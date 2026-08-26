#!/usr/bin/env bash
# 部署 cachecloud-web.war 到 指定服务器
# 流程：停服务 → 备份旧包 → 上传新包 → 启动
# 用法：./scripts/deploy-war-to-44.sh
# 可选：SERVER_USER SERVER_HOST REMOTE_DIR SPRING_PROFILE SERVER_PORT

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
SERVER_USER="${SERVER_USER:-root}"
# 不设默认值：部署目标必须显式指定，避免把某台具体服务器写进公开仓库
SERVER_HOST="${SERVER_HOST:?请设置 SERVER_HOST，例如 export SERVER_HOST=10.0.0.1}"
REMOTE_DIR="${REMOTE_DIR:-/app/tomcat/cachecloud}"
SPRING_PROFILE="${SPRING_PROFILE:-online}"
SERVER_PORT="${SERVER_PORT:-8080}"
WAR_NAME="cachecloud-web.war"
LOCAL_WAR="${LOCAL_WAR:-${REPO_ROOT}/cachecloud-web/target/${WAR_NAME}}"
MVN="${MVN:-/Applications/IntelliJ IDEA.app/Contents/plugins/maven/lib/maven3/bin/mvn}"

if [[ ! -f "${LOCAL_WAR}" ]]; then
  echo "==> 本地未找到 ${LOCAL_WAR}，开始打包..."
  "${MVN}" -f "${REPO_ROOT}/pom.xml" -pl cachecloud-web -am clean package -DskipTests
fi

if [[ ! -f "${LOCAL_WAR}" ]]; then
  echo "打包失败，未生成 ${LOCAL_WAR}" >&2
  exit 1
fi

echo "==> 部署包: ${LOCAL_WAR} ($(du -h "${LOCAL_WAR}" | awk '{print $1}'))"
echo "==> 目标: ${SERVER_USER}@${SERVER_HOST}:${REMOTE_DIR}"

ssh -o ConnectTimeout=10 "${SERVER_USER}@${SERVER_HOST}" "hostname && echo OK"

BACKUP_SUFFIX="$(date +%Y%m%d_%H%M%S)"

echo "==> 上传 war 包..."
scp "${LOCAL_WAR}" "${SERVER_USER}@${SERVER_HOST}:${REMOTE_DIR}/${WAR_NAME}.new"

echo "==> 远程停服务、备份、替换、启动..."
ssh "${SERVER_USER}@${SERVER_HOST}" bash -s <<EOF
set -euo pipefail
cd '${REMOTE_DIR}'

echo "-- 停止旧进程"
OLD_PIDS=\$(ps -ef | grep '[j]ava.*cachecloud-web' | awk '{print \$2}' || true)
if [[ -n "\${OLD_PIDS}" ]]; then
  echo "kill: \${OLD_PIDS}"
  kill \${OLD_PIDS} || true
  sleep 3
  STILL=\$(ps -ef | grep '[j]ava.*cachecloud-web' | awk '{print \$2}' || true)
  if [[ -n "\${STILL}" ]]; then
    kill -9 \${STILL} || true
  fi
else
  echo "未发现运行中的 cachecloud-web 进程"
fi

echo "-- 备份旧包"
if [[ -f '${WAR_NAME}' ]]; then
  mv '${WAR_NAME}' '${WAR_NAME}_${BACKUP_SUFFIX}'
  echo "已备份为 ${WAR_NAME}_${BACKUP_SUFFIX}"
fi

echo "-- 替换新包"
mv '${WAR_NAME}.new' '${WAR_NAME}'

mkdir -p logs
echo "-- 启动服务"
nohup java -jar '${WAR_NAME}' \
  --spring.profiles.active=${SPRING_PROFILE} \
  --server.port=${SERVER_PORT} \
  > logs/startup.log 2>&1 &

sleep 8
NEW_PID=\$(ps -ef | grep '[j]ava.*cachecloud-web' | awk '{print \$2}' | head -1)
if [[ -z "\${NEW_PID}" ]]; then
  echo "启动失败，日志："
  tail -50 logs/startup.log || true
  exit 1
fi

echo "启动成功 PID=\${NEW_PID}"
HTTP_CODE=\$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:${SERVER_PORT}/manage/login || echo 000)
echo "健康检查 /manage/login => HTTP \${HTTP_CODE}"
tail -20 logs/startup.log || true
EOF

echo ""
echo "=========================================="
echo "  后端部署完成"
echo "  http://${SERVER_HOST}:${SERVER_PORT}/manage/login"
echo "  日志: ssh ${SERVER_USER}@${SERVER_HOST} 'tail -f ${REMOTE_DIR}/logs/startup.log'"
echo "=========================================="
