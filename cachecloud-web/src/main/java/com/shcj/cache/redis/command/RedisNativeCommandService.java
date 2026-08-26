package com.shcj.cache.redis.command;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.ScanParams;
import redis.clients.jedis.ScanResult;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * 用 Java 客户端实现 redis-cli 的客户端侧能力，替代对 redis-cli 二进制的依赖。
 *
 * <p>redis-cli 的 {@code --bigkeys / --memkeys / --hotkeys / --scan / --stat / --latency}
 * <b>并不是 Redis 命令</b>——服务端根本不认识它们，是 redis-cli 在客户端用 SCAN / TYPE /
 * MEMORY USAGE / OBJECT FREQ / INFO / PING 组合出来的。因此这些"命令"无法通过
 * {@code sendCommand} 转发给服务端，只能在这里按同样的语义重新实现。
 *
 * <p>另一项 redis-cli 独有的能力是 {@code -c} 的 MOVED/ASK 重定向跟随，见
 * {@link #executeFollowingRedirect}。
 */
@Service
public class RedisNativeCommandService {

    private static final Logger LOGGER = LoggerFactory.getLogger(RedisNativeCommandService.class);

    /** 单次 SCAN 的游标批量，与 redis-cli 默认行为保持一致的量级 */
    private static final int SCAN_BATCH = 1000;

    /** 扫描类命令的最大采样键数，避免在大实例上无限扫下去阻塞工具页 */
    private static final int MAX_SCAN_KEYS = 200000;

    /** 结果里展示的 Top N */
    private static final int TOP_N = 20;

    /** --latency 的采样次数 */
    private static final int LATENCY_SAMPLES = 100;

    /** --stat 的采样轮次与间隔 */
    private static final int STAT_ROUNDS = 5;
    private static final long STAT_INTERVAL_MS = 1000L;

    /** MOVED/ASK 最多跟随的跳数，防止拓扑异常时无限重定向 */
    private static final int MAX_REDIRECTS = 5;

    public boolean isPseudoCommand(String command) {
        return resolvePseudo(command) != null;
    }

    private String resolvePseudo(String command) {
        if (StringUtils.isBlank(command)) {
            return null;
        }
        String first = StringUtils.trim(command).split("\\s+")[0].toLowerCase(Locale.ROOT);
        switch (first) {
            case "--bigkeys":
            case "--memkeys":
            case "--hotkeys":
            case "--scan":
            case "--stat":
            case "--latency":
                return first;
            default:
                return null;
        }
    }

    /**
     * 执行 redis-cli 客户端侧伪命令。
     *
     * @param jedis   已连接的客户端，由调用方负责关闭
     * @param command 形如 {@code --bigkeys} 或 {@code --scan user:*}
     */
    public String executePseudo(Jedis jedis, String command) {
        String[] parts = StringUtils.trim(command).split("\\s+");
        String pseudo = resolvePseudo(command);
        String arg = parts.length > 1 ? parts[1] : null;
        switch (pseudo) {
            case "--bigkeys":
                return bigKeys(jedis);
            case "--memkeys":
                return memKeys(jedis);
            case "--hotkeys":
                return hotKeys(jedis);
            case "--scan":
                return scan(jedis, arg);
            case "--stat":
                return stat(jedis);
            case "--latency":
                return latency(jedis);
            default:
                return "不支持的命令: " + command;
        }
    }

    /**
     * 按 key 的元素个数找出各类型中最大的键，等价于 redis-cli --bigkeys。
     *
     * <p>与 redis-cli 一致：衡量的是<b>元素个数</b>而非占用内存（string 用 STRLEN）。
     * 需要看真实内存占用请用 --memkeys。
     */
    private String bigKeys(Jedis jedis) {
        Map<String, String> biggestKey = new LinkedHashMap<>();
        Map<String, Long> biggestSize = new LinkedHashMap<>();
        Map<String, Long> typeCount = new LinkedHashMap<>();
        long scanned = scanKeys(jedis, null, (client, key) -> {
            String type = client.type(key);
            typeCount.merge(type, 1L, Long::sum);
            Long size = sizeOf(client, key, type);
            if (size == null) {
                return null;
            }
            Long current = biggestSize.get(type);
            if (current == null || size > current) {
                biggestSize.put(type, size);
                biggestKey.put(type, key);
            }
            return null;
        });

        StringBuilder sb = new StringBuilder();
        sb.append("# Scanning the entire keyspace to find biggest keys\n\n");
        sb.append(String.format("已采样 %d 个 key%n%n", scanned));
        sb.append("-------- 各类型最大 key --------\n");
        if (biggestKey.isEmpty()) {
            sb.append("(未扫描到任何 key)\n");
        } else {
            for (Map.Entry<String, String> entry : biggestKey.entrySet()) {
                sb.append(String.format("%-10s %-40s %d%n", entry.getKey(), entry.getValue(),
                        biggestSize.get(entry.getKey())));
            }
        }
        sb.append("\n-------- 各类型 key 数量 --------\n");
        for (Map.Entry<String, Long> entry : typeCount.entrySet()) {
            sb.append(String.format("%-10s %d%n", entry.getKey(), entry.getValue()));
        }
        return sb.toString();
    }

    /**
     * 按 MEMORY USAGE 找出占用内存最大的键，等价于 redis-cli --memkeys。
     */
    private String memKeys(Jedis jedis) {
        List<Map.Entry<String, Long>> top = new ArrayList<>();
        long scanned = scanKeys(jedis, null, (client, key) -> {
            Long bytes;
            try {
                bytes = client.memoryUsage(key);
            } catch (Exception e) {
                // MEMORY USAGE 需要 Redis 4.0+，低版本直接跳过
                return null;
            }
            if (bytes != null) {
                collectTop(top, key, bytes);
            }
            return null;
        });
        return renderTop("按内存占用排序（MEMORY USAGE，单位字节）", scanned, top);
    }

    /**
     * 按 OBJECT FREQ 找出访问最频繁的键，等价于 redis-cli --hotkeys。
     *
     * <p>依赖 {@code maxmemory-policy} 设为 LFU 系列，否则 Redis 不维护访问频率。
     */
    private String hotKeys(Jedis jedis) {
        String policy = configValue(jedis, "maxmemory-policy");
        if (policy != null && !policy.toLowerCase(Locale.ROOT).contains("lfu")) {
            return "--hotkeys 需要 maxmemory-policy 为 allkeys-lfu 或 volatile-lfu，当前为 " + policy
                    + "\n（非 LFU 策略下 Redis 不维护访问频率，无法统计热键）";
        }
        List<Map.Entry<String, Long>> top = new ArrayList<>();
        long scanned = scanKeys(jedis, null, (client, key) -> {
            try {
                Long freq = client.objectFreq(key);
                if (freq != null) {
                    collectTop(top, key, freq);
                }
            } catch (Exception e) {
                return null;
            }
            return null;
        });
        return renderTop("按访问频率排序（OBJECT FREQ）", scanned, top);
    }

    /**
     * 等价于 redis-cli --scan [pattern]，用 SCAN 渐进遍历，不会像 KEYS 那样阻塞主线程。
     */
    private String scan(Jedis jedis, String pattern) {
        StringBuilder sb = new StringBuilder();
        long scanned = scanKeys(jedis, pattern, (client, key) -> {
            sb.append(key).append('\n');
            return null;
        });
        sb.append(String.format("%n共 %d 个 key%s%n", scanned,
                scanned >= MAX_SCAN_KEYS ? "（已达采样上限 " + MAX_SCAN_KEYS + "，结果被截断）" : ""));
        return sb.toString();
    }

    /**
     * 等价于 redis-cli --stat：周期采样 INFO，输出关键指标的变化。
     */
    private String stat(Jedis jedis) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%-10s %-12s %-10s %-14s %-12s %-10s%n",
                "keys", "mem", "clients", "requests", "connections", "hitrate"));
        long lastRequests = -1L;
        for (int i = 0; i < STAT_ROUNDS; i++) {
            Map<String, String> info = parseInfo(jedis.info("all"));
            long keys = totalKeys(info);
            String mem = StringUtils.defaultString(info.get("used_memory_human"), "-");
            String clients = StringUtils.defaultString(info.get("connected_clients"), "-");
            long requests = parseLong(info.get("total_commands_processed"));
            String connections = StringUtils.defaultString(info.get("total_connections_received"), "-");
            long hits = parseLong(info.get("keyspace_hits"));
            long misses = parseLong(info.get("keyspace_misses"));
            String hitRate = (hits + misses) > 0
                    ? String.format("%.2f%%", hits * 100.0D / (hits + misses)) : "-";
            String delta = lastRequests < 0 ? String.valueOf(requests)
                    : requests + " (+" + (requests - lastRequests) + ")";
            lastRequests = requests;
            sb.append(String.format("%-10d %-12s %-10s %-14s %-12s %-10s%n",
                    keys, mem, clients, delta, connections, hitRate));
            if (i < STAT_ROUNDS - 1) {
                sleep(STAT_INTERVAL_MS);
            }
        }
        return sb.toString();
    }

    /**
     * 等价于 redis-cli --latency：连续 PING 并统计往返耗时。
     */
    private String latency(Jedis jedis) {
        long min = Long.MAX_VALUE;
        long max = 0L;
        long total = 0L;
        for (int i = 0; i < LATENCY_SAMPLES; i++) {
            long start = System.nanoTime();
            jedis.ping();
            long costUsec = (System.nanoTime() - start) / 1000L;
            min = Math.min(min, costUsec);
            max = Math.max(max, costUsec);
            total += costUsec;
        }
        return String.format("min %.3f ms, max %.3f ms, avg %.3f ms (%d samples)%n",
                min / 1000.0D, max / 1000.0D, total / (double) LATENCY_SAMPLES / 1000.0D, LATENCY_SAMPLES);
    }

    /**
     * 跟随 MOVED / ASK 重定向执行命令，等价于 redis-cli 的 {@code -c}。
     *
     * <p>Jedis 的 {@code sendCommand} 直连单节点，命中其他分片的 key 只会把 MOVED 当异常抛出。
     * 这里解析出目标节点后换连接重试。
     *
     * @param jedisFactory 按 host:port 建立连接的工厂，返回的连接由本方法负责关闭
     * @param action       在给定连接上执行命令
     */
    public <T> T executeFollowingRedirect(Jedis jedis, BiFunction<String, Integer, Jedis> jedisFactory,
                                          java.util.function.Function<Jedis, T> action) {
        Jedis current = jedis;
        Jedis opened = null;
        try {
            for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
                try {
                    return action.apply(current);
                } catch (Exception e) {
                    HostAndPortRef target = parseRedirect(e.getMessage());
                    if (target == null || hop == MAX_REDIRECTS) {
                        throw e;
                    }
                    LOGGER.debug("following redirect to {}:{}", target.host, target.port);
                    if (opened != null) {
                        opened.close();
                    }
                    opened = jedisFactory.apply(target.host, target.port);
                    if (opened == null) {
                        throw e;
                    }
                    if (target.asking) {
                        // ASK 重定向要求先发 ASKING 再重放命令，且只对本次生效
                        opened.asking();
                    }
                    current = opened;
                }
            }
            throw new IllegalStateException("重定向次数超过 " + MAX_REDIRECTS + " 次");
        } finally {
            if (opened != null) {
                opened.close();
            }
        }
    }

    /** 解析 {@code MOVED 3999 127.0.0.1:6381} / {@code ASK 3999 127.0.0.1:6381} */
    private HostAndPortRef parseRedirect(String message) {
        if (StringUtils.isBlank(message)) {
            return null;
        }
        String trimmed = message.trim();
        boolean moved = trimmed.startsWith("MOVED ");
        boolean ask = trimmed.startsWith("ASK ");
        if (!moved && !ask) {
            return null;
        }
        String[] parts = trimmed.split("\\s+");
        if (parts.length < 3) {
            return null;
        }
        int split = parts[2].lastIndexOf(':');
        if (split <= 0) {
            return null;
        }
        try {
            return new HostAndPortRef(parts[2].substring(0, split),
                    Integer.parseInt(parts[2].substring(split + 1)), ask);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- 内部工具

    /**
     * 用 SCAN 渐进遍历键空间，对每个 key 调用 visitor。
     *
     * @return 实际采样到的 key 数
     */
    private long scanKeys(Jedis jedis, String pattern, BiFunction<Jedis, String, Void> visitor) {
        ScanParams params = new ScanParams().count(SCAN_BATCH);
        if (StringUtils.isNotBlank(pattern)) {
            params.match(pattern);
        }
        String cursor = ScanParams.SCAN_POINTER_START;
        long scanned = 0L;
        do {
            ScanResult<String> result = jedis.scan(cursor, params);
            for (String key : result.getResult()) {
                visitor.apply(jedis, key);
                if (++scanned >= MAX_SCAN_KEYS) {
                    return scanned;
                }
            }
            cursor = result.getCursor();
        } while (!ScanParams.SCAN_POINTER_START.equals(cursor));
        return scanned;
    }

    /** 按类型取"大小"，语义与 redis-cli --bigkeys 一致 */
    private Long sizeOf(Jedis jedis, String key, String type) {
        try {
            switch (type) {
                case "string":
                    return jedis.strlen(key);
                case "list":
                    return jedis.llen(key);
                case "set":
                    return jedis.scard(key);
                case "zset":
                    return jedis.zcard(key);
                case "hash":
                    return jedis.hlen(key);
                case "stream":
                    return jedis.xlen(key);
                default:
                    return null;
            }
        } catch (Exception e) {
            // key 可能在扫描过程中被删除或改型，跳过即可
            return null;
        }
    }

    private void collectTop(List<Map.Entry<String, Long>> top, String key, long value) {
        top.add(new java.util.AbstractMap.SimpleEntry<>(key, value));
        if (top.size() > TOP_N * 4) {
            trimTop(top);
        }
    }

    private void trimTop(List<Map.Entry<String, Long>> top) {
        top.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        while (top.size() > TOP_N) {
            top.remove(top.size() - 1);
        }
    }

    private String renderTop(String title, long scanned, List<Map.Entry<String, Long>> top) {
        trimTop(top);
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("已采样 %d 个 key，%s，取前 %d%n%n", scanned, title, TOP_N));
        if (top.isEmpty()) {
            sb.append("(无结果)\n");
            return sb.toString();
        }
        for (Map.Entry<String, Long> entry : top) {
            sb.append(String.format("%-50s %d%n", entry.getKey(), entry.getValue()));
        }
        return sb.toString();
    }

    private String configValue(Jedis jedis, String key) {
        try {
            List<String> config = jedis.configGet(key);
            return config != null && config.size() >= 2 ? config.get(1) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private Map<String, String> parseInfo(String info) {
        Map<String, String> map = new LinkedHashMap<>();
        if (StringUtils.isBlank(info)) {
            return map;
        }
        for (String line : info.split("\\r?\\n")) {
            if (StringUtils.isBlank(line) || line.startsWith("#")) {
                continue;
            }
            int split = line.indexOf(':');
            if (split > 0) {
                map.put(line.substring(0, split).trim(), line.substring(split + 1).trim());
            }
        }
        return map;
    }

    /** Keyspace 段形如 db0:keys=1,expires=0,avg_ttl=0，把各 db 的 keys 累加 */
    private long totalKeys(Map<String, String> info) {
        long total = 0L;
        for (Map.Entry<String, String> entry : info.entrySet()) {
            if (!entry.getKey().startsWith("db")) {
                continue;
            }
            for (String part : StringUtils.defaultString(entry.getValue()).split(",")) {
                String[] kv = part.split("=");
                if (kv.length == 2 && "keys".equals(kv[0].trim())) {
                    total += parseLong(kv[1]);
                }
            }
        }
        return total;
    }

    private long parseLong(String value) {
        try {
            return StringUtils.isBlank(value) ? 0L : Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class HostAndPortRef {
        private final String host;
        private final int port;
        private final boolean asking;

        HostAndPortRef(String host, int port, boolean asking) {
            this.host = host;
            this.port = port;
            this.asking = asking;
        }
    }

    /** 供帮助文案使用 */
    public static List<String> supportedPseudoCommands() {
        return Arrays.asList("--bigkeys", "--memkeys", "--hotkeys", "--scan [pattern]", "--stat", "--latency");
    }
}
