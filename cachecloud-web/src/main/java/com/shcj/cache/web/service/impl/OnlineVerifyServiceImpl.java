package com.shcj.cache.web.service.impl;

import com.shcj.cache.dao.AppDao;
import com.shcj.cache.dao.InstanceDao;
import com.shcj.cache.entity.AppDesc;
import com.shcj.cache.entity.InstanceInfo;
import com.shcj.cache.redis.util.AuthUtil;
import com.shcj.cache.util.ConstUtils;
import com.shcj.cache.util.StringUtil;
import com.shcj.cache.web.controller.api.dto.OnlineHealthCheckResultDto;
import com.shcj.cache.web.controller.api.dto.OnlineHealthCheckResultDto.OnlineHealthCheckItemDto;
import com.shcj.cache.web.controller.api.dto.OnlineHealthCheckResultDto.OnlineHealthTargetDto;
import com.shcj.cache.web.service.OnlineVerifyService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.Protocol;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 在线健康检查：对齐 check_redis_status.py。
 * 已纳管节点可只填 ip:port，自动使用平台应用密码。
 */
@Service
@Slf4j
public class OnlineVerifyServiceImpl implements OnlineVerifyService {

    private static final String SEPARATOR = "----------------------------------------";
    private static final String INFO = "Info";
    private static final String ERROR = "Error";
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int CONNECT_TIMEOUT_MS = Protocol.DEFAULT_TIMEOUT;
    private static final int SO_TIMEOUT_MS = Protocol.DEFAULT_TIMEOUT * 3;

    @Autowired
    private InstanceDao instanceDao;

    @Autowired
    private AppDao appDao;

    @Override
    public OnlineHealthCheckResultDto verifyDetailed(String servers, String verifyType) {
        OnlineHealthCheckResultDto result = new OnlineHealthCheckResultDto();
        List<ServerTarget> targets = parseServers(servers);
        if (targets.isEmpty()) {
            result.setResult("请输入有效的服务器信息（每行一个，格式 ip:port 或 ip:port:password）\n");
            return result;
        }

        StringBuilder text = new StringBuilder();
        for (int i = 0; i < targets.size(); i++) {
            if (i > 0) {
                text.append(SEPARATOR).append('\n');
            }
            OnlineHealthTargetDto targetDto = checkHealth(targets.get(i));
            result.getTargets().add(targetDto);
            text.append(formatTargetText(targetDto));
        }
        result.setResult(text.toString());
        return result;
    }

    private OnlineHealthTargetDto checkHealth(ServerTarget target) {
        OnlineHealthTargetDto dto = new OnlineHealthTargetDto();
        dto.setHost(target.ip + ":" + target.port);

        // 未手填密码时，从纳管实例/应用自动取密码
        ManagedAuth managed = resolveManagedAuth(target);
        String password = StringUtils.isNotBlank(target.password) ? target.password : managed.password;
        boolean managedHit = StringUtils.isBlank(target.password) && managed.found;
        if (managedHit) {
            dto.setManaged(true);
            dto.setAppId(managed.appId);
            dto.setAppName(managed.appName);
        }

        Jedis jedis = null;
        try {
            jedis = connect(target.ip, target.port, password);
            // 带密码失败时再试无密码（Sentinel 常见）
            if (jedis == null && StringUtils.isNotBlank(password)) {
                jedis = connect(target.ip, target.port, null);
            }
            // 手填密码失败、且未用纳管密码时，再尝试纳管密码
            if (jedis == null && StringUtils.isNotBlank(target.password)
                    && managed.found && StringUtils.isNotBlank(managed.password)
                    && !managed.password.equals(target.password)) {
                jedis = connect(target.ip, target.port, managed.password);
                if (jedis != null) {
                    dto.setManaged(true);
                    dto.setAppId(managed.appId);
                    dto.setAppName(managed.appName);
                }
            }
            if (jedis == null) {
                dto.setMode("unknown");
                dto.setModeLabel("未知");
                dto.setAllOk(false);
                dto.setConnectError(buildConnectError(target, managed));
                return dto;
            }

            String infoAll = safeInfo(jedis, "all");
            if (StringUtils.isBlank(infoAll) && StringUtils.isNotBlank(password)) {
                closeQuietly(jedis);
                jedis = connect(target.ip, target.port, null);
                infoAll = jedis == null ? null : safeInfo(jedis, "all");
            }
            if (StringUtils.isBlank(infoAll)) {
                dto.setMode("unknown");
                dto.setModeLabel("未知");
                dto.setAllOk(false);
                dto.setConnectError(buildConnectError(target, managed));
                return dto;
            }

            Map<String, String> redis = parseInfo(infoAll);
            boolean isSentinel = infoAll.contains("# Sentinel")
                    || (managed.instanceType != null && managed.instanceType == ConstUtils.CACHE_REDIS_SENTINEL);
            boolean isCluster = "1".equals(redis.get("cluster_enabled"));

            if (isSentinel) {
                dto.setMode("sentinel");
                dto.setModeLabel("Sentinel");
                fillSentinelStatus(jedis, redis);
            } else if (isCluster) {
                dto.setMode("cluster");
                dto.setModeLabel("Cluster");
                fillClusterStatus(jedis, redis);
            } else {
                dto.setMode("standalone");
                dto.setModeLabel("Standalone / 主从");
            }

            List<OnlineHealthCheckItemDto> checks = analyzeStatus(jedis, redis, isSentinel, isCluster);
            dto.setChecks(checks);
            dto.setAllOk(checks.stream().noneMatch(c -> ERROR.equals(c.getLevel())));
            return dto;
        } catch (Exception e) {
            log.error("online health check failed, {}:{}", target.ip, target.port, e);
            dto.setMode("unknown");
            dto.setModeLabel("未知");
            dto.setAllOk(false);
            dto.setConnectError("Redis节点连接异常: "
                    + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
            return dto;
        } finally {
            closeQuietly(jedis);
        }
    }

    /**
     * 按 ip:port 查纳管实例，取所属应用密码。
     */
    private ManagedAuth resolveManagedAuth(ServerTarget target) {
        ManagedAuth auth = new ManagedAuth();
        try {
            InstanceInfo inst = instanceDao.getAllInstByIpAndPort(target.ip, target.port);
            if (inst == null) {
                // 兼容 status=1 查询
                inst = instanceDao.getInstByIpAndPort(target.ip, target.port);
            }
            if (inst == null || inst.getAppId() <= 0) {
                return auth;
            }
            AppDesc appDesc = appDao.getAppDescById(inst.getAppId());
            if (appDesc == null) {
                return auth;
            }
            auth.found = true;
            auth.appId = appDesc.getAppId();
            auth.appName = appDesc.getName();
            auth.instanceType = inst.getType();
            // Sentinel 节点：仅在开启哨兵密码时才鉴权
            if (inst.getType() == ConstUtils.CACHE_REDIS_SENTINEL && !appDesc.hasSentinelPwdFlag()) {
                auth.password = null;
            } else {
                auth.password = appDesc.getAppPassword();
            }
        } catch (Exception e) {
            log.warn("resolve managed auth failed {}:{} - {}", target.ip, target.port, e.getMessage());
        }
        return auth;
    }

    private String buildConnectError(ServerTarget target, ManagedAuth managed) {
        if (managed.found) {
            return "已纳管节点连接失败（应用: " + StringUtil.defaultIfBlank(managed.appName, String.valueOf(managed.appId))
                    + "）。请确认实例存活，或手动追加密码：ip:port:password";
        }
        if (StringUtils.isBlank(target.password)) {
            return "Redis节点连接异常。未在平台查到该 ip:port 的纳管记录，请补充密码：ip:port:password";
        }
        return "Redis节点连接异常，请检查连接串及Redis存活状态";
    }

    private Jedis connect(String ip, int port, String password) {
        Jedis jedis = new Jedis(ip, port);
        jedis.getClient().setConnectionTimeout(CONNECT_TIMEOUT_MS);
        jedis.getClient().setSoTimeout(SO_TIMEOUT_MS);
        try {
            if (StringUtils.isBlank(password)) {
                jedis.ping();
            } else {
                AuthUtil.auth(jedis, password);
            }
            return jedis;
        } catch (Exception e) {
            log.warn("connect redis failed {}:{} - {}", ip, port, e.getMessage());
            closeQuietly(jedis);
            return null;
        }
    }

    private String safeInfo(Jedis jedis, String section) {
        try {
            return jedis.info(section);
        } catch (Exception e) {
            log.debug("info {} failed: {}", section, e.getMessage());
            return null;
        }
    }

    private Map<String, String> parseInfo(String info) {
        Map<String, String> redis = new LinkedHashMap<>();
        redis.put("cluster_enabled", "0");
        for (String raw : info.split("\r?\n")) {
            String item = raw.trim();
            if (item.isEmpty() || item.startsWith("#") || !item.contains(":")) {
                continue;
            }
            int idx = item.indexOf(':');
            String key = item.substring(0, idx).trim();
            String value = item.substring(idx + 1).trim();
            switch (key) {
                case "connected_clients":
                case "total_system_memory":
                case "total_system_memory_human":
                case "maxmemory":
                case "maxmemory_human":
                case "used_memory":
                case "used_memory_human":
                case "used_memory_rss":
                case "used_memory_rss_human":
                case "used_memory_peak":
                case "used_memory_peak_human":
                case "maxmemory_policy":
                case "rdb_last_bgsave_status":
                case "rdb_last_bgsave_time_sec":
                case "aof_enabled":
                case "aof_last_bgrewrite_status":
                case "aof_last_write_status":
                case "aof_delayed_fsync":
                case "rejected_connections":
                case "evicted_keys":
                case "latest_fork_usec":
                case "cluster_enabled":
                case "role":
                case "master_link_status":
                case "connected_slaves":
                    redis.put(key, value);
                    break;
                default:
                    break;
            }
        }
        return redis;
    }

    private void fillSentinelStatus(Jedis jedis, Map<String, String> redis) {
        String sentinelInfo = safeInfo(jedis, "sentinel");
        if (StringUtils.isBlank(sentinelInfo)) {
            return;
        }
        List<String> statusLines = new ArrayList<>();
        for (String line : sentinelInfo.split("\r?\n")) {
            if (line.contains("status=")) {
                statusLines.add(line.trim());
            }
        }
        if (!statusLines.isEmpty()) {
            redis.put("_sentinel_status_lines", String.join("\n", statusLines));
        }
    }

    private void fillClusterStatus(Jedis jedis, Map<String, String> redis) {
        try {
            String clusterInfo = jedis.clusterInfo();
            if (StringUtils.isNotBlank(clusterInfo)) {
                for (String raw : clusterInfo.split("\r?\n")) {
                    String item = raw.trim();
                    if (!item.contains(":")) {
                        continue;
                    }
                    int idx = item.indexOf(':');
                    String key = item.substring(0, idx).trim();
                    String value = item.substring(idx + 1).trim();
                    if ("cluster_state".equals(key)
                            || "cluster_slots_assigned".equals(key)
                            || "cluster_slots_ok".equals(key)) {
                        redis.put(key, value);
                    }
                }
            }
        } catch (Exception e) {
            log.debug("cluster info failed: {}", e.getMessage());
        }

        try {
            String clusterNodes = jedis.clusterNodes();
            List<String> failList = new ArrayList<>();
            if (StringUtils.isNotBlank(clusterNodes)) {
                for (String line : clusterNodes.split("\r?\n")) {
                    if (line.contains("fail")) {
                        String[] parts = line.trim().split("\\s+");
                        if (parts.length >= 2) {
                            failList.add(parts[1]);
                        }
                    }
                }
            }
            redis.put("_cluster_fail_list", String.join(",", failList));
        } catch (Exception e) {
            log.debug("cluster nodes failed: {}", e.getMessage());
            redis.put("_cluster_fail_list", "");
        }
    }

    private List<OnlineHealthCheckItemDto> analyzeStatus(Jedis jedis, Map<String, String> redis,
                                                         boolean isSentinel, boolean isCluster) {
        Map<String, String[]> checks = new LinkedHashMap<>();

        if (isSentinel && redis.containsKey("_sentinel_status_lines")) {
            analyzeSentinel(jedis, redis.get("_sentinel_status_lines"), checks);
        }

        // Sentinel 节点本身不做数据节点内存/复制类检查
        if (!isSentinel) {
            addMemoryAndRuntimeChecks(redis, checks);
            addReplicationChecks(redis, checks);
        }

        // 仅集群模式输出集群相关检查
        if (isCluster) {
            addClusterChecks(redis, checks);
        }

        List<OnlineHealthCheckItemDto> list = new ArrayList<>();
        // Error 在前，Info 在后
        for (Map.Entry<String, String[]> entry : checks.entrySet()) {
            if (ERROR.equals(entry.getValue()[0])) {
                list.add(toItem(entry.getKey(), entry.getValue()));
            }
        }
        for (Map.Entry<String, String[]> entry : checks.entrySet()) {
            if (INFO.equals(entry.getValue()[0])) {
                list.add(toItem(entry.getKey(), entry.getValue()));
            }
        }
        return list;
    }

    private void addMemoryAndRuntimeChecks(Map<String, String> redis, Map<String, String[]> checks) {
        long totalMem = parseLong(redis.get("total_system_memory"));
        String totalHuman = StringUtil.defaultIfBlank(redis.get("total_system_memory_human"), formatBytes(totalMem));
        long maxmemory = parseLong(redis.get("maxmemory"));
        String maxHuman = StringUtil.defaultIfBlank(redis.get("maxmemory_human"), formatBytes(maxmemory));

        if (redis.containsKey("maxmemory") && totalMem > 0) {
            long limit = (long) (totalMem * 0.7);
            checks.put("redis内存上限", new String[]{
                    maxmemory < limit ? INFO : ERROR,
                    "小于系统内存总量*70% (" + totalHuman + "×0.7=" + formatBytes(limit) + ")",
                    StringUtil.defaultIfBlank(redis.get("maxmemory_human"), formatBytes(maxmemory)),
                    "当前redis-server的最大内存使用量"
            });
        }

        if (redis.containsKey("used_memory")) {
            long used = parseLong(redis.get("used_memory"));
            long limit;
            String expect;
            if (maxmemory > 0) {
                limit = (long) (maxmemory * 0.8);
                expect = "小于redis内存上限*80% (" + maxHuman + "×0.8=" + formatBytes(limit) + ")";
            } else if (totalMem > 0) {
                limit = (long) (totalMem * 0.7);
                expect = "小于系统内存总量*70% (" + totalHuman + "×0.7=" + formatBytes(limit) + ")";
            } else {
                limit = 0;
                expect = "小于redis内存上限*80%(或小于系统内存总量*70%)";
            }
            checks.put("redis已使用内存", new String[]{
                    limit <= 0 || used < limit ? INFO : ERROR,
                    expect,
                    StringUtil.defaultIfBlank(redis.get("used_memory_human"), formatBytes(used)),
                    "当前redis-server的内存使用量,若未设置内存上限，则需要当前值小于系统总内存*70%"
            });
        }

        if (redis.containsKey("used_memory_rss") && totalMem > 0) {
            long rss = parseLong(redis.get("used_memory_rss"));
            long limit = (long) (totalMem * 0.8);
            checks.put("redis已使用物理内存", new String[]{
                    rss < limit ? INFO : ERROR,
                    "小于系统内存总量*80% (" + totalHuman + "×0.8=" + formatBytes(limit) + ")",
                    StringUtil.defaultIfBlank(redis.get("used_memory_rss_human"), formatBytes(rss)),
                    "从系统角度，显示 redis 进程占用的物理内存总量，(同top,ps)"
            });
        }

        if (redis.containsKey("rdb_last_bgsave_status")) {
            String status = redis.get("rdb_last_bgsave_status");
            checks.put("上一次RDB执行状态", new String[]{
                    "ok".equalsIgnoreCase(status) ? INFO : ERROR,
                    "ok为正常",
                    status,
                    "上次写入RDB持久化文件的执行状态"
            });
        }

        if (redis.containsKey("rdb_last_bgsave_time_sec")) {
            long sec = parseLong(redis.get("rdb_last_bgsave_time_sec"));
            String level = (sec < 0 || sec < 60) ? INFO : ERROR;
            checks.put("上一次RDB执行耗时", new String[]{
                    level,
                    "上次重写RDB持久化文件用时，小于60s认为是健康状态",
                    redis.get("rdb_last_bgsave_time_sec"),
                    "单次写盘时间过长可能会导致实例异常时，更长时间段数据的丢失，且会对Redis自身性能有一定影响"
            });
        }

        boolean aofEnabled = "1".equals(redis.get("aof_enabled"));
        if (aofEnabled && redis.containsKey("aof_last_bgrewrite_status")) {
            String status = redis.get("aof_last_bgrewrite_status");
            checks.put("上次AOF重写结果", new String[]{
                    "ok".equalsIgnoreCase(status) ? INFO : ERROR,
                    "ok",
                    status,
                    "上次重写AOF文件的执行结果"
            });
        }
        if (aofEnabled && redis.containsKey("aof_last_write_status")) {
            String status = redis.get("aof_last_write_status");
            checks.put("上次AOF写入结果", new String[]{
                    "ok".equalsIgnoreCase(status) ? INFO : ERROR,
                    "ok",
                    status,
                    "上次写入AOF文件的执行结果"
            });
        }
        if (aofEnabled && redis.containsKey("aof_delayed_fsync")) {
            long delayed = parseLong(redis.get("aof_delayed_fsync"));
            checks.put("aof单次刷盘延迟次数", new String[]{
                    delayed == 0 ? INFO : ERROR,
                    "0，即无AOF-Sync刷盘延迟",
                    redis.get("aof_delayed_fsync"),
                    "redis实例存在AOF-Sync刷盘延迟情况，有可能会阻塞主进程，影响redis运行速度，建议排查磁盘使用情况。"
            });
        }

        if (redis.containsKey("rejected_connections")) {
            long rejected = parseLong(redis.get("rejected_connections"));
            checks.put("被拒绝的连接数", new String[]{
                    rejected <= 50 ? INFO : ERROR,
                    "小于等于50",
                    redis.get("rejected_connections"),
                    "由于连接数超限而拒绝的连接数"
            });
        }

        if (redis.containsKey("evicted_keys")) {
            long evicted = parseLong(redis.get("evicted_keys"));
            checks.put("淘汰的key数量", new String[]{
                    evicted <= 100 ? INFO : ERROR,
                    "小于等于100个",
                    redis.get("evicted_keys"),
                    "redis 因内存不足淘汰的key数量"
            });
        }

        if (redis.containsKey("connected_clients")) {
            long clients = parseLong(redis.get("connected_clients"));
            checks.put("连接的客户端数量", new String[]{
                    clients < 8000 ? INFO : ERROR,
                    "小于 maxclients(默认值10000) * 80% ",
                    redis.get("connected_clients"),
                    "当前连接redis的客户端数量"
            });
        }

        if (redis.containsKey("latest_fork_usec")) {
            long forkUsec = parseLong(redis.get("latest_fork_usec"));
            checks.put("上次fork子进程耗时", new String[]{
                    forkUsec < 200000 ? INFO : ERROR,
                    "小于0.2s(200000微秒)",
                    redis.get("latest_fork_usec"),
                    "redis上次fork子进程使用(阻塞)的时间(微秒)"
            });
        }
    }

    private void addReplicationChecks(Map<String, String> redis, Map<String, String[]> checks) {
        if (redis.containsKey("role")) {
            String role = redis.get("role");
            long slaves = parseLong(redis.get("connected_slaves"));
            String level = ("master".equalsIgnoreCase(role) && slaves == 0) ? ERROR : INFO;
            checks.put("连接的从节点数", new String[]{
                    level,
                    "对于master节点需要大于0，当前节点为: " + role,
                    redis.containsKey("connected_slaves") ? redis.get("connected_slaves") : "0",
                    "当前master节点连接的slave节点数，为实现Redis实例的高可用，master节点需要至少配备一个slave节点"
            });
        }

        // 仅从节点显示主从连接状态
        if ("slave".equalsIgnoreCase(redis.get("role")) && redis.containsKey("master_link_status")) {
            String linkStatus = redis.get("master_link_status");
            checks.put("与主节点连接状态", new String[]{
                    "up".equalsIgnoreCase(linkStatus) ? INFO : ERROR,
                    "连接状态为: up",
                    linkStatus,
                    "当前slave节点所连接的master节点状态，正常应为up状态"
            });
        }
    }

    private void addClusterChecks(Map<String, String> redis, Map<String, String[]> checks) {
        if (redis.containsKey("cluster_state")) {
            String state = redis.get("cluster_state");
            checks.put("集群状态", new String[]{
                    "ok".equalsIgnoreCase(state) ? INFO : ERROR,
                    "ok",
                    state,
                    "redis集群状态(是否可用)"
            });
        }
        if (redis.containsKey("cluster_slots_assigned")) {
            long assigned = parseLong(redis.get("cluster_slots_assigned"));
            checks.put("当前已分配槽位", new String[]{
                    assigned == 16384 ? INFO : ERROR,
                    "16384",
                    redis.get("cluster_slots_assigned"),
                    "redis集群当前已分配的槽位数量"
            });
        }
        if (redis.containsKey("cluster_slots_ok")) {
            long ok = parseLong(redis.get("cluster_slots_ok"));
            checks.put("当前可使用槽位", new String[]{
                    ok == 16384 ? INFO : ERROR,
                    "16384",
                    redis.get("cluster_slots_ok"),
                    "redis集群当前可正常使用的槽位数量"
            });
        }
        if (redis.containsKey("_cluster_fail_list")) {
            String failList = redis.get("_cluster_fail_list");
            boolean empty = StringUtils.isBlank(failList);
            checks.put("当前集群中失败节点检查", new String[]{
                    empty ? INFO : ERROR,
                    "当前集群中无失败节点",
                    empty ? "-" : failList,
                    "当前值记录了目前集群中失败的节点地址"
            });
        }
    }

    private void analyzeSentinel(Jedis jedis, String statusLines, Map<String, String[]> checks) {
        for (String line : statusLines.split("\n")) {
            if (StringUtils.isBlank(line) || !line.contains("status=")) {
                continue;
            }
            String masterName = extractKv(line, "name");
            String masterStatus = extractKv(line, "status");
            if (StringUtils.isBlank(masterName)) {
                continue;
            }
            long sentinelFailNum = countDisconnected(jedis, "SENTINELS", masterName);
            long slaveFailNum = countDisconnected(jedis, "SLAVES", masterName);
            boolean withFaulty = sentinelFailNum != 0 || slaveFailNum != 0;
            String level = (!"ok".equalsIgnoreCase(masterStatus) || withFaulty) ? ERROR : INFO;
            checks.put("哨兵状态检查(" + masterName + ")", new String[]{
                    level,
                    "Master节点状态为ok,且无宕机的哨兵/从节点",
                    line.trim(),
                    "宕机哨兵节点个数：" + sentinelFailNum + " 宕机从节点个数：" + slaveFailNum
            });
        }
    }

    private long countDisconnected(Jedis jedis, String sub, String masterName) {
        try {
            List<Map<String, String>> list = "SENTINELS".equalsIgnoreCase(sub)
                    ? jedis.sentinelSentinels(masterName)
                    : jedis.sentinelSlaves(masterName);
            if (list == null) {
                return 0;
            }
            long count = 0;
            for (Map<String, String> item : list) {
                String flags = item.get("flags");
                if (flags != null && flags.contains("disconnected")) {
                    count++;
                }
            }
            return count;
        } catch (Exception e) {
            log.debug("sentinel {} {} failed: {}", sub, masterName, e.getMessage());
            return 0;
        }
    }

    private String extractKv(String line, String key) {
        String body = line;
        int colon = line.indexOf(':');
        if (colon >= 0) {
            body = line.substring(colon + 1);
        }
        for (String part : body.split(",")) {
            String[] kv = part.trim().split("=", 2);
            if (kv.length == 2 && key.equals(kv[0].trim())) {
                return kv[1].trim();
            }
        }
        return "";
    }

    private OnlineHealthCheckItemDto toItem(String name, String[] arr) {
        OnlineHealthCheckItemDto item = new OnlineHealthCheckItemDto();
        item.setName(name);
        item.setLevel(arr[0]);
        item.setExpect(arr[1]);
        item.setCurrent(arr[2]);
        item.setDescription(arr[3]);
        return item;
    }

    private String formatTargetText(OnlineHealthTargetDto dto) {
        StringBuilder sb = new StringBuilder();
        sb.append(dto.getHost()).append("  [").append(dto.getModeLabel()).append("]");
        if (dto.isManaged()) {
            sb.append("  [纳管: ").append(StringUtil.defaultIfBlank(dto.getAppName(), String.valueOf(dto.getAppId()))).append("]");
        }
        sb.append('\n');
        if (StringUtils.isNotBlank(dto.getConnectError())) {
            sb.append(errorPrefixBare()).append(" ").append(dto.getConnectError()).append('\n');
            return sb.toString();
        }
        if (dto.isAllOk()) {
            sb.append(infoPrefix()).append(" every thing is ok!\n");
        }
        for (OnlineHealthCheckItemDto item : dto.getChecks()) {
            String prefix = ERROR.equals(item.getLevel()) ? errorPrefixBare() : infoPrefix();
            sb.append(prefix)
                    .append(" 检查项: ").append(item.getName())
                    .append("\t| 预期值: ").append(item.getExpect())
                    .append("\t| 当前值: ").append(item.getCurrent())
                    .append("\t| 说明: ").append(item.getDescription())
                    .append('\n');
        }
        return sb.toString();
    }

    private String now() {
        return LocalDateTime.now().format(TIME_FMT);
    }

    private String infoPrefix() {
        return "[Info] [" + now() + "]";
    }

    private String errorPrefixBare() {
        return "[Error] [" + now() + "]";
    }

    private long parseLong(String value) {
        if (StringUtils.isBlank(value)) {
            return 0L;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /** 将字节格式化为可读容量，贴近 Redis human 风格 */
    private String formatBytes(long bytes) {
        if (bytes <= 0) {
            return "0B";
        }
        final String[] units = {"B", "K", "M", "G", "T"};
        double value = bytes;
        int idx = 0;
        while (value >= 1024 && idx < units.length - 1) {
            value /= 1024;
            idx++;
        }
        if (idx == 0) {
            return ((long) value) + units[idx];
        }
        return String.format(java.util.Locale.ROOT, "%.2f%s", value, units[idx]);
    }

    private void closeQuietly(Jedis jedis) {
        if (jedis != null) {
            try {
                jedis.close();
            } catch (Exception ignored) {
                // ignore
            }
        }
    }

    private List<ServerTarget> parseServers(String input) {
        List<ServerTarget> list = new ArrayList<>();
        if (StringUtil.isBlank(input)) {
            return list;
        }
        for (String line : input.split("\\r?\\n")) {
            line = line.trim();
            if (line.isEmpty()) {
                continue;
            }
            ServerTarget target = parseServerLine(line);
            if (target != null) {
                list.add(target);
            }
        }
        return list;
    }

    private ServerTarget parseServerLine(String line) {
        if (line.contains(":")) {
            String[] parts = line.split(":", 3);
            if (parts.length < 2) {
                return null;
            }
            try {
                int port = Integer.parseInt(parts[1].trim());
                String password = parts.length >= 3 ? parts[2] : null;
                if (password != null && password.isEmpty()) {
                    password = null;
                }
                return new ServerTarget(parts[0].trim(), port, password);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        String[] parts = line.split("\\s+");
        if (parts.length >= 2) {
            try {
                int port = Integer.parseInt(parts[1].trim());
                String password = parts.length >= 3 ? parts[2] : null;
                return new ServerTarget(parts[0].trim(), port, password);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static class ServerTarget {
        private final String ip;
        private final int port;
        private final String password;

        ServerTarget(String ip, int port, String password) {
            this.ip = ip;
            this.port = port;
            this.password = password;
        }
    }

    private static class ManagedAuth {
        private boolean found;
        private long appId;
        private String appName;
        private String password;
        private Integer instanceType;
    }
}
