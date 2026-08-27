package com.shcj.cache.stats.app.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shcj.cache.constant.AppDataMigrateEnum;
import com.shcj.cache.constant.AppDataMigrateResult;
import com.shcj.cache.constant.AppDataMigrateStatusEnum;
import com.shcj.cache.dao.AppDataMigrateStatusDao;
import com.shcj.cache.entity.AppDataMigrateStatus;
import com.shcj.cache.redis.RedisCenter;
import com.shcj.cache.util.ConstUtils;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.Protocol;
import redis.clients.jedis.ScanParams;
import redis.clients.jedis.ScanResult;
import redis.clients.jedis.commands.ProtocolCommand;
import redis.clients.jedis.util.SafeEncoder;

import javax.annotation.PostConstruct;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class EmbeddedRedisShakeService {

    private static final Logger logger = LoggerFactory.getLogger(EmbeddedRedisShakeService.class);

    public static final int TOOL_ID = -461;
    public static final String TOOL_NAME = "redis-shake-4.6.1 (embedded)";

    /**
     * 内嵌任务的标识。
     *
     * <p>migrate_tool 认不出来：内嵌与远程 redis-shake 写的都是 0，
     * 真正区分二者的是 migrate_machine_ip 上的这个前缀。</p>
     */
    private static final String EMBEDDED_MACHINE_PREFIX = "embedded@";

    /** 启动宽限期：这段时间内即使探测不到也不判异常 */
    private static final long STARTUP_GRACE_MILLIS = 60_000L;
    private static final String VERSION = "4.6.1";
    private static final long LINUX_AMD64_BINARY_SIZE = 11980866L;

    @Autowired
    private RedisCenter redisCenter;
    @Autowired
    private AppDataMigrateStatusDao migrateStatusDao;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, Process> processes = new ConcurrentHashMap<>();
    private Path home;
    private Path binary;

    @PostConstruct
    public void init() throws IOException {
        String catalinaBase = System.getProperty("catalina.base", System.getProperty("java.io.tmpdir"));
        home = new File(catalinaBase, "redis-shake").toPath();
        binary = home.resolve("bin").resolve("v" + VERSION).resolve("redis-shake");
        Files.createDirectories(binary.getParent());
        if (!Files.exists(binary) || Files.size(binary) != LINUX_AMD64_BINARY_SIZE) {
            try (InputStream input = getClass().getResourceAsStream(
                    "/tools/redisshake/v4.6.1/redis-shake")) {
                if (input == null) {
                    throw new FileNotFoundException("embedded RedisShake 4.6.1 binary not found");
                }
                Files.copy(input, binary, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        if (!binary.toFile().setExecutable(true, true) && !binary.toFile().canExecute()) {
            throw new IOException("cannot set RedisShake executable permission: " + binary);
        }
    }

    public AppDataMigrateResult check(AppDataMigrateEnum sourceType, String sourceServers, String sourcePassword,
                                      AppDataMigrateEnum targetType, String targetServers, String targetPassword,
                                      boolean clearTarget) {
        if (!supported(sourceType) || !supported(targetType)) {
            return AppDataMigrateResult.fail("source and target type must be cluster or non-cluster");
        }
        String source = firstAddress(sourceServers);
        String target = firstAddress(targetServers);
        if (StringUtils.isBlank(source) || StringUtils.isBlank(target)) {
            return AppDataMigrateResult.fail("source and target cluster addresses are required");
        }
        String[] sourceHp = splitAddress(source);
        String[] targetHp = splitAddress(target);
        if (!redisCenter.isRun(sourceHp[0], Integer.parseInt(sourceHp[1]), sourcePassword)) {
            return AppDataMigrateResult.fail("source cluster is unreachable or password is invalid: " + source);
        }
        if (!redisCenter.isRun(targetHp[0], Integer.parseInt(targetHp[1]), targetPassword)) {
            return AppDataMigrateResult.fail("target cluster is unreachable or password is invalid: " + target);
        }
        if (clearTarget) {
            try {
                boolean flushAllDisabled = false;
                for (String master : targetNodes(target, targetPassword, targetType == AppDataMigrateEnum.cluster)) {
                    if (!isCommandAvailable(master, targetPassword, "FLUSHALL")) {
                        flushAllDisabled = true;
                    }
                    ensureCommandAvailable(master, targetPassword, "UNLINK");
                }
                if (flushAllDisabled) {
                    return AppDataMigrateResult.success(
                            "目标库已禁用 FLUSHALL，开始迁移后将使用 SCAN + UNLINK 批量非阻塞清空目标库");
                }
            } catch (Exception e) {
                return AppDataMigrateResult.fail("目标库批量清空能力检查失败: " + rootMessage(e));
            }
        }
        return AppDataMigrateResult.success("RedisShake 4.6.1 configuration check passed");
    }

    public String detectRedisVersion(String servers, String password) {
        String address = firstAddress(servers);
        if (StringUtils.isBlank(address)) return "";
        String[] hp = splitAddress(address);
        try (Jedis jedis = redisCenter.getJedis(hp[0], Integer.parseInt(hp[1]), password)) {
            for (String line : jedis.info("server").split("\\r?\\n")) {
                if (line.startsWith("redis_version:")) return line.substring("redis_version:".length()).trim();
            }
        } catch (Exception ignored) { }
        return "";
    }

    public AppDataMigrateStatus start(AppDataMigrateEnum sourceType, AppDataMigrateEnum targetType,
                                      String sourceServers, String targetServers, long sourceAppId, long targetAppId,
                                      String sourcePassword, String targetPassword, String sourceVersion,
                                      String targetVersion, long userId, Map<String, Object> options) {
        String migrateId = new java.text.SimpleDateFormat("yyyyMMddHHmmssSSS").format(new Date());
        Path taskDir = home.resolve("tasks").resolve(migrateId);
        try {
            Files.createDirectories(taskDir);
            boolean clearTarget = bool(options, "clearTarget", false);
            if (clearTarget) {
                clearTarget(firstAddress(targetServers), targetPassword, targetType == AppDataMigrateEnum.cluster);
            }
            int statusPort = freePort();
            Path config = taskDir.resolve("shake.toml");
            Path log = taskDir.resolve("data").resolve("shake.log");
            Path console = taskDir.resolve("console.log");
            Files.write(config, buildConfig(taskDir, log, statusPort, firstAddress(sourceServers), sourcePassword,
                    sourceType == AppDataMigrateEnum.cluster, firstAddress(targetServers), targetPassword,
                    targetType == AppDataMigrateEnum.cluster, options).getBytes(StandardCharsets.UTF_8));
            config.toFile().setReadable(false, false);
            config.toFile().setReadable(true, true);
            config.toFile().setWritable(true, true);

            ProcessBuilder processBuilder = new ProcessBuilder(binary.toString(), config.toString());
            processBuilder.directory(taskDir.toFile());
            processBuilder.redirectErrorStream(true);
            processBuilder.redirectOutput(ProcessBuilder.Redirect.appendTo(console.toFile()));
            Process process = processBuilder.start();
            processes.put(migrateId, process);
            Files.write(taskDir.resolve("pid"), String.valueOf(processPid(process)).getBytes(StandardCharsets.US_ASCII));

            AppDataMigrateStatus status = new AppDataMigrateStatus();
            status.setMigrateId(migrateId);
            status.setMigrateTool(0);
            status.setMigrateMachineIp(EMBEDDED_MACHINE_PREFIX + localHostName());
            status.setMigrateMachinePort(statusPort);
            status.setSourceMigrateType(sourceType.getIndex());
            status.setTargetMigrateType(targetType.getIndex());
            status.setSourceServers(sourceServers);
            status.setTargetServers(targetServers);
            status.setSourceAppId(sourceAppId);
            status.setTargetAppId(targetAppId);
            status.setRedisSourceVersion(sourceVersion);
            status.setRedisTargetVersion(targetVersion);
            status.setUserId(userId);
            status.setStatus(AppDataMigrateStatusEnum.PREPARE.getStatus());
            status.setStartTime(new Date());
            status.setLogPath(log.toString());
            status.setConfigPath(config.toString());
            migrateStatusDao.save(status);
            return status;
        } catch (Exception e) {
            throw new IllegalStateException("failed to start embedded RedisShake: " + rootMessage(e), e);
        }
    }

    public AppDataMigrateResult stop(long id) {
        AppDataMigrateStatus status = migrateStatusDao.get(id);
        if (status == null) return AppDataMigrateResult.fail("migration task does not exist: " + id);
        Process process = processes.remove(status.getMigrateId());
        if (process != null && process.isAlive()) {
            process.destroy();
            try {
                if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) process.destroyForcibly();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        } else {
            killPidFile(status);
        }
        migrateStatusDao.updateStatus(id, AppDataMigrateStatusEnum.END.getStatus());
        return AppDataMigrateResult.success("RedisShake task stopped");
    }

    public AppDataMigrateStatus resync(long id, long userId) {
        AppDataMigrateStatus original = migrateStatusDao.get(id);
        if (original == null) throw new IllegalArgumentException("migration task does not exist: " + id);
        if (!isEmbedded(original))
            throw new IllegalArgumentException("only embedded RedisShake tasks can be resynchronized");
        // 异常退出的任务同样允许重来：应用一重启，跑着的 RedisShake 子进程就没了，
        // 只认 END 的话这些任务会既跑不起来又只能删掉重建。
        boolean restartable = original.getStatus() == AppDataMigrateStatusEnum.END.getStatus()
                || original.getStatus() == AppDataMigrateStatusEnum.ERROR.getStatus();
        if (!restartable || isRunning(original))
            throw new IllegalStateException("only completed or failed migration tasks can be resynchronized");

        String migrateId = original.getMigrateId();
        Path config = Paths.get(original.getConfigPath());
        Path taskDir = config.getParent();
        try {
            List<String> oldConfig = Files.readAllLines(config, StandardCharsets.UTF_8);
            deleteTree(taskDir.resolve("data"));
            Files.createDirectories(taskDir.resolve("data"));
            int statusPort = freePort();
            Path log = taskDir.resolve("data").resolve("shake.log");
            Path console = taskDir.resolve("console.log");
            List<String> newConfig = new ArrayList<>();
            for (String oldLine : oldConfig) {
                String line = oldLine;
                String trimmed = line.trim();
                if (trimmed.startsWith("dir =")) line = "dir = " + toml(taskDir.resolve("data").toString());
                else if (trimmed.startsWith("log_file =")) line = "log_file = \"shake.log\"";
                else if (trimmed.startsWith("status_port =")) line = "status_port = " + statusPort;
                newConfig.add(line);
            }
            Files.write(config, newConfig, StandardCharsets.UTF_8);
            Files.write(console, new byte[0]);
            config.toFile().setReadable(false, false);
            config.toFile().setReadable(true, true);
            config.toFile().setWritable(true, true);

            ProcessBuilder builder = new ProcessBuilder(binary.toString(), config.toString());
            builder.directory(taskDir.toFile());
            builder.redirectErrorStream(true);
            builder.redirectOutput(ProcessBuilder.Redirect.appendTo(console.toFile()));
            Process process = builder.start();
            processes.put(migrateId, process);
            Files.write(taskDir.resolve("pid"), String.valueOf(processPid(process)).getBytes(StandardCharsets.US_ASCII));

            migrateStatusDao.resetForResync(id, statusPort, userId, log.toString(), config.toString());
            return migrateStatusDao.get(id);
        } catch (Exception e) {
            throw new IllegalStateException("failed to resynchronize with embedded RedisShake: " + rootMessage(e), e);
        }
    }

    public void delete(long id) {
        AppDataMigrateStatus status = migrateStatusDao.get(id);
        if (status == null) return;
        if (isRunning(status)) throw new IllegalStateException("running migration tasks cannot be deleted");
        if (status.getStatus() != AppDataMigrateStatusEnum.END.getStatus()
                && status.getStatus() != AppDataMigrateStatusEnum.ERROR.getStatus())
            throw new IllegalStateException("only completed or failed migration tasks can be deleted");
        migrateStatusDao.delete(id);
        if (StringUtils.isNotBlank(status.getConfigPath())) deleteTree(Paths.get(status.getConfigPath()).getParent());
    }

    public Map<String, Object> compareKeyCounts(long id) {
        AppDataMigrateStatus status = migrateStatusDao.get(id);
        if (status == null) throw new IllegalArgumentException("migration task does not exist: " + id);
        if (status.getStatus() == AppDataMigrateStatusEnum.ERROR.getStatus())
            throw new IllegalStateException("failed migration tasks cannot perform key count validation");
        try {
            Map<String, Map<String, String>> config = readTomlSections(Paths.get(status.getConfigPath()));
            long source = countKeys(config.get("sync_reader"));
            long target = countKeys(config.get("redis_writer"));
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("sourceKeyCount", source);
            result.put("targetKeyCount", target);
            result.put("difference", target - source);
            result.put("consistent", source == target);
            result.put("running", status.getStatus() != AppDataMigrateStatusEnum.END.getStatus());
            return result;
        } catch (Exception e) {
            throw new IllegalStateException("failed to compare Redis key counts: " + rootMessage(e), e);
        }
    }

    private long countKeys(Map<String, String> section) {
        if (section == null) throw new IllegalArgumentException("RedisShake endpoint configuration is missing");
        String address = section.get("address");
        String password = section.get("password");
        boolean cluster = Boolean.parseBoolean(section.get("cluster"));
        List<String> nodes = cluster ? clusterMasters(address, password) : Collections.singletonList(address);
        long total = 0;
        for (String node : nodes) {
            String[] hp = splitAddress(node);
            try (Jedis jedis = redisCenter.getJedis(hp[0], Integer.parseInt(hp[1]), password)) {
                if (cluster) total += jedis.dbSize();
                else total += keyspaceCount(jedis.info("keyspace"));
            }
        }
        return total;
    }

    private long keyspaceCount(String info) {
        long total = 0;
        if (StringUtils.isBlank(info)) return total;
        for (String line : info.split("\\r?\\n")) {
            int keys = line.indexOf("keys=");
            if (!line.startsWith("db") || keys < 0) continue;
            int end = line.indexOf(',', keys);
            String value = line.substring(keys + 5, end < 0 ? line.length() : end);
            try { total += Long.parseLong(value); } catch (NumberFormatException ignored) { }
        }
        return total;
    }

    private Map<String, Map<String, String>> readTomlSections(Path config) throws IOException {
        Map<String, Map<String, String>> sections = new LinkedHashMap<>();
        Map<String, String> current = null;
        for (String raw : Files.readAllLines(config, StandardCharsets.UTF_8)) {
            String line = raw.trim();
            if (line.startsWith("[") && line.endsWith("]")) {
                current = new LinkedHashMap<>();
                sections.put(line.substring(1, line.length() - 1), current);
            } else if (current != null && line.contains("=")) {
                int split = line.indexOf('=');
                String value = line.substring(split + 1).trim();
                if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\""))
                    value = value.substring(1, value.length() - 1).replace("\\\"", "\"").replace("\\\\", "\\");
                current.put(line.substring(0, split).trim(), value);
            }
        }
        return sections;
    }

    public Map<String, Object> progress(long id) {
        AppDataMigrateStatus status = migrateStatusDao.get(id);
        if (status == null) return Collections.singletonMap("error", "migration task does not exist");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("migrateId", status.getMigrateId());
        result.put("running", isRunning(status));
        Map<String, Object> raw = fetchProgress(status, 1000, 3000);
        if (raw != null) {
            result.putAll(summarize(raw));
            result.put("raw", raw);
            updateStatusFromProgress(status, raw);
        } else {
            result.put("message", isRunning(status) ? "RedisShake is starting; status endpoint is not ready"
                    : "RedisShake process has exited");
            result.put("recentLog", tail(status.getLogPath(), 30));
        }
        return result;
    }

    private String buildConfig(Path taskDir, Path log, int statusPort, String source, String sourcePassword,
                               boolean sourceCluster, String target, String targetPassword,
                               boolean targetCluster, Map<String, Object> options) {
        StringBuilder c = new StringBuilder();
        c.append("[sync_reader]\ncluster = ").append(sourceCluster).append("\naddress = ").append(toml(source));
        c.append("\npassword = ").append(toml(sourcePassword)).append("\nsync_rdb = true\nsync_aof = true\n\n");
        c.append("[redis_writer]\ncluster = ").append(targetCluster).append("\naddress = ").append(toml(target));
        c.append("\npassword = ").append(toml(targetPassword)).append("\n\n[filter]\n");
        appendArray(c, "allow_key_prefix", lines(options.get("allowKeyPrefix")));
        appendArray(c, "allow_key_suffix", lines(options.get("allowKeySuffix")));
        appendArray(c, "allow_key_regex", lines(options.get("allowKeyRegex")));
        appendArray(c, "block_key_prefix", lines(options.get("blockKeyPrefix")));
        appendArray(c, "block_key_suffix", lines(options.get("blockKeySuffix")));
        appendArray(c, "block_key_regex", lines(options.get("blockKeyRegex")));
        c.append("\n[advanced]\ndir = ").append(toml(taskDir.resolve("data").toString()));
        c.append("\nlog_file = ").append(toml(log.getFileName().toString()));
        c.append("\nlog_level = \"info\"\nrdb_restore_command_behavior = ")
                .append(toml(restoreCommandBehavior(options)));
        c.append("\nempty_db_before_sync = false\nstatus_port = ").append(statusPort);
        c.append("\npipeline_count_limit = ").append(intVal(options, "pipelineCountLimit", 1024, 1, 100000));
        c.append("\ntarget_redis_max_qps = ").append(intVal(options, "targetRedisMaxQps", 300000, 1, 300000));
        c.append("\n");
        return c.toString();
    }

    private void clearTarget(String seed, String password, boolean cluster) {
        List<String> masters = targetNodes(seed, password, cluster);
        for (String master : masters) {
            String[] hp = splitAddress(master);
            try (Jedis jedis = redisCenter.getJedis(hp[0], Integer.parseInt(hp[1]), password)) {
                for (Integer database : targetDatabases(jedis, cluster)) {
                    if (!cluster) jedis.select(database);
                    unlinkAll(jedis, master, database);
                }
            } catch (Exception e) {
                throw new IllegalStateException(master + " 使用 SCAN + UNLINK 清空失败: " + rootMessage(e), e);
            }
        }
    }

    private List<Integer> targetDatabases(Jedis jedis, boolean cluster) {
        if (cluster) return Collections.singletonList(0);
        TreeSet<Integer> databases = new TreeSet<>();
        databases.add(0);
        String keyspace = jedis.info("keyspace");
        if (StringUtils.isNotBlank(keyspace)) {
            for (String line : keyspace.split("\\r?\\n")) {
                if (!line.startsWith("db") || !line.contains(":")) continue;
                try {
                    databases.add(Integer.parseInt(line.substring(2, line.indexOf(':'))));
                } catch (NumberFormatException ignored) { }
            }
        }
        return new ArrayList<>(databases);
    }

    private void unlinkAll(Jedis jedis, String address, int database) {
        final int batchSize = 5000;
        long removed = 0;
        int pass = 0;
        while (jedis.dbSize() > 0) {
            byte[] cursor = ScanParams.SCAN_POINTER_START_BINARY;
            do {
                ScanResult<byte[]> result = jedis.scan(cursor, new ScanParams().count(batchSize));
                cursor = result.getCursorAsBytes();
                List<byte[]> keys = result.getResult();
                if (keys != null && !keys.isEmpty()) {
                    jedis.unlink(keys.toArray(new byte[keys.size()][]));
                    removed += keys.size();
                }
            } while (!Arrays.equals(cursor, ScanParams.SCAN_POINTER_START_BINARY));
            pass++;
            if (pass >= 10 && jedis.dbSize() > 0) {
                throw new IllegalStateException("连续清理 10 轮后仍剩余 " + jedis.dbSize() + " 个 key");
            }
        }
        logger.info("target Redis cleared with SCAN+UNLINK address={} database={} removedKeys={}",
                address, database, removed);
    }

    private List<String> targetNodes(String seed, String password, boolean cluster) {
        return cluster ? clusterMasters(seed, password) : Collections.singletonList(seed);
    }

    private boolean supported(AppDataMigrateEnum type) {
        return type == AppDataMigrateEnum.cluster || type == AppDataMigrateEnum.standalone;
    }

    private boolean isCommandAvailable(String address, String password, String command) {
        String[] hp = splitAddress(address);
        try (Jedis jedis = redisCenter.getJedis(hp[0], Integer.parseInt(hp[1]), password)) {
            jedis.getClient().sendCommand(RawCommand.COMMAND, SafeEncoder.encode("INFO"),
                    SafeEncoder.encode(command));
            List<Object> reply = jedis.getClient().getObjectMultiBulkReply();
            return reply != null && !reply.isEmpty() && reply.get(0) != null;
        } catch (Exception e) {
            return false;
        }
    }

    private void ensureCommandAvailable(String address, String password, String command) {
        if (!isCommandAvailable(address, password, command)) {
            throw new IllegalStateException(address + " 未启用 " + command + " 命令");
        }
    }

    private List<String> clusterMasters(String seed, String password) {
        String[] hp = splitAddress(seed);
        String nodes;
        try (Jedis jedis = redisCenter.getJedis(hp[0], Integer.parseInt(hp[1]), password)) {
            nodes = jedis.clusterNodes();
        }
        LinkedHashSet<String> masters = new LinkedHashSet<>();
        for (String line : nodes.split("\\r?\\n")) {
            String[] fields = line.trim().split("\\s+");
            if (fields.length < 3 || fields[2].contains("fail") || !fields[2].contains("master")) continue;
            String address = fields[1].split("@")[0];
            if (address.contains(":")) masters.add(address);
        }
        if (masters.isEmpty()) throw new IllegalStateException("no online master nodes found in target cluster");
        return new ArrayList<>(masters);
    }

    private Map<String, Object> summarize(Map<String, Object> raw) {
        Map<String, Object> result = new LinkedHashMap<>();
        Object entries = raw.get("total_entries_count");
        result.put("entries", entries);
        result.put("consistent", raw.get("consistent"));
        List<Map<String, Object>> readers = normalizeReaders(raw.get("reader"));
        long rdbTotal = 0, rdbReceived = 0, rdbSent = 0, aofReceived = 0;
        Set<String> stages = new LinkedHashSet<>();
        if (readers != null) for (Map<String, Object> reader : readers) {
            stages.add(String.valueOf(reader.get("status")));
            rdbTotal += longValue(reader.get("rdb_file_size_bytes"));
            rdbReceived += longValue(reader.get("rdb_received_bytes"));
            rdbSent += longValue(reader.get("rdb_sent_bytes"));
            aofReceived += longValue(reader.get("aof_received_bytes"));
        }
        result.put("stage", StringUtils.join(stages, ", "));
        result.put("rdbTotalBytes", rdbTotal);
        result.put("rdbReceivedBytes", rdbReceived);
        result.put("rdbSentBytes", rdbSent);
        result.put("rdbProgress", rdbTotal <= 0 ? 0 : Math.min(100, rdbSent * 100 / rdbTotal));
        result.put("aofReceivedBytes", aofReceived);
        return result;
    }

    private List<Map<String, Object>> normalizeReaders(Object readerValue) {
        if (readerValue == null) return Collections.emptyList();
        if (readerValue instanceof Collection) {
            List<Map<String, Object>> readers = new ArrayList<>();
            for (Object reader : (Collection<?>) readerValue) {
                readers.add(objectMapper.convertValue(reader, new TypeReference<Map<String, Object>>() {}));
            }
            return readers;
        }
        return Collections.singletonList(
                objectMapper.convertValue(readerValue, new TypeReference<Map<String, Object>>() {}));
    }

    /**
     * 列表页用的状态刷新。
     *
     * <p>状态原先只在 progress() 里推进，也就是只有打开某个任务的进度视图才会更新。
     * 列表页直接读库，正在迁移的任务于是一直停在「准备阶段」。这里给列表一个短超时的
     * 刷新入口：拿不到就保持原值，不猜、不改判终态。</p>
     */
    public void refreshStatus(AppDataMigrateStatus status) {
        if (status == null || !isEmbedded(status)) {
            return;
        }
        int current = status.getStatus();
        // 终态不再回读：已结束/已失败的任务其状态端口早就没了
        if (current == AppDataMigrateStatusEnum.END.getStatus()
                || current == AppDataMigrateStatusEnum.ERROR.getStatus()) {
            return;
        }
        // 超时给得比 progress() 短：这是列表页的批量回读，一个卡死的任务不该拖慢整页
        Map<String, Object> raw = fetchProgress(status, 500, 1000);
        if (raw != null) {
            updateStatusFromProgress(status, raw);
            return;
        }
        // 端口不通且进程也没了：任务是异常退出的（应用重启、被 kill、自身崩溃）。
        // 不收敛成终态的话，这行会永远停在「准备阶段」，而 resync 要求 END、
        // delete 要求 END/ERROR，等于既跑不起来也删不掉。
        if (!isRunning(status) && startedLongEnough(status)) {
            migrateStatusDao.updateStatus(status.getId(), AppDataMigrateStatusEnum.ERROR.getStatus());
            status.setStatus(AppDataMigrateStatusEnum.ERROR.getStatus());
        }
    }

    /**
     * 刚启动的任务给一段宽限期。
     *
     * <p>RedisShake 起来到状态端口可用之间有几秒空窗，这期间 pid 文件也可能还没落盘，
     * 此时判死会把正常启动的任务误判为异常。</p>
     */
    private boolean startedLongEnough(AppDataMigrateStatus status) {
        Date startTime = status.getStartTime();
        return startTime == null
                || System.currentTimeMillis() - startTime.getTime() > STARTUP_GRACE_MILLIS;
    }

    private boolean isEmbedded(AppDataMigrateStatus status) {
        return status.getMigrateMachineIp() != null
                && status.getMigrateMachineIp().startsWith(EMBEDDED_MACHINE_PREFIX);
    }

    private Map<String, Object> fetchProgress(AppDataMigrateStatus status, int connectTimeout, int readTimeout) {
        try {
            HttpURLConnection connection = (HttpURLConnection) new URL(
                    "http://127.0.0.1:" + status.getMigrateMachinePort() + "/").openConnection();
            connection.setConnectTimeout(connectTimeout);
            connection.setReadTimeout(readTimeout);
            try (InputStream input = connection.getInputStream()) {
                return objectMapper.readValue(input, new TypeReference<Map<String, Object>>() {});
            }
        } catch (Exception e) {
            return null;
        }
    }

    private void updateStatusFromProgress(AppDataMigrateStatus status, Map<String, Object> raw) {
        String json = String.valueOf(raw.get("reader"));
        int next = json.contains("syncing aof") ? AppDataMigrateStatusEnum.FULL_END.getStatus()
                : AppDataMigrateStatusEnum.START.getStatus();
        if (status.getStatus() != next) {
            migrateStatusDao.updateStatus(status.getId(), next);
            // 同步回内存对象：调用方（列表页）拿的就是这个实例，只写库的话页面这一轮还是旧值
            status.setStatus(next);
        }
    }

    private boolean isRunning(AppDataMigrateStatus status) {
        Process process = processes.get(status.getMigrateId());
        if (process != null) return process.isAlive();
        Path pid = new File(status.getConfigPath()).toPath().getParent().resolve("pid");
        if (!Files.exists(pid)) return false;
        try {
            String value = new String(Files.readAllBytes(pid), StandardCharsets.US_ASCII).trim();
            Process probe = new ProcessBuilder("/bin/sh", "-c", "kill -0 " + Long.parseLong(value)).start();
            return probe.waitFor() == 0;
        } catch (Exception e) { return false; }
    }

    private void killPidFile(AppDataMigrateStatus status) {
        try {
            Path pid = new File(status.getConfigPath()).toPath().getParent().resolve("pid");
            if (Files.exists(pid)) {
                long value = Long.parseLong(new String(Files.readAllBytes(pid), StandardCharsets.US_ASCII).trim());
                new ProcessBuilder("/bin/kill", String.valueOf(value)).start().waitFor();
            }
        } catch (Exception ignored) { }
    }

    private long processPid(Process process) {
        try { return ((Number) Process.class.getMethod("pid").invoke(process)).longValue(); }
        catch (Exception ignored) {
            try {
                java.lang.reflect.Field field = process.getClass().getDeclaredField("pid");
                field.setAccessible(true);
                return field.getLong(process);
            } catch (Exception e) { return -1; }
        }
    }

    private int freePort() throws IOException { try (ServerSocket socket = new ServerSocket(0)) { return socket.getLocalPort(); } }
    private String localHostName() { try { return java.net.InetAddress.getLocalHost().getHostAddress(); } catch (Exception e) { return "localhost"; } }
    private String firstAddress(String servers) { return StringUtils.isBlank(servers) ? "" : servers.trim().split("\\r?\\n")[0].trim(); }
    private String[] splitAddress(String address) {
        int colon = address.lastIndexOf(':');
        if (colon <= 0) throw new IllegalArgumentException("invalid address: " + address);
        String port = address.substring(colon + 1);
        Integer.parseInt(port);
        return new String[]{address.substring(0, colon), port};
    }
    private String toml(String value) { return "\"" + StringUtils.defaultString(value).replace("\\", "\\\\").replace("\"", "\\\"") + "\""; }
    private void appendArray(StringBuilder c, String name, List<String> values) {
        c.append(name).append(" = [");
        for (int i = 0; i < values.size(); i++) { if (i > 0) c.append(", "); c.append(toml(values.get(i))); }
        c.append("]\n");
    }
    private List<String> lines(Object value) {
        if (value == null) return Collections.emptyList();
        List<String> result = new ArrayList<>();
        for (String line : String.valueOf(value).split("[\\r\\n,]+")) if (StringUtils.isNotBlank(line)) result.add(line.trim());
        return result;
    }
    private boolean bool(Map<String, Object> map, String key, boolean fallback) { Object v = map.get(key); return v == null ? fallback : Boolean.parseBoolean(String.valueOf(v)); }
    private String restoreCommandBehavior(Map<String, Object> options) {
        Object configured = options.get("rdbRestoreCommandBehavior");
        String value = configured == null ? "rewrite" : String.valueOf(configured);
        if (StringUtils.isBlank(value)) value = "rewrite";
        return Arrays.asList("rewrite", "skip", "panic").contains(value) ? value : "rewrite";
    }
    private int intVal(Map<String, Object> map, String key, int fallback, int min, int max) {
        try { return Math.max(min, Math.min(max, Integer.parseInt(String.valueOf(map.get(key))))); }
        catch (Exception e) { return fallback; }
    }
    private long longValue(Object value) { try { return Long.parseLong(String.valueOf(value)); } catch (Exception e) { return 0; } }
    private String rootMessage(Throwable error) { Throwable t = error; while (t.getCause() != null) t = t.getCause(); return StringUtils.isBlank(t.getMessage()) ? t.getClass().getSimpleName() : t.getMessage(); }
    private List<String> tail(String path, int count) {
        if (StringUtils.isBlank(path) || !new File(path).exists()) return Collections.emptyList();
        try { List<String> all = Files.readAllLines(new File(path).toPath(), StandardCharsets.UTF_8); return all.subList(Math.max(0, all.size() - count), all.size()); }
        catch (Exception e) { return Collections.singletonList(e.getMessage()); }
    }

    private void deleteTree(Path root) {
        if (root == null || !Files.exists(root)) return;
        try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); }
                catch (IOException e) { throw new UncheckedIOException(e); }
            });
        } catch (IOException | UncheckedIOException e) {
            throw new IllegalStateException("failed to clean task directory: " + rootMessage(e), e);
        }
    }

    private enum RawCommand implements ProtocolCommand {
        COMMAND;
        @Override
        public byte[] getRaw() { return SafeEncoder.encode(name()); }
    }
}
