package com.shcj.cache.util;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Redis 命令的读/写归类，用于把 INFO commandstats 汇总成「读命令 / 写命令」两条曲线。
 *
 * <p>判定口径是「是否改动键空间」，与 Redis 自身 COMMAND 的 write 标志一致。
 * 没有走 COMMAND INFO 实时取标志：那要对每个实例多发一次命令，而这份归类只用于
 * 画图，静态表足够，也能对历史数据回溯生效。
 *
 * <p>兜底方向刻意选成「未知命令算读」：新出现的写命令会让写曲线偏低，比把读命令
 * 误计成写、让人以为集群写压力很大要好判断。新增写命令时补进 {@link #WRITE_COMMANDS}。
 */
public final class RedisCommandKindUtil {

    /** 改动键空间的命令 */
    private static final Set<String> WRITE_COMMANDS = Collections.unmodifiableSet(new HashSet<String>(Arrays.asList(
            // 字符串
            "set", "setex", "psetex", "setnx", "mset", "msetnx", "getset", "getdel", "getex",
            "append", "setrange", "setbit", "incr", "incrby", "incrbyfloat", "decr", "decrby",
            // 通用
            "del", "unlink", "expire", "pexpire", "expireat", "pexpireat", "persist",
            "rename", "renamenx", "move", "copy", "restore", "migrate", "sort",
            "flushall", "flushdb", "swapdb",
            // 列表
            "lpush", "rpush", "lpushx", "rpushx", "lpop", "rpop", "lset", "linsert",
            "lrem", "ltrim", "lmove", "blmove", "rpoplpush", "brpoplpush", "blpop", "brpop", "lmpop", "blmpop",
            // 集合
            "sadd", "srem", "spop", "smove", "sinterstore", "sunionstore", "sdiffstore",
            // 有序集合
            "zadd", "zincrby", "zrem", "zremrangebyscore", "zremrangebyrank", "zremrangebylex",
            "zpopmin", "zpopmax", "bzpopmin", "bzpopmax", "zmpop", "bzmpop",
            "zdiffstore", "zunionstore", "zinterstore", "zrangestore",
            // 哈希
            "hset", "hsetnx", "hmset", "hincrby", "hincrbyfloat", "hdel",
            // 位图 / HLL / 地理
            "bitop", "bitfield", "pfadd", "pfmerge", "geoadd", "georadius", "georadiusbymember",
            "geosearchstore",
            // 流
            "xadd", "xtrim", "xdel", "xgroup", "xack", "xclaim", "xautoclaim",
            // 脚本与事务里的写由具体命令各自计入，此处不重复计算
            "xsetid")));

    /**
     * 既不读也不写键空间的命令：连接管理、复制、集群、监控、发布订阅。
     *
     * <p>把它们排除在外，两条曲线才对应真实业务流量——否则平台自己每分钟的
     * info / ping / slowlog 采集就会把读命令曲线抬起一个固定底噪。
     */
    private static final Set<String> NON_KEYSPACE_COMMANDS = Collections.unmodifiableSet(new HashSet<String>(Arrays.asList(
            "ping", "echo", "auth", "hello", "select", "quit", "reset", "client",
            "info", "config", "command", "slowlog", "latency", "memory", "monitor", "debug",
            "shutdown", "lastsave", "bgsave", "bgrewriteaof", "save", "dbsize",
            "replconf", "psync", "sync", "replicaof", "slaveof", "wait", "failover",
            "cluster", "sentinel", "acl", "module", "script", "function",
            "subscribe", "unsubscribe", "psubscribe", "punsubscribe", "publish",
            "ssubscribe", "sunsubscribe", "spublish", "pubsub",
            "multi", "exec", "discard", "watch", "unwatch")));

    private RedisCommandKindUtil() {
    }

    public static boolean isWrite(String command) {
        return command != null && WRITE_COMMANDS.contains(command.toLowerCase());
    }

    /** 读命令：既不是写命令，也不是连接/复制/集群这类不碰键空间的命令 */
    public static boolean isRead(String command) {
        if (command == null) {
            return false;
        }
        String lower = command.toLowerCase();
        return !WRITE_COMMANDS.contains(lower) && !NON_KEYSPACE_COMMANDS.contains(lower);
    }
}
