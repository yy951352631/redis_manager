package com.shcj.cache.benchmark;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 压测可选命令目录。
 *
 * <p>比 redis-benchmark 的固定命令集多出 HGETALL / ZRANGE / LRANGE / TTL 这类：
 * 它们在真实业务里占比很高，而且恰恰是容易成为瓶颈的那一类——HGETALL 在大 hash 上
 * 是 O(N)，用只含 GET/SET 的命令集压出来的数字看不出这个问题。
 *
 * <p>每个命令自带参数组装规则，使用者只需要勾选，不必关心 key 之外还要传什么。
 */
public enum BenchmarkCommand {

    // ---------------------------------------------------------------- String
    GET("GET", Group.STRING, Kind.READ),
    MGET("MGET", Group.STRING, Kind.READ),
    STRLEN("STRLEN", Group.STRING, Kind.READ),
    EXISTS("EXISTS", Group.STRING, Kind.READ),
    TTL("TTL", Group.STRING, Kind.READ),
    SET("SET", Group.STRING, Kind.WRITE),
    SETEX("SETEX", Group.STRING, Kind.WRITE),
    INCR("INCR", Group.STRING, Kind.WRITE),
    APPEND("APPEND", Group.STRING, Kind.WRITE),
    DEL("DEL", Group.STRING, Kind.WRITE),

    // ---------------------------------------------------------------- Hash
    HGET("HGET", Group.HASH, Kind.READ),
    HMGET("HMGET", Group.HASH, Kind.READ),
    HGETALL("HGETALL", Group.HASH, Kind.READ),
    HLEN("HLEN", Group.HASH, Kind.READ),
    HSET("HSET", Group.HASH, Kind.WRITE),
    HDEL("HDEL", Group.HASH, Kind.WRITE),
    HINCRBY("HINCRBY", Group.HASH, Kind.WRITE),

    // ---------------------------------------------------------------- List
    LRANGE("LRANGE", Group.LIST, Kind.READ),
    LLEN("LLEN", Group.LIST, Kind.READ),
    LPUSH("LPUSH", Group.LIST, Kind.WRITE),
    RPUSH("RPUSH", Group.LIST, Kind.WRITE),
    LPOP("LPOP", Group.LIST, Kind.WRITE),

    // ---------------------------------------------------------------- Set
    SISMEMBER("SISMEMBER", Group.SET, Kind.READ),
    SCARD("SCARD", Group.SET, Kind.READ),
    SADD("SADD", Group.SET, Kind.WRITE),
    SREM("SREM", Group.SET, Kind.WRITE),

    // ---------------------------------------------------------------- ZSet
    ZSCORE("ZSCORE", Group.ZSET, Kind.READ),
    ZRANGE("ZRANGE", Group.ZSET, Kind.READ),
    ZCARD("ZCARD", Group.ZSET, Kind.READ),
    ZADD("ZADD", Group.ZSET, Kind.WRITE),
    ZINCRBY("ZINCRBY", Group.ZSET, Kind.WRITE);

    public enum Group {
        STRING("String"), HASH("Hash"), LIST("List"), SET("Set"), ZSET("ZSet");

        private final String label;

        Group(String label) {
            this.label = label;
        }

        public String getLabel() {
            return label;
        }
    }

    public enum Kind {
        READ, WRITE
    }

    private final String name;
    private final Group group;
    private final Kind kind;

    BenchmarkCommand(String name, Group group, Kind kind) {
        this.name = name;
        this.group = group;
        this.kind = kind;
    }

    public String getName() {
        return name;
    }

    public Group getGroup() {
        return group;
    }

    public Kind getKind() {
        return kind;
    }

    public boolean isWrite() {
        return kind == Kind.WRITE;
    }

    public static BenchmarkCommand of(String name) {
        if (name == null) {
            return null;
        }
        for (BenchmarkCommand command : values()) {
            if (command.name.equalsIgnoreCase(name.trim())) {
                return command;
            }
        }
        return null;
    }

    /** 按数据类型分组的目录，供界面折叠展示 */
    public static Map<String, List<Map<String, String>>> catalog() {
        Map<String, List<Map<String, String>>> grouped = new LinkedHashMap<>();
        for (Group group : Group.values()) {
            grouped.put(group.getLabel(), new ArrayList<Map<String, String>>());
        }
        for (BenchmarkCommand command : values()) {
            Map<String, String> item = new LinkedHashMap<>();
            item.put("name", command.name);
            item.put("kind", command.kind.name());
            grouped.get(command.group.getLabel()).add(item);
        }
        return grouped;
    }
}
