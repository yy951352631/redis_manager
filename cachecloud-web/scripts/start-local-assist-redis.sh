#!/bin/bash
# 启动本机辅助 Redis（127.0.0.1:6379，口令由 ASSIST_PASSWORD 提供）
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
REDIS_SERVER="${ROOT}/.local-setup/redis-7.2.4/src/redis-server"
REDIS_CLI="${ROOT}/.local-setup/redis-7.2.4/src/redis-cli"
CONF="${ROOT}/.local-setup/assist-redis-local.conf"
DATA_DIR="${ROOT}/.local-setup/assist-redis-local/data"
PASSWORD="${ASSIST_PASSWORD:?请设置 ASSIST_PASSWORD}"

mkdir -p "${DATA_DIR}"

if "${REDIS_CLI}" -h 127.0.0.1 -p 6379 -a "${PASSWORD}" --no-auth-warning PING >/dev/null 2>&1; then
  echo "本地辅助 Redis 已在运行 (127.0.0.1:6379)"
  exit 0
fi

"${REDIS_SERVER}" "${CONF}"
sleep 1
"${REDIS_CLI}" -h 127.0.0.1 -p 6379 -a "${PASSWORD}" --no-auth-warning PING
echo "OK: 本地辅助 Redis 已启动"
echo "  host: 127.0.0.1"
echo "  port: 6379"
echo "  password: ${PASSWORD}"
