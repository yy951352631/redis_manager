#!/usr/bin/env bash
# 构建 CacheCloud 离线安装介质包。
#
#   bash scripts/build-offline-package.sh                 # 构建后端与前端，再打包
#   bash scripts/build-offline-package.sh --skip-build    # 复用已有产物（target/*.war 与 ui/dist）
#   bash scripts/build-offline-package.sh --with-deps     # 顺带把 Tomcat 下进包里（需联网）
#   CC_VERSION=1.2.0 bash scripts/build-offline-package.sh
#
# 产物：dist/cachecloud-offline-<版本>.tar.gz 及其 .sha256
#
# 构建机需要 JDK8 + Maven + Node 20.19+/22.12+ + pnpm 10+；
# 目标机什么都不需要装（除了手册里的基础组件）。

set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

SKIP_BUILD=0
WITH_DEPS=0
for arg in "$@"; do
  case "$arg" in
    --skip-build) SKIP_BUILD=1 ;;
    --with-deps)  WITH_DEPS=1 ;;
    -h|--help)    sed -n '2,16p' "$0"; exit 0 ;;
    *)            echo "未知参数：$arg" >&2; exit 1 ;;
  esac
done

VERSION="${CC_VERSION:-$(git describe --tags --always --dirty 2>/dev/null || date +%Y%m%d)}"
BUILD_TIME="$(date '+%Y-%m-%d %H:%M:%S %z')"
GIT_SHA="$(git rev-parse --short HEAD 2>/dev/null || echo unknown)"
NAME="cachecloud-offline-${VERSION}"
STAGE="$ROOT/dist/$NAME"
OUT="$ROOT/dist/${NAME}.tar.gz"

say() { printf '\n==> %s\n' "$*"; }

# ----------------------------------------------------------------- 构建
if [ "$SKIP_BUILD" -eq 0 ]; then
  say "构建后端 WAR"
  command -v mvn >/dev/null || { echo "找不到 mvn" >&2; exit 1; }
  mvn -q -B -ntp -pl cachecloud-web -am package -DskipTests

  say "构建前端 dist"
  command -v pnpm >/dev/null || { echo "找不到 pnpm（corepack enable 可启用）" >&2; exit 1; }
  (cd cachecloud-ui && pnpm install --frozen-lockfile && pnpm build)
else
  say "跳过构建，复用已有产物"
fi

WAR="$ROOT/cachecloud-web/target/cachecloud-web.war"
UI_DIST="$ROOT/cachecloud-ui/dist"
[ -f "$WAR" ] || { echo "缺少 ${WAR}，先构建或去掉 --skip-build" >&2; exit 1; }
[ -f "$UI_DIST/index.html" ] || { echo "缺少 $UI_DIST/index.html" >&2; exit 1; }

# ----------------------------------------------------------------- 组装
say "组装介质包 $NAME"
rm -rf "$STAGE"
mkdir -p "$STAGE"/{app,sql,conf,scripts,deps}

cp -a deploy/offline/install.sh   "$STAGE/"
cp -a deploy/offline/uninstall.sh "$STAGE/"
cp -a deploy/offline/scripts/.    "$STAGE/scripts/"
cp -a deploy/offline/conf/.       "$STAGE/conf/"

cp -a "$WAR"     "$STAGE/app/cachecloud-web.war"
cp -a "$UI_DIST" "$STAGE/app/dist"

# init.sql 用 LF 版本；CRLF 在部分 mysql 客户端里会把行尾带进字符串字面量
tr -d '\r' < cachecloud-web/sql/init.sql    > "$STAGE/sql/init.sql"
tr -d '\r' < cachecloud-web/sql/upgrade.sql > "$STAGE/sql/upgrade.sql"
cp -a cachecloud-web/sql/README.md "$STAGE/sql/README.md"

cp -a docs/deploy/offline-install.md "$STAGE/README.md"

if [ "$WITH_DEPS" -eq 1 ]; then
  say "拉取第三方介质"
  bash "$STAGE/scripts/fetch-deps.sh"
fi

cat > "$STAGE/VERSION" <<EOF
version:    $VERSION
git:        $GIT_SHA
build_time: $BUILD_TIME
build_host: $(hostname)
EOF

chmod +x "$STAGE"/install.sh "$STAGE"/uninstall.sh "$STAGE"/scripts/*.sh

# 清单，便于目标机核对文件是否传全
say "生成清单与校验和"
(cd "$STAGE" && find . -type f ! -name MANIFEST.sha256 -print0 \
  | sort -z | xargs -0 shasum -a 256 > MANIFEST.sha256)
info_count="$(wc -l < "$STAGE/MANIFEST.sha256" | tr -d ' ')"

# ----------------------------------------------------------------- 打包
say "打包"
rm -f "$OUT" "$OUT.sha256"
tar -C "$ROOT/dist" -czf "$OUT" "$NAME"
(cd "$ROOT/dist" && shasum -a 256 "$(basename "$OUT")" > "$(basename "$OUT").sha256")
rm -rf "$STAGE"

SIZE="$(du -h "$OUT" | cut -f1)"
cat <<EOF

  介质包   $OUT
  大小     $SIZE
  文件数   $info_count
  校验     $(cat "$OUT.sha256")

  拷到目标机后：
    tar xzf $(basename "$OUT") && cd $NAME
    cp conf/cachecloud.env.example conf/cachecloud.env && vi conf/cachecloud.env
    bash scripts/preflight.sh
    sudo bash install.sh

EOF
