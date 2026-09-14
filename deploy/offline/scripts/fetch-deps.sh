#!/usr/bin/env bash
# 在**联网**机器上执行，把第三方安装介质下载到 deps/，
# 这样整个介质包拷进内网后就是自包含的。
#
#   bash scripts/fetch-deps.sh              # 只下 Tomcat（install.sh 唯一会自动展开的）
#   bash scripts/fetch-deps.sh --all        # 额外下 JDK/MySQL/Redis/nginx 的下载提示
#
# 说明：JDK、MySQL、Redis、nginx 的分发形式与发行版强相关（rpm/deb/源码），
# 且多数需要接受许可或按 OS 版本选包，这里不替你猜 —— 用 --all 会打印出
# 对应版本的获取方式，由你按目标系统下好后放进 deps/。

set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PKG="$(cd "$HERE/.." && pwd)"
# shellcheck source=lib.sh
. "$HERE/lib.sh"

TOMCAT_VERSION="${TOMCAT_VERSION:-9.0.108}"
mkdir -p "$PKG/deps"

step "下载 Tomcat $TOMCAT_VERSION"
tgz="$PKG/deps/apache-tomcat-${TOMCAT_VERSION}.tar.gz"
if [ -f "$tgz" ]; then
  info "已存在，跳过：$(basename "$tgz")"
else
  url="https://archive.apache.org/dist/tomcat/tomcat-9/v${TOMCAT_VERSION}/bin/apache-tomcat-${TOMCAT_VERSION}.tar.gz"
  info "$url"
  curl -fL --progress-bar -o "$tgz.part" "$url" || die "下载失败，可手工下好后放到 deps/"
  mv "$tgz.part" "$tgz"
  # 校验和一并下下来，install.sh 不强校验，但留着便于人工核对
  curl -fsSL -o "$tgz.sha512" "$url.sha512" 2>/dev/null || warn "校验和文件没下到"
  info "→ $(basename "$tgz")"
fi

if [ "${1:-}" = "--all" ]; then
  step "其余基础组件（需按目标系统自行获取，放进 deps/）"
  cat <<'EOF'
  JDK 8（后端按 JDK 8 编译）
    RHEL/CentOS/Anolis : yum install -y java-1.8.0-openjdk-devel
                         离线：yum install --downloadonly --downloaddir=deps/ java-1.8.0-openjdk-devel
    Ubuntu/Debian      : apt-get install -y openjdk-8-jdk
    通用 tarball       : https://adoptium.net/temurin/releases/?version=8

  MySQL 5.7.44（已验证版本；8.0 亦可，注意 sql_mode 与默认排序规则）
    RPM Bundle : https://dev.mysql.com/get/Downloads/MySQL-5.7/mysql-5.7.44-1.el7.x86_64.rpm-bundle.tar
    要点       : character-set-server=utf8mb4, collation-server=utf8mb4_bin,
                 lower_case_table_names=1, max_allowed_packet=256M
                 sql_mode 去掉 ONLY_FULL_GROUP_BY

  Redis 6.2.13（平台自用缓存，不是被纳管对象）
    源码 : https://download.redis.io/releases/redis-6.2.13.tar.gz
    要点 : 建议设 requirepass；appendonly yes

  nginx 1.20+（托管前端 dist 并反代后端）
    RHEL 系 : yum install -y nginx
    离线     : yum install --downloadonly --downloaddir=deps/ nginx
EOF
fi

echo
info "deps/ 当前内容："
ls -lh "$PKG/deps" 2>/dev/null | tail -n +2 | sed 's/^/    /' || echo "    （空）"
