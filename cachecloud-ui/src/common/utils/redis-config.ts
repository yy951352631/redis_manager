const YES_NO_CONFIGS = new Set([
  "aof-load-truncated",
  "aof-rewrite-incremental-fsync",
  "aof-use-rdb-preamble",
  "activerehashing",
  "activedefrag",
  "always-show-logo",
  "appendonly",
  "cluster-allow-reads-when-down",
  "cluster-allow-replica-migration",
  "cluster-enabled",
  "cluster-replica-no-failover",
  "cluster-require-full-coverage",
  "crash-log-enabled",
  "crash-memcheck-enabled",
  "daemonize",
  "disable-thp",
  "dynamic-hz",
  "gopher-enabled",
  "io-threads-do-reads",
  "jemalloc-bg-thread",
  "lazyfree-lazy-eviction",
  "lazyfree-lazy-expire",
  "lazyfree-lazy-server-del",
  "lazyfree-lazy-user-del",
  "lazyfree-lazy-user-flush",
  "no-appendfsync-on-rewrite",
  "protected-mode",
  "rdb-del-sync-files",
  "rdb-save-incremental-fsync",
  "rdbchecksum",
  "rdbcompression",
  "repl-disable-tcp-nodelay",
  "repl-diskless-sync",
  "replica-announced",
  "replica-ignore-maxmemory",
  "replica-lazy-flush",
  "replica-read-only",
  "replica-serve-stale-data",
  "sanitize-dump-payload",
  "set-proc-title",
  "stop-writes-on-bgsave-error",
  "syslog-enabled",
  "tls-cluster",
  "tls-prefer-server-ciphers",
  "tls-replication",
  "tls-session-caching"
])

const CONFIG_VALUE_OPTIONS: Record<string, string[]> = {
  "acl-pubsub-default": ["allchannels", "resetchannels"],
  "appendfsync": ["always", "everysec", "no"],
  "loglevel": ["debug", "verbose", "notice", "warning"],
  "maxmemory-policy": [
    "noeviction",
    "allkeys-lru",
    "volatile-lru",
    "allkeys-random",
    "volatile-random",
    "volatile-ttl",
    "allkeys-lfu",
    "volatile-lfu"
  ],
  "oom-score-adj": ["no", "yes", "relative"],
  "repl-diskless-load": ["disabled", "on-empty-db", "swapdb"],
  "supervised": ["no", "upstart", "systemd", "auto"],
  "syslog-facility": ["user", "local0", "local1", "local2", "local3", "local4", "local5", "local6", "local7"],
  "tls-auth-clients": ["yes", "no", "optional"]
}

export function getRedisConfigValueOptions(configName?: string) {
  const name = (configName ?? "").trim().toLowerCase()
  if (!name) return []
  if (YES_NO_CONFIGS.has(name)) return ["yes", "no"]
  return CONFIG_VALUE_OPTIONS[name] ?? []
}
