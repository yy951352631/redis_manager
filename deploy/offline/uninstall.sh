#!/usr/bin/env bash
# 卸载 CacheCloud 平台本体。
#
#   sudo bash uninstall.sh          # 停服务、删应用与配置，保留数据库
#   sudo bash uninstall.sh --purge  # 同上，并额外删除数据库（不可恢复）
#
# MySQL / Redis / nginx / JDK 这些基础组件不动 —— 它们可能还被别的系统用着。

set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/lib.sh
. "$HERE/scripts/lib.sh"

PURGE=0
[ "${1:-}" = "--purge" ] && PURGE=1

need_root
load_env "${CC_ENV_FILE:-$HERE/conf/cachecloud.env}"
TOMCAT_HOME="${CC_TOMCAT_HOME:-$CC_HOME/tomcat}"

step "确认"
cat <<EOF
  即将删除：
    - systemd 服务 cachecloud-web
    - $TOMCAT_HOME/webapps/ROOT{,.war}
    - $CC_HOME/www/dist
    - nginx 站点配置 cachecloud.conf
EOF
if [ "$PURGE" -eq 1 ]; then
  printf '    - 数据库 %s@%s（不可恢复）\n' "$CC_MYSQL_DB" "$CC_MYSQL_HOST"
else
  printf '  数据库 %s 会保留。\n' "$CC_MYSQL_DB"
fi
printf '\n  输入 yes 继续：'
read -r ans
[ "$ans" = "yes" ] || die "已取消"

step "停止服务"
systemctl stop cachecloud-web 2>/dev/null || true
systemctl disable cachecloud-web 2>/dev/null || true
rm -f /etc/systemd/system/cachecloud-web.service
systemctl daemon-reload
info "服务已移除"

step "删除应用文件"
rm -rf "${TOMCAT_HOME:?}/webapps/ROOT" "${TOMCAT_HOME:?}/webapps/ROOT.war"
rm -rf "${CC_HOME:?}/www/dist"
info "应用文件已删除（日志保留在 $CC_HOME/logs）"

step "移除 nginx 配置"
for d in /etc/nginx/conf.d /usr/local/nginx/conf/conf.d /etc/nginx/http.d; do
  [ -f "$d/cachecloud.conf" ] && { rm -f "$d/cachecloud.conf"; info "已删除 $d/cachecloud.conf"; }
done
if nginx -t >/dev/null 2>&1; then systemctl reload nginx 2>/dev/null || true; fi

if [ "$PURGE" -eq 1 ]; then
  step "删除数据库"
  MYSQL_PWD="$CC_MYSQL_PASSWORD" mysql -h"$CC_MYSQL_HOST" -P"$CC_MYSQL_PORT" \
    -u"$CC_MYSQL_USER" -e "DROP DATABASE IF EXISTS \`$CC_MYSQL_DB\`"
  warn "数据库 $CC_MYSQL_DB 已删除"
fi

echo
info "卸载完成"
log  "  运行账号 $CC_USER 与目录 $CC_HOME 未删除，确认无用后手工清理："
log  "    userdel $CC_USER && rm -rf $CC_HOME"
