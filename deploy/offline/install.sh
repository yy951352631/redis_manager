#!/usr/bin/env bash
# CacheCloud 非容器环境安装脚本。
#
#   bash scripts/preflight.sh     # 先自检
#   sudo bash install.sh          # 再安装
#
# 本脚本只负责「装平台自己」：部署 WAR 与前端静态资源、导入 SQL、
# 生成 Tomcat / nginx / systemd 配置并启动。
# JDK、MySQL、Redis、nginx 这些基础组件不由本脚本安装 —— 各发行版差异太大，
# 装法见手册 docs/deploy/offline-install.md 的「基础组件」。Tomcat 是例外：
# 它就是一个解压即用的目录，deps/ 里有压缩包时会自动展开。
#
# 可重复执行：再跑一次等于升级（换 WAR 与 dist、重启服务），已有库不会被动。

set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/lib.sh
. "$HERE/scripts/lib.sh"

ENV_FILE="${CC_ENV_FILE:-$HERE/conf/cachecloud.env}"

need_root
load_env "$ENV_FILE"

for v in CC_HOME CC_USER CC_MYSQL_HOST CC_MYSQL_PORT CC_MYSQL_DB CC_MYSQL_USER \
         CC_MYSQL_PASSWORD CC_REDIS_HOST CC_REDIS_PORT CC_HTTP_PORT CC_BACKEND_PORT \
         CC_SERVER_DOMAIN; do
  require_env "$v"
done
: "${CC_REDIS_PASSWORD:=}"
: "${CC_JAVA_XMS:=512m}"
: "${CC_JAVA_XMX:=1536m}"
: "${CC_PROFILE:=online}"
: "${CC_TIMEZONE:=Asia/Shanghai}"

TOMCAT_HOME="${CC_TOMCAT_HOME:-$CC_HOME/tomcat}"
WEB_ROOT="$CC_HOME/www"

# ---------------------------------------------------------------- 用户与目录
step "创建运行账号与目录"
if ! id "$CC_USER" >/dev/null 2>&1; then
  useradd --system --home-dir "$CC_HOME" --shell /sbin/nologin "$CC_USER"
  info "已创建系统账号 $CC_USER"
else
  info "账号 $CC_USER 已存在"
fi
install -d -o "$CC_USER" -g "$CC_USER" -m 0750 "$CC_HOME" "$CC_HOME/logs" "$CC_HOME/tmp"
install -d -o "$CC_USER" -g "$CC_USER" -m 0755 "$WEB_ROOT"

# ------------------------------------------------------------------- Tomcat
step "准备 Tomcat"
if [ -x "$TOMCAT_HOME/bin/catalina.sh" ]; then
  info "复用已有 Tomcat：$TOMCAT_HOME"
else
  tgz="$(ls "$HERE"/deps/apache-tomcat-9*.tar.gz 2>/dev/null | head -1 || true)"
  [ -n "$tgz" ] || die "既没有可用的 ${TOMCAT_HOME}，deps/ 下也没有 apache-tomcat-9*.tar.gz"
  info "解压 $(basename "$tgz")"
  tmp="$(mktemp -d)"; tar -xzf "$tgz" -C "$tmp"
  src="$(find "$tmp" -maxdepth 1 -type d -name 'apache-tomcat-*' | head -1)"
  [ -n "$src" ] || die "压缩包结构异常，没找到 apache-tomcat-* 目录"
  rm -rf "$TOMCAT_HOME"; mv "$src" "$TOMCAT_HOME"; rm -rf "$tmp"
fi
# 自带的样例应用没有用处，留着还多一份攻击面
rm -rf "${TOMCAT_HOME:?}"/webapps/* "$TOMCAT_HOME"/conf/Catalina/localhost/*.xml 2>/dev/null || true

# ------------------------------------------------------------------ 应用文件
step "部署后端 WAR"
[ -f "$HERE/app/cachecloud-web.war" ] || die "介质包里没有 app/cachecloud-web.war"
install -d -o "$CC_USER" -g "$CC_USER" "$TOMCAT_HOME/webapps"
install -o "$CC_USER" -g "$CC_USER" -m 0644 "$HERE/app/cachecloud-web.war" "$TOMCAT_HOME/webapps/ROOT.war"
rm -rf "$TOMCAT_HOME/webapps/ROOT"
info "→ $TOMCAT_HOME/webapps/ROOT.war"

step "部署前端静态资源"
[ -f "$HERE/app/dist/index.html" ] || die "介质包里没有 app/dist/index.html"
rm -rf "${WEB_ROOT:?}/dist.old"
[ -d "$WEB_ROOT/dist" ] && mv "$WEB_ROOT/dist" "$WEB_ROOT/dist.old"
cp -a "$HERE/app/dist" "$WEB_ROOT/dist"
chown -R "$CC_USER":"$CC_USER" "$WEB_ROOT/dist"
rm -rf "$WEB_ROOT/dist.old"
info "→ $WEB_ROOT/dist"

# ------------------------------------------------------------------ 数据库
step "初始化数据库"
have mysql || die "找不到 mysql 客户端"
table_count="$(MYSQL_PWD="$CC_MYSQL_PASSWORD" mysql -h"$CC_MYSQL_HOST" -P"$CC_MYSQL_PORT" \
  -u"$CC_MYSQL_USER" -N -B -e \
  "select count(*) from information_schema.tables where table_schema='$CC_MYSQL_DB'" 2>/dev/null || echo -1)"
if [ "$table_count" = "-1" ]; then
  die "连不上 MySQL（$CC_MYSQL_HOST:${CC_MYSQL_PORT}），先跑 scripts/preflight.sh 定位"
elif [ "$table_count" -gt 0 ]; then
  warn "库 $CC_MYSQL_DB 已有 $table_count 张表，跳过 init.sql"
  warn "（升级场景无需手工建表：后端启动时会补齐新表与新列）"
else
  info "空库，导入 sql/init.sql"
  # init.sql 顶部写死了 CREATE DATABASE / USE redis_manager，库名不同时就地改掉
  sql="$HERE/sql/init.sql"
  if [ "$CC_MYSQL_DB" != "redis_manager" ]; then
    sql="$(mktemp)"; sed "s/\`redis_manager\`/\`$CC_MYSQL_DB\`/g" "$HERE/sql/init.sql" > "$sql"
  fi
  MYSQL_PWD="$CC_MYSQL_PASSWORD" mysql -h"$CC_MYSQL_HOST" -P"$CC_MYSQL_PORT" -u"$CC_MYSQL_USER" < "$sql"
  [ "$sql" = "$HERE/sql/init.sql" ] || rm -f "$sql"
  n="$(MYSQL_PWD="$CC_MYSQL_PASSWORD" mysql -h"$CC_MYSQL_HOST" -P"$CC_MYSQL_PORT" \
    -u"$CC_MYSQL_USER" -N -B -e \
    "select count(*) from information_schema.tables where table_schema='$CC_MYSQL_DB'")"
  info "建表完成，共 $n 张"
fi

# -------------------------------------------------------------------- 配置
step "生成配置"
JDBC_URL="jdbc:mysql://${CC_MYSQL_HOST}:${CC_MYSQL_PORT}/${CC_MYSQL_DB}?useUnicode=true&characterEncoding=UTF8&autoReconnect=true&connectTimeout=3000&socketTimeout=10000&serverTimezone=${CC_TIMEZONE}"

install -d -m 0755 "$TOMCAT_HOME/bin"
render "$HERE/conf/setenv.sh" "$TOMCAT_HOME/bin/setenv.sh" \
  "JAVA_XMS=$CC_JAVA_XMS" "JAVA_XMX=$CC_JAVA_XMX" "TIMEZONE=$CC_TIMEZONE" \
  "PROFILE=$CC_PROFILE" "SERVER_DOMAIN=$CC_SERVER_DOMAIN" \
  "JDBC_URL=$JDBC_URL" "MYSQL_USER=$CC_MYSQL_USER" "MYSQL_PASSWORD=$CC_MYSQL_PASSWORD" \
  "REDIS_HOST=$CC_REDIS_HOST" "REDIS_PORT=$CC_REDIS_PORT" "REDIS_PASSWORD=$CC_REDIS_PASSWORD" \
  "CC_HOME=$CC_HOME"
chmod 0750 "$TOMCAT_HOME/bin/setenv.sh"
chown "$CC_USER":"$CC_USER" "$TOMCAT_HOME/bin/setenv.sh"
info "→ $TOMCAT_HOME/bin/setenv.sh（含数据库口令，权限 0750）"

# Tomcat 只监听回环：外部一律走 nginx，后端端口不直接暴露
render "$HERE/conf/server.xml" "$TOMCAT_HOME/conf/server.xml" \
  "BACKEND_PORT=$CC_BACKEND_PORT"
chown "$CC_USER":"$CC_USER" "$TOMCAT_HOME/conf/server.xml"
chown -R "$CC_USER":"$CC_USER" "$TOMCAT_HOME"
info "→ $TOMCAT_HOME/conf/server.xml（仅监听 127.0.0.1:${CC_BACKEND_PORT}）"

NGINX_CONF_DIR=""
for d in /etc/nginx/conf.d /usr/local/nginx/conf/conf.d /etc/nginx/http.d; do
  [ -d "$d" ] && { NGINX_CONF_DIR="$d"; break; }
done
[ -n "$NGINX_CONF_DIR" ] || die "找不到 nginx 的 conf.d 目录，手工放置 conf/nginx-cachecloud.conf"
render "$HERE/conf/nginx-cachecloud.conf" "$NGINX_CONF_DIR/cachecloud.conf" \
  "HTTP_PORT=$CC_HTTP_PORT" "BACKEND_PORT=$CC_BACKEND_PORT" "WEB_ROOT=$WEB_ROOT"
info "→ $NGINX_CONF_DIR/cachecloud.conf"

render "$HERE/conf/cachecloud-web.service" /etc/systemd/system/cachecloud-web.service \
  "CC_USER=$CC_USER" "TOMCAT_HOME=$TOMCAT_HOME" "CC_HOME=$CC_HOME"
info "→ /etc/systemd/system/cachecloud-web.service"

# -------------------------------------------------------------------- 启动
step "启动服务"
systemctl daemon-reload
systemctl enable cachecloud-web >/dev/null 2>&1 || true
systemctl restart cachecloud-web
info "cachecloud-web 已启动，等待就绪（首次展开 WAR 需要 60-90 秒）"

if ! wait_http "http://127.0.0.1:${CC_BACKEND_PORT}/api/v1/health" 180; then
  warn "后端 180 秒内没有就绪，看日志："
  warn "  journalctl -u cachecloud-web -n 100 --no-pager"
  warn "  tail -100 $TOMCAT_HOME/logs/catalina.out"
  die "后端启动失败"
fi
info "后端健康检查通过"

if nginx -t >/dev/null 2>&1; then
  systemctl reload nginx 2>/dev/null || systemctl restart nginx
  info "nginx 已加载新配置"
else
  nginx -t || true
  die "nginx 配置校验失败，见上方输出"
fi

if ! wait_http "http://127.0.0.1:${CC_HTTP_PORT}/" 30; then
  die "前端 $CC_HTTP_PORT 端口无响应，检查 nginx 与 SELinux（见手册「常见问题」）"
fi

# -------------------------------------------------------------------- 收尾
cat <<EOF

$(info 安装完成)

  访问地址   ${CC_SERVER_DOMAIN}
  默认账号   admin / admin%TGB7ygv    ← 首次登录后立刻修改

  后端日志   journalctl -u cachecloud-web -f
             tail -f ${TOMCAT_HOME}/logs/catalina.out
  服务控制   systemctl {start|stop|restart|status} cachecloud-web
  卸载       sudo bash uninstall.sh

EOF
