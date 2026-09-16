#!/usr/bin/env bash
# CacheCloud 离线介质包入口（非容器环境）。
#
#   bash install-cachecloud-offline.sh --verify-only [介质包.tar.gz]
#   sudo bash install-cachecloud-offline.sh [介质包.tar.gz] cachecloud.env
#
# 介质包参数省略时，会自动使用脚本同目录下唯一的 cachecloud-offline-*.tar.gz。

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ARCHIVE=""
ENV_FILE=""
VERIFY_ONLY=0

usage() {
  cat <<'EOF'
用法：
  bash install-cachecloud-offline.sh --verify-only [介质包.tar.gz]
  sudo bash install-cachecloud-offline.sh [介质包.tar.gz] cachecloud.env

说明：
  --verify-only  只校验压缩包与包内文件，不安装
  介质包省略时，自动使用脚本同目录下唯一的 cachecloud-offline-*.tar.gz
  cachecloud.env 可由同目录 cachecloud.env.example 复制后修改
EOF
}

for arg in "$@"; do
  case "$arg" in
    --verify-only) VERIFY_ONLY=1 ;;
    -h|--help) usage; exit 0 ;;
    --*) printf '未知参数：%s\n' "$arg" >&2; usage >&2; exit 1 ;;
    *)
      if [ -z "$ARCHIVE" ]; then
        ARCHIVE="$arg"
      elif [ -z "$ENV_FILE" ]; then
        ENV_FILE="$arg"
      else
        printf '参数过多：%s\n' "$arg" >&2
        usage >&2
        exit 1
      fi
      ;;
  esac
done

# 正式安装只给一个位置参数且不是 tar.gz 时，把它视为环境配置，归档自动发现。
if [ "$VERIFY_ONLY" -eq 0 ] && [ -n "$ARCHIVE" ] && [ -z "$ENV_FILE" ]; then
  case "$ARCHIVE" in
    *.tar.gz) ;;
    *) ENV_FILE="$ARCHIVE"; ARCHIVE="" ;;
  esac
fi

if [ -z "$ARCHIVE" ]; then
  candidates=("$SCRIPT_DIR"/cachecloud-offline-*.tar.gz)
  if [ "${#candidates[@]}" -ne 1 ] || [ ! -f "${candidates[0]}" ]; then
    printf '未找到唯一的 cachecloud-offline-*.tar.gz，请显式指定介质包路径\n' >&2
    exit 1
  fi
  ARCHIVE="${candidates[0]}"
fi

[ -f "$ARCHIVE" ] || { printf '介质包不存在：%s\n' "$ARCHIVE" >&2; exit 1; }
ARCHIVE="$(cd "$(dirname "$ARCHIVE")" && pwd)/$(basename "$ARCHIVE")"

sha256_check() {
  local checksum_file="$1" checksum_dir
  checksum_dir="$(dirname "$checksum_file")"
  if command -v sha256sum >/dev/null 2>&1; then
    (cd "$checksum_dir" && sha256sum -c "$(basename "$checksum_file")")
  elif command -v shasum >/dev/null 2>&1; then
    (cd "$checksum_dir" && shasum -a 256 -c "$(basename "$checksum_file")")
  else
    printf '缺少 sha256sum 或 shasum，无法校验介质完整性\n' >&2
    exit 1
  fi
}

printf '\n==> 校验离线介质包\n'
if [ -f "${ARCHIVE}.sha256" ]; then
  sha256_check "${ARCHIVE}.sha256"
else
  printf '缺少外层校验文件 %s，继续校验包内清单\n' "${ARCHIVE}.sha256" >&2
fi

WORK_DIR="$(mktemp -d "${TMPDIR:-/tmp}/cachecloud-offline.XXXXXX")"
cleanup() {
  case "$WORK_DIR" in
    "${TMPDIR:-/tmp}"/cachecloud-offline.*) rm -rf "$WORK_DIR" ;;
  esac
}
trap cleanup EXIT

tar -xzf "$ARCHIVE" -C "$WORK_DIR"
PACKAGE_ROOT="$(find "$WORK_DIR" -mindepth 1 -maxdepth 1 -type d -name 'cachecloud-offline-*' -print -quit)"
[ -n "$PACKAGE_ROOT" ] || { printf '介质包结构异常：缺少 cachecloud-offline-* 根目录\n' >&2; exit 1; }
[ -f "$PACKAGE_ROOT/MANIFEST.sha256" ] || { printf '介质包缺少 MANIFEST.sha256\n' >&2; exit 1; }
sha256_check "$PACKAGE_ROOT/MANIFEST.sha256"

for required in app/cachecloud-web.war app/dist/index.html sql/init.sql sql/upgrade.sql \
  conf/cachecloud.env.example conf/setenv.sh install.sh scripts/preflight.sh; do
  [ -e "$PACKAGE_ROOT/$required" ] || { printf '介质包缺少 %s\n' "$required" >&2; exit 1; }
done

if [ "$VERIFY_ONLY" -eq 1 ]; then
  printf '\n介质包校验通过：%s\n' "$ARCHIVE"
  exit 0
fi

if [ "$(id -u)" -ne 0 ]; then
  printf '正式安装需要 root 权限，请使用 sudo 运行\n' >&2
  exit 1
fi
[ -n "$ENV_FILE" ] || { printf '缺少 cachecloud.env，请从 cachecloud.env.example 复制并修改后传入\n' >&2; usage >&2; exit 1; }
[ -f "$ENV_FILE" ] || { printf '配置文件不存在：%s\n' "$ENV_FILE" >&2; exit 1; }
ENV_FILE="$(cd "$(dirname "$ENV_FILE")" && pwd)/$(basename "$ENV_FILE")"

install -m 0600 "$ENV_FILE" "$PACKAGE_ROOT/conf/cachecloud.env"

printf '\n==> 运行安装前自检\n'
bash "$PACKAGE_ROOT/scripts/preflight.sh" "$PACKAGE_ROOT/conf/cachecloud.env"

printf '\n==> 开始非容器安装\n'
CC_ENV_FILE="$PACKAGE_ROOT/conf/cachecloud.env" bash "$PACKAGE_ROOT/install.sh"
