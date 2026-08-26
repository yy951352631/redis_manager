#!/bin/bash
# 在 运行 assist Redis 的机器上执行，重置 requirepass 并与 application-local.yml 保持一致
set -euo pipefail

# 不内置默认密码：默认值一旦泄露，等于所有按文档搭起来的环境共用同一个口令
NEW_PASSWORD="${1:?用法: $0 <新密码>}"
OLD_PASSWORD="${OLD_PASSWORD:-}"
CONF="${CONF:-/etc/cachecloud-assist-redis.conf}"
REDIS_CLI="${REDIS_CLI:-redis-cli}"

auth_args() {
  if [[ -n "${OLD_PASSWORD}" ]]; then
    echo "-a" "${OLD_PASSWORD}" "--no-auth-warning"
  else
    echo ""
  fi
}

echo "==> 新密码: ${NEW_PASSWORD}"

if [[ -n "${OLD_PASSWORD}" ]]; then
  eval "${REDIS_CLI}" $(auth_args) PING >/dev/null
  eval "${REDIS_CLI}" $(auth_args) CONFIG SET requirepass "${NEW_PASSWORD}"
fi

eval "${REDIS_CLI}" -a "${NEW_PASSWORD}" --no-auth-warning PING
echo "PING OK"

if [[ -f "${CONF}" ]]; then
  if grep -q '^requirepass ' "${CONF}"; then
    sed -i "s/^requirepass .*/requirepass ${NEW_PASSWORD}/" "${CONF}"
  else
    echo "requirepass ${NEW_PASSWORD}" >> "${CONF}"
  fi
  echo "已更新 ${CONF}"
  if command -v systemctl >/dev/null 2>&1; then
    systemctl restart cachecloud-assist-redis 2>/dev/null || true
  fi
fi

echo "完成。请确认 application-local.yml 中 cachecloud.redis.main.password 同为: ${NEW_PASSWORD}"
