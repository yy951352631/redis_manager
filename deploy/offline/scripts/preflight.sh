#!/usr/bin/env bash
# 环境自检：装之前先跑一遍，把不满足的条件一次性列全，避免装到一半才发现缺东西。
# 只读，不改任何东西。
#
#   bash scripts/preflight.sh            # 用 conf/cachecloud.env
#   bash scripts/preflight.sh /path/env  # 指定配置文件

set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PKG="$(cd "$HERE/.." && pwd)"
# shellcheck source=lib.sh
. "$HERE/lib.sh"

ENV_FILE="${1:-$PKG/conf/cachecloud.env}"
FAIL=0
check_fail() { printf '%s  ✗%s %s\n' "$CC_RED" "$CC_RESET" "$*"; FAIL=$((FAIL + 1)); }
check_ok()   { printf '%s  ✓%s %s\n' "$CC_GREEN" "$CC_RESET" "$*"; }
check_warn() { printf '%s  !%s %s\n' "$CC_YELLOW" "$CC_RESET" "$*"; }

step "配置文件"
if [ -f "$ENV_FILE" ]; then
  check_ok "$ENV_FILE"
  # shellcheck disable=SC1090
  set -a; . "$ENV_FILE"; set +a
else
  check_fail "缺少 ${ENV_FILE}，先 cp conf/cachecloud.env.example conf/cachecloud.env"
fi

step "必填配置项"
for v in CC_HOME CC_MYSQL_HOST CC_MYSQL_PORT CC_MYSQL_DB CC_MYSQL_USER CC_MYSQL_PASSWORD \
         CC_REDIS_HOST CC_REDIS_PORT CC_HTTP_PORT CC_BACKEND_PORT CC_SERVER_DOMAIN; do
  val="${!v:-}"
  if [ -z "$val" ]; then
    check_fail "$v 未设置"
  elif [ "$val" = "replace-me" ] || [ "$val" = "CHANGE_ME" ]; then
    check_fail "$v 还是模板默认值"
  else
    case "$v" in
      *PASSWORD*) check_ok "$v = ******" ;;
      *)          check_ok "$v = $val" ;;
    esac
  fi
done

step "Java（需要 JDK 8）"
if have java; then
  jv="$(java -version 2>&1 | head -1)"
  if java -version 2>&1 | grep -qE '"1\.8|version "8'; then check_ok "$jv"
  else check_fail "$jv —— 后端按 JDK 8 编译，高版本 JVM 未验证" ; fi
else
  check_fail "找不到 java，先安装 JDK 8（见手册「基础组件」）"
fi

step "Tomcat"
if [ -n "${CC_TOMCAT_HOME:-}" ] && [ -x "${CC_TOMCAT_HOME}/bin/catalina.sh" ]; then
  check_ok "$CC_TOMCAT_HOME"
elif [ -d "$PKG/deps" ] && ls "$PKG"/deps/apache-tomcat-9*.tar.gz >/dev/null 2>&1; then
  check_ok "介质包内有 Tomcat 压缩包，install.sh 会解压到 \$CC_HOME/tomcat"
else
  check_fail "CC_TOMCAT_HOME 无效，且 deps/ 下没有 apache-tomcat-9*.tar.gz"
fi

step "nginx"
if have nginx; then check_ok "$(nginx -v 2>&1)"
else check_fail "找不到 nginx，前端静态站点与反代需要它"; fi

step "MySQL 连通性（$CC_MYSQL_HOST:${CC_MYSQL_PORT}）"
if have mysql; then
  check_ok "mysql 客户端: $(mysql --version)"
  if MYSQL_PWD="$CC_MYSQL_PASSWORD" mysql -h"$CC_MYSQL_HOST" -P"$CC_MYSQL_PORT" \
       -u"$CC_MYSQL_USER" -e 'select 1' >/dev/null 2>&1; then
    check_ok "连接成功"
    ver="$(MYSQL_PWD="$CC_MYSQL_PASSWORD" mysql -h"$CC_MYSQL_HOST" -P"$CC_MYSQL_PORT" \
            -u"$CC_MYSQL_USER" -N -B -e 'select version()' 2>/dev/null)"
    case "$ver" in
      5.7.*|8.0.*) check_ok "版本 $ver" ;;
      *)           check_warn "版本 $ver —— 仅在 5.7.44 上验证过" ;;
    esac
    n="$(MYSQL_PWD="$CC_MYSQL_PASSWORD" mysql -h"$CC_MYSQL_HOST" -P"$CC_MYSQL_PORT" \
          -u"$CC_MYSQL_USER" -N -B -e \
          "select count(*) from information_schema.tables where table_schema='$CC_MYSQL_DB'" 2>/dev/null || echo 0)"
    if [ "${n:-0}" -eq 0 ]; then check_ok "库 $CC_MYSQL_DB 为空，install.sh 会执行 init.sql"
    else check_warn "库 $CC_MYSQL_DB 已有 $n 张表，install.sh 会跳过 init.sql（升级场景）"; fi
  else
    check_fail "连接失败，检查地址/账号/口令与 MySQL 的授权"
  fi
else
  check_fail "找不到 mysql 客户端，install.sh 要用它导入 init.sql"
fi

step "Redis 连通性（$CC_REDIS_HOST:${CC_REDIS_PORT}）"
if wait_port "$CC_REDIS_HOST" "$CC_REDIS_PORT" 3; then check_ok "端口可达"
else check_fail "端口不可达，平台自用 Redis 未启动或地址不对"; fi
if have redis-cli; then check_ok "redis-cli: $(redis-cli --version)"
else check_warn "找不到 redis-cli —— 平台的部分运维功能（如手工执行命令）需要它"; fi

step "端口占用"
for p in "$CC_HTTP_PORT" "$CC_BACKEND_PORT"; do
  if port_in_use "$p"; then check_fail "端口 $p 已被占用"; else check_ok "端口 $p 空闲"; fi
done

step "磁盘"
target="${CC_HOME%/*}"; [ -d "$target" ] || target=/
avail_kb="$(df -Pk "$target" | awk 'NR==2{print $4}')"
avail_gb=$((avail_kb / 1024 / 1024))
if [ "$avail_gb" -ge 40 ]; then check_ok "$target 可用 ${avail_gb}G"
elif [ "$avail_gb" -ge 20 ]; then check_warn "$target 仅可用 ${avail_gb}G —— 指标按分钟写入，建议 ≥40G"
else check_fail "$target 仅可用 ${avail_gb}G，太少；MySQL 写满会让整个平台挂起"; fi

step "介质包完整性"
for f in app/cachecloud-web.war sql/init.sql conf/nginx-cachecloud.conf conf/cachecloud-web.service; do
  if [ -e "$PKG/$f" ]; then check_ok "$f"; else check_fail "缺少 ${f}，介质包不完整"; fi
done
if [ -d "$PKG/app/dist" ] && [ -f "$PKG/app/dist/index.html" ]; then check_ok "app/dist（前端静态资源）"
else check_fail "缺少 app/dist/index.html"; fi

echo
if [ "$FAIL" -eq 0 ]; then
  info "自检通过，可以执行 bash install.sh"
else
  die "自检未通过：$FAIL 项需要处理"
fi
