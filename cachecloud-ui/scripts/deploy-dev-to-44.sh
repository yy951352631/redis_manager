#!/usr/bin/env bash
# 开发模式部署 cachecloud-ui 到 指定服务器（由 SERVER_HOST 环境变量给出）
# 用法：./scripts/deploy-dev-to-44.sh
# 可选环境变量：SERVER_USER SERVER_HOST REMOTE_DIR DEV_PORT

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
SERVER_USER="${SERVER_USER:-root}"
# 不设默认值：部署目标必须显式指定，避免把某台具体服务器写进公开仓库
SERVER_HOST="${SERVER_HOST:?请设置 SERVER_HOST，例如 export SERVER_HOST=10.0.0.1}"
REMOTE_DIR="${REMOTE_DIR:-/app/tomcat/cachecloud-ui}"
DEV_PORT="${DEV_PORT:-3333}"

echo "==> 部署目标: ${SERVER_USER}@${SERVER_HOST}:${REMOTE_DIR}"

echo "==> 检查 SSH 连通性..."
ssh -o ConnectTimeout=10 "${SERVER_USER}@${SERVER_HOST}" "hostname && echo OK"

echo "==> 同步前端代码（排除 node_modules / dist）..."
ssh "${SERVER_USER}@${SERVER_HOST}" "mkdir -p '${REMOTE_DIR}'"
rsync -avz --delete \
  --exclude node_modules \
  --exclude dist \
  --exclude .git \
  --exclude .husky/_ \
  "${ROOT_DIR}/" "${SERVER_USER}@${SERVER_HOST}:${REMOTE_DIR}/"

echo "==> 远程安装依赖并启动 Vite 开发服务..."
ssh "${SERVER_USER}@${SERVER_HOST}" bash -s <<EOF
set -euo pipefail
cd '${REMOTE_DIR}'

if ! command -v node >/dev/null 2>&1; then
  echo "未检测到 Node.js，尝试安装 Node 18..."
  curl -fsSL https://rpm.nodesource.com/setup_18.x | bash -
  yum install -y nodejs
fi
echo "Node: \$(node -v), npm: \$(npm -v)"

# 停掉旧的 vite 进程
pkill -f "vite.*--port ${DEV_PORT}" 2>/dev/null || true
pkill -f "vite.*--port=${DEV_PORT}" 2>/dev/null || true
sleep 1

npm install --legacy-peer-deps
nohup npm run dev -- --host 0.0.0.0 --port ${DEV_PORT} > dev.log 2>&1 &
sleep 3

if ss -lntp 2>/dev/null | grep -q ":${DEV_PORT} "; then
  echo "前端已启动，端口 ${DEV_PORT} 监听中"
elif netstat -lntp 2>/dev/null | grep -q ":${DEV_PORT} "; then
  echo "前端已启动，端口 ${DEV_PORT} 监听中"
else
  echo "启动可能失败，最近日志："
  tail -30 dev.log || true
  exit 1
fi

echo ""
echo "后端检查（8080）："
curl -s -o /dev/null -w "HTTP %{http_code}\n" http://127.0.0.1:8080/manage/login || true
EOF

echo ""
echo "=========================================="
echo "  部署完成！浏览器访问："
echo "  http://${SERVER_HOST}:${DEV_PORT}"
echo "  账号：admin / admin%TGB7ygv"
echo "=========================================="
echo "查看日志: ssh ${SERVER_USER}@${SERVER_HOST} 'tail -f ${REMOTE_DIR}/dev.log'"
