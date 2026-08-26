#!/usr/bin/env bash
# CacheCloud MySQL 定时逻辑备份。
#
# 以 sidecar 容器常驻运行：启动后立即备份一次，之后每 BACKUP_INTERVAL_SECONDS 备份一次。
# 产物落在宿主机 ./deploy/mysql/backups，不随 docker volume 一起被清掉。
#
# 恢复：
#   gunzip -c backups/redis_manager-20260823-2230.sql.gz | \
#     docker exec -i cachecloud-mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" redis_manager
#
# 若 binlog 已开启，dump 头部会带上 CHANGE MASTER 注释（--master-data=2），
# 可据此用 mysqlbinlog 做到「恢复到故障前任意时刻」。
set -uo pipefail

MYSQL_HOST="${MYSQL_HOST:-mysql}"
MYSQL_PORT="${MYSQL_PORT:-3306}"
MYSQL_USER="${MYSQL_USER:-root}"
BACKUP_DB="${BACKUP_DB:-redis_manager}"
BACKUP_DIR="${BACKUP_DIR:-/backup}"
BACKUP_INTERVAL_SECONDS="${BACKUP_INTERVAL_SECONDS:-21600}"
BACKUP_KEEP_DAYS="${BACKUP_KEEP_DAYS:-14}"

log() { echo "[$(date '+%F %T')] $*"; }

mkdir -p "$BACKUP_DIR"

# --master-data=2 需要 binlog；未开启时 mysqldump 会直接报错，这里探测一次后决定是否带该参数。
detect_master_data_flag() {
    local on
    on=$(mysql -h"$MYSQL_HOST" -P"$MYSQL_PORT" -u"$MYSQL_USER" -p"$MYSQL_ROOT_PASSWORD" \
            -N -B -e "SELECT @@log_bin" 2>/dev/null)
    if [ "$on" = "1" ]; then
        echo "--master-data=2"
    else
        log "WARN: binlog 未开启，本次备份不含 binlog 位点，无法做时间点恢复"
        echo ""
    fi
}

backup_once() {
    local stamp target tmp master_flag
    stamp=$(date '+%Y%m%d-%H%M%S')
    target="${BACKUP_DIR}/${BACKUP_DB}-${stamp}.sql.gz"
    tmp="${target}.partial"
    master_flag=$(detect_master_data_flag)

    # shellcheck disable=SC2086
    if mysqldump -h"$MYSQL_HOST" -P"$MYSQL_PORT" -u"$MYSQL_USER" -p"$MYSQL_ROOT_PASSWORD" \
            --single-transaction --quick --routines --triggers --events \
            --default-character-set=utf8mb4 \
            $master_flag \
            --databases "$BACKUP_DB" 2>/tmp/dump.err | gzip -c > "$tmp"; then
        mv "$tmp" "$target"
        log "OK  $(basename "$target")  $(du -h "$target" | cut -f1)"
    else
        rm -f "$tmp"
        log "FAIL 备份失败: $(tr '\n' ' ' < /tmp/dump.err)"
        return 1
    fi
}

prune() {
    local removed
    removed=$(find "$BACKUP_DIR" -maxdepth 1 -name "${BACKUP_DB}-*.sql.gz" \
                   -mtime "+${BACKUP_KEEP_DAYS}" -print -delete | wc -l | tr -d ' ')
    [ "$removed" != "0" ] && log "清理过期备份 ${removed} 个（保留 ${BACKUP_KEEP_DAYS} 天）"
    return 0
}

log "备份服务启动: db=${BACKUP_DB} 间隔=${BACKUP_INTERVAL_SECONDS}s 保留=${BACKUP_KEEP_DAYS}天 目录=${BACKUP_DIR}"
while true; do
    backup_once
    prune
    sleep "$BACKUP_INTERVAL_SECONDS"
done
