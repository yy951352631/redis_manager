#!/usr/bin/env bash
# 离线安装脚本共用函数。被 install.sh / uninstall.sh / preflight.sh source。

set -euo pipefail

CC_RED=$'\033[31m'; CC_GREEN=$'\033[32m'; CC_YELLOW=$'\033[33m'; CC_RESET=$'\033[0m'

log()  { printf '%s\n' "$*"; }
info() { printf '%s[ 信息 ]%s %s\n' "$CC_GREEN" "$CC_RESET" "$*"; }
warn() { printf '%s[ 注意 ]%s %s\n' "$CC_YELLOW" "$CC_RESET" "$*" >&2; }
die()  { printf '%s[ 失败 ]%s %s\n' "$CC_RED" "$CC_RESET" "$*" >&2; exit 1; }

step() { printf '\n==> %s\n' "$*"; }

need_root() {
  [ "$(id -u)" -eq 0 ] || die "请用 root 运行（或 sudo）"
}

have() { command -v "$1" >/dev/null 2>&1; }

# 读取 conf/cachecloud.env，导出其中的变量
load_env() {
  local f="$1"
  [ -f "$f" ] || die "配置文件不存在：${f}（从 cachecloud.env.example 复制并修改）"
  # shellcheck disable=SC1090
  set -a; . "$f"; set +a
}

# require_env VAR "说明"
require_env() {
  local name="$1" desc="${2:-}"
  local val="${!name:-}"
  [ -n "$val" ] || die "配置项 $name 未设置${desc:+（${desc}）}"
  case "$val" in
    replace-me|replace-with-a-strong-password|CHANGE_ME)
      die "配置项 $name 还是模板默认值，请改成真实值" ;;
  esac
}

# 端口是否已被占用
port_in_use() {
  local p="$1"
  if have ss; then ss -ltn 2>/dev/null | awk '{print $4}' | grep -qE "[:.]$p$"
  elif have netstat; then netstat -ltn 2>/dev/null | awk '{print $4}' | grep -qE "[:.]$p$"
  else return 1
  fi
}

# 等待 TCP 端口就绪：wait_port <host> <port> <秒>
wait_port() {
  local host="$1" port="$2" timeout="${3:-60}" i=0
  while [ "$i" -lt "$timeout" ]; do
    if (exec 3<>"/dev/tcp/$host/$port") 2>/dev/null; then exec 3>&- 2>/dev/null || true; return 0; fi
    i=$((i + 1)); sleep 1
  done
  return 1
}

# 等待 HTTP 返回 2xx：wait_http <url> <秒>
wait_http() {
  local url="$1" timeout="${2:-120}" i=0
  while [ "$i" -lt "$timeout" ]; do
    if curl -fsS -o /dev/null --max-time 5 "$url" 2>/dev/null; then return 0; fi
    i=$((i + 2)); sleep 2
  done
  return 1
}

# 备份将被覆盖的文件，带时间戳
backup_file() {
  local f="$1"
  [ -e "$f" ] || return 0
  local bak="${f}.bak.$(date +%Y%m%d%H%M%S)"
  cp -a "$f" "$bak"
  warn "已备份原文件：$bak"
}

# 用 sed 把模板里的 @VAR@ 占位替换掉，输出到目标路径
render() {
  local src="$1" dst="$2"; shift 2
  local tmp; tmp="$(mktemp)"
  cp "$src" "$tmp"
  local pair name value
  for pair in "$@"; do
    name="${pair%%=*}"; value="${pair#*=}"
    # value 里可能有 / 和 &，用 python 做字面替换更稳
    CC_NAME="$name" CC_VALUE="$value" python3 - "$tmp" <<'PY'
import io, os, sys
p = sys.argv[1]
s = io.open(p, encoding='utf-8').read()
s = s.replace('@%s@' % os.environ['CC_NAME'], os.environ['CC_VALUE'])
io.open(p, 'w', encoding='utf-8').write(s)
PY
  done
  backup_file "$dst"
  install -m 0644 "$tmp" "$dst"
  rm -f "$tmp"
}
