package com.shcj.cache.redis.enums;

import java.util.Optional;
import java.util.stream.Stream;

/**
 * Description
 *
 * @author zoushunqing 2023/3/1 11:16
 * @since Dev_1.0.1
 */
public enum RedisConfigPoolEnum {

    //todo zoushunqing
    INCLUDE("include","",""),
    LOADMODULE("loadmodule","",""),
    BIND("bind","",""),
    PROTECTED_MODE("protected-mode","",""),
    PORT("port","",""),
    TCP_BACKLOG("tcp-backlog","",""),
    UNIXSOCKET("unixsocket","",""),
    UNIXSOCKETPERM("unixsocketperm","",""),
    TIMEOUT("timeout","",""),
    TCP_KEEPALIVE("tcp-keepalive","",""),
    TLS_PORT("tls-port","",""),
    TLS_CERT_FILE("tls-cert-file","",""),
    TLS_KEY_FILE("tls-key-file","",""),
    TLS_KEY_FILE_PASS("tls-key-file-pass","",""),
    TLS_CLIENT_CERT_FILE("tls-client-cert-file","",""),
    TLS_CLIENT_KEY_FILE("tls-client-key-file","",""),
    TLS_CLIENT_KEY_FILE_PASS("tls-client-key-file-pass","",""),
    TLS_DH_PARAMS_FILE("tls-dh-params-file","",""),
    TLS_CA_CERT_FILE("tls-ca-cert-file","",""),
    TLS_CA_CERT_DIR("tls-ca-cert-dir","",""),
    TLS_AUTH_CLIENTS("tls-auth-clients","",""),
    TLS_REPLICATION("tls-replication","",""),
    TLS_CLUSTER("tls-cluster","",""),
    TLS_PROTOCOLS("tls-protocols","",""),
    TLS_CIPHERS("tls-ciphers","",""),
    TLS_CIPHERSUITES("tls-ciphersuites","",""),
    TLS_PREFER_SERVER_CIPHERS("tls-prefer-server-ciphers","",""),
    TLS_SESSION_CACHING("tls-session-caching","",""),
    TLS_SESSION_CACHE_SIZE("tls-session-cache-size","",""),
    TLS_SESSION_CACHE_TIMEOUT("tls-session-cache-timeout","",""),
    DAEMONIZE("daemonize","",""),
    SUPERVISED("supervised","",""),
    PIDFILE("pidfile","",""),
    LOGLEVEL("loglevel","",""),
    LOGFILE("logfile","",""),
    SYSLOG_ENABLED("syslog-enabled","",""),
    SYSLOG_IDENT("syslog-ident","",""),
    SYSLOG_FACILITY("syslog-facility","",""),
    CRASH_LOG_ENABLED("crash-log-enabled","",""),
    CRASH_MEMCHECK_ENABLED("crash-memcheck-enabled","",""),
    DATABASES("databases","",""),
    ALWAYS_SHOW_LOGO("always-show-logo","",""),
    SET_PROC_TITLE("set-proc-title","",""),
    PROC_TITLE_TEMPLATE("proc-title-template","",""),
    SAVE("save","",""),
    STOP_WRITES_ON_BGSAVE_ERROR("stop-writes-on-bgsave-error","",""),
    RDBCOMPRESSION("rdbcompression","",""),
    RDBCHECKSUM("rdbchecksum","",""),
    SANITIZE_DUMP_PAYLOAD("sanitize-dump-payload","",""),
    DBFILENAME("dbfilename","",""),
    RDB_DEL_SYNC_FILES("rdb-del-sync-files","",""),
    DIR("dir","",""),
    REPLICAOF("replicaof","",""),
    MASTERAUTH("masterauth","",""),
    MASTERUSER("masteruser","",""),
    REPLICA_SERVE_STALE_DATA("replica-serve-stale-data","",""),
    REPLICA_READ_ONLY("replica-read-only","",""),
    REPL_DISKLESS_SYNC("repl-diskless-sync","",""),
    REPL_DISKLESS_SYNC_DELAY("repl-diskless-sync-delay","",""),
    REPL_DISKLESS_LOAD("repl-diskless-load","",""),
    REPL_PING_REPLICA_PERIOD("repl-ping-replica-period","",""),
    REPL_TIMEOUT("repl-timeout","",""),
    REPL_DISABLE_TCP_NODELAY("repl-disable-tcp-nodelay","",""),
    REPL_BACKLOG_SIZE("repl-backlog-size","",""),
    REPL_BACKLOG_TTL("repl-backlog-ttl","",""),
    REPLICA_PRIORITY("replica-priority","",""),
    REPLICA_ANNOUNCED("replica-announced","",""),
    MIN_REPLICAS_TO_WRITE("min-replicas-to-write","",""),
    MIN_REPLICAS_MAX_LAG("min-replicas-max-lag","",""),
    REPLICA_ANNOUNCE_IP("replica-announce-ip","",""),
    REPLICA_ANNOUNCE_PORT("replica-announce-port","",""),
    TRACKING_TABLE_MAX_KEYS("tracking-table-max-keys","",""),
    ACLLOG_MAX_LEN("acllog-max-len","",""),
    ACLFILE("aclfile","",""),
    REQUIREPASS("requirepass","",""),
    ACL_PUBSUB_DEFAULT("acl-pubsub-default","",""),
    RENAME_COMMAND("rename-command","",""),
    MAXCLIENTS("maxclients","",""),
    MAXMEMORY("maxmemory","",""),
    MAXMEMORY_POLICY("maxmemory-policy","",""),
    MAXMEMORY_SAMPLES("maxmemory-samples","",""),
    MAXMEMORY_EVICTION_TENACITY("maxmemory-eviction-tenacity","",""),
    REPLICA_IGNORE_MAXMEMORY("replica-ignore-maxmemory","",""),
    ACTIVE_EXPIRE_EFFORT("active-expire-effort","",""),
    LAZYFREE_LAZY_EVICTION("lazyfree-lazy-eviction","",""),
    LAZYFREE_LAZY_EXPIRE("lazyfree-lazy-expire","",""),
    LAZYFREE_LAZY_SERVER_DEL("lazyfree-lazy-server-del","",""),
    REPLICA_LAZY_FLUSH("replica-lazy-flush","",""),
    LAZYFREE_LAZY_USER_DEL("lazyfree-lazy-user-del","",""),
    LAZYFREE_LAZY_USER_FLUSH("lazyfree-lazy-user-flush","",""),
    IO_THREADS("io-threads","",""),
    IO_THREADS_DO_READS("io-threads-do-reads","",""),
    OOM_SCORE_ADJ("oom-score-adj","",""),
    OOM_SCORE_ADJ_VALUES("oom-score-adj-values","",""),
    DISABLE_THP("disable-thp","",""),
    APPENDONLY("appendonly","",""),
    APPENDFILENAME("appendfilename","",""),
    APPENDFSYNC("appendfsync","",""),
    NO_APPENDFSYNC_ON_REWRITE("no-appendfsync-on-rewrite","",""),
    AUTO_AOF_REWRITE_PERCENTAGE("auto-aof-rewrite-percentage","",""),
    AUTO_AOF_REWRITE_MIN_SIZE("auto-aof-rewrite-min-size","",""),
    AOF_LOAD_TRUNCATED("aof-load-truncated","",""),
    AOF_USE_RDB_PREAMBLE("aof-use-rdb-preamble","",""),
    LUA_TIME_LIMIT("lua-time-limit","",""),
    CLUSTER_ENABLED("cluster-enabled","",""),
    CLUSTER_CONFIG_FILE("cluster-config-file","",""),
    CLUSTER_NODE_TIMEOUT("cluster-node-timeout","",""),
    CLUSTER_REPLICA_VALIDITY_FACTOR("cluster-replica-validity-factor","",""),
    CLUSTER_MIGRATION_BARRIER("cluster-migration-barrier","",""),
    CLUSTER_ALLOW_REPLICA_MIGRATION("cluster-allow-replica-migration","",""),
    CLUSTER_REQUIRE_FULL_COVERAGE("cluster-require-full-coverage","",""),
    CLUSTER_REPLICA_NO_FAILOVER("cluster-replica-no-failover","",""),
    CLUSTER_ALLOW_READS_WHEN_DOWN("cluster-allow-reads-when-down","",""),
    CLUSTER_ANNOUNCE_IP("cluster-announce-ip","",""),
    CLUSTER_ANNOUNCE_TLS_PORT("cluster-announce-tls-port","",""),
    CLUSTER_ANNOUNCE_PORT("cluster-announce-port","",""),
    CLUSTER_ANNOUNCE_BUS_PORT("cluster-announce-bus-port","",""),
    SLOWLOG_LOG_SLOWER_THAN("slowlog-log-slower-than","",""),
    SLOWLOG_MAX_LEN("slowlog-max-len","",""),
    LATENCY_MONITOR_THRESHOLD("latency-monitor-threshold","",""),
    NOTIFY_KEYSPACE_EVENTS("notify-keyspace-events","",""),
    GOPHER_ENABLED("gopher-enabled","",""),
    HASH_MAX_ZIPLIST_ENTRIES("hash-max-ziplist-entries","",""),
    HASH_MAX_ZIPLIST_VALUE("hash-max-ziplist-value","",""),
    LIST_MAX_ZIPLIST_SIZE("list-max-ziplist-size","",""),
    LIST_COMPRESS_DEPTH("list-compress-depth","",""),
    SET_MAX_INTSET_ENTRIES("set-max-intset-entries","",""),
    ZSET_MAX_ZIPLIST_ENTRIES("zset-max-ziplist-entries","",""),
    ZSET_MAX_ZIPLIST_VALUE("zset-max-ziplist-value","",""),
    HLL_SPARSE_MAX_BYTES("hll-sparse-max-bytes","",""),
    STREAM_NODE_MAX_BYTES("stream-node-max-bytes","",""),
    STREAM_NODE_MAX_ENTRIES("stream-node-max-entries","",""),
    ACTIVEREHASHING("activerehashing","",""),
    CLIENT_OUTPUT_BUFFER_LIMIT("client-output-buffer-limit","",""),
    CLIENT_QUERY_BUFFER_LIMIT("client-query-buffer-limit","",""),
    PROTO_MAX_BULK_LEN("proto-max-bulk-len","",""),
    HZ("hz","",""),
    DYNAMIC_HZ("dynamic-hz","",""),
    AOF_REWRITE_INCREMENTAL_FSYNC("aof-rewrite-incremental-fsync","",""),
    RDB_SAVE_INCREMENTAL_FSYNC("rdb-save-incremental-fsync","",""),
    LFU_LOG_FACTOR("lfu-log-factor","",""),
    LFU_DECAY_TIME("lfu-decay-time","",""),
    ACTIVEDEFRAG("activedefrag","",""),
    ACTIVE_DEFRAG_IGNORE_BYTES("active-defrag-ignore-bytes","",""),
    ACTIVE_DEFRAG_THRESHOLD_LOWER("active-defrag-threshold-lower","",""),
    ACTIVE_DEFRAG_THRESHOLD_UPPER("active-defrag-threshold-upper","",""),
    ACTIVE_DEFRAG_CYCLE_MIN("active-defrag-cycle-min","",""),
    ACTIVE_DEFRAG_CYCLE_MAX("active-defrag-cycle-max","",""),
    ACTIVE_DEFRAG_MAX_SCAN_FIELDS("active-defrag-max-scan-fields","",""),
    JEMALLOC_BG_THREAD("jemalloc-bg-thread","",""),
    SERVER_CPULIST("server_cpulist","",""),
    BIO_CPULIST("bio_cpulist","",""),
    AOF_REWRITE_CPULIST("aof_rewrite_cpulist","",""),
    BGSAVE_CPULIST("bgsave_cpulist","",""),
    IGNORE_WARNINGS("ignore-warnings","",""),
    SLAVEOF("slaveof","",""),
    SLAVE_SERVE_STALE_DATA("slave-serve-stale-data","",""),
    SLAVE_READ_ONLY("slave-read-only","",""),
    SLAVE_PRIORITY("slave-priority","",""),
    SLAVE_LAZY_FLUSH("slave-lazy-flush","","");

    private String key;

    private String value;

    private String desc;

    RedisConfigPoolEnum(String key, String value, String desc) {
        this.key = key;
        this.value = value;
        this.desc = desc;
    }

    public String getValue() {
        return value;
    }

    public String getDesc() {
        return desc;
    }

    public String getKey() {
        return key;
    }

    public static RedisConfigPoolEnum get(String key) {
        if (key == null) {
            return null;
        }
        for (RedisConfigPoolEnum config : RedisConfigPoolEnum.values()) {
            if (config.key.equals(key)) {
                return config;
            }
        }
        return null;
    }

    public static boolean containKey(String key){
        Optional<RedisConfigPoolEnum> first = Stream.of(RedisConfigPoolEnum.values()).filter(e -> key.equals(e.key)).findFirst();
        return first.isPresent();
    }

}
