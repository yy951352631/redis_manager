#!/bin/sh
# 用「配置文件」方式启动 Redis，而不是纯命令行参数。
#
# 为什么必须这样：CONFIG REWRITE 要求服务端启动时确实加载过一个配置文件，
# 否则 Redis 固定返回 "ERR The server is running without a config file"。
# 结果就是 CacheCloud 里改完配置只在运行时生效，节点一重启就恢复原值。
#
# 配置文件写在数据卷 /data 内，原因有二：
#   1) Redis 必须对它有写权限，CONFIG REWRITE 才能真正落盘（只读挂载会失败）；
#   2) 放在卷里才能跨容器重建保留 REWRITE 过的内容。
#
# 已存在则不覆盖 —— 否则每次重启都会把平台改过的配置抹掉，等于白改。
set -e

CONF=/data/redis.conf

if [ ! -f "$CONF" ]; then
    {
        echo "port 6379"
        echo "dir /data"
        echo "appendonly yes"
        if [ "${REDIS_CLUSTER:-no}" = "yes" ]; then
            echo "cluster-enabled yes"
            echo "cluster-config-file nodes.conf"
            echo "cluster-node-timeout ${REDIS_CLUSTER_NODE_TIMEOUT:-5000}"
            [ -n "$REDIS_ANNOUNCE_IP" ] && echo "cluster-announce-ip $REDIS_ANNOUNCE_IP"
        else
            [ -n "$REDIS_ANNOUNCE_IP" ] && echo "replica-announce-ip $REDIS_ANNOUNCE_IP"
            [ -n "$REDIS_REPLICAOF" ] && echo "replicaof $REDIS_REPLICAOF"
        fi
    } > "$CONF"
    echo "[entrypoint] 已生成 $CONF"
else
    echo "[entrypoint] 沿用已有的 $CONF（含此前 CONFIG REWRITE 落盘的内容）"
fi

exec redis-server "$CONF"
