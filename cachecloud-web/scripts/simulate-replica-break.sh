#!/usr/bin/env bash
#
# 模拟 Redis 主从断联（仅用于测试环境）
#
# 用法:
#   ./simulate-replica-break.sh status
#   ./simulate-replica-break.sh break replicaof-no-one   # 默认，从节点执行 REPLICAOF NO ONE
#   ./simulate-replica-break.sh break kill-repl          # 主节点踢掉复制连接
#   ./simulate-replica-break.sh restore                  # 恢复主从
#
# 环境变量（按你的纳管实例修改）:
#   REDIS_HOST=139.196.144.53
#   MASTER_PORT=6380
#   SLAVE_PORT=6379
#   REDIS_PASSWORD=your_password
#   SENTINEL_PORT=26379        # 可选，仅 status 时查看 sentinel 认知
#   SENTINEL_MASTER_NAME=mymaster
#
set -euo pipefail

REDIS_HOST="${REDIS_HOST:-139.196.144.53}"
MASTER_PORT="${MASTER_PORT:-6380}"
SLAVE_PORT="${SLAVE_PORT:-6379}"
REDIS_PASSWORD="${REDIS_PASSWORD:-}"
SENTINEL_PORT="${SENTINEL_PORT:-26379}"
SENTINEL_MASTER_NAME="${SENTINEL_MASTER_NAME:-mymaster}"

REDIS_CLI=(redis-cli -h "$REDIS_HOST")
if [[ -n "$REDIS_PASSWORD" ]]; then
  REDIS_CLI+=(-a "$REDIS_PASSWORD" --no-auth-warning)
fi

die() { echo "ERROR: $*" >&2; exit 1; }
info() { echo "==> $*"; }

redis_cmd() {
  local port="$1"
  shift
  "${REDIS_CLI[@]}" -p "$port" "$@"
}

show_replication() {
  local label="$1"
  local port="$2"
  info "$label ($REDIS_HOST:$port)"
  redis_cmd "$port" INFO replication | grep -E '^(role|master_host|master_port|master_link_status|connected_slaves|slave_repl_offset|master_repl_offset):' || true
  echo
}

show_sentinel() {
  if [[ -z "${SENTINEL_PORT:-}" ]]; then
    return 0
  fi
  info "Sentinel 认知 ($REDIS_HOST:$SENTINEL_PORT, master=$SENTINEL_MASTER_NAME)"
  redis_cmd "$SENTINEL_PORT" SENTINEL master "$SENTINEL_MASTER_NAME" 2>/dev/null | head -20 || echo "  (无法读取 sentinel，可忽略)"
  echo
}

cmd_status() {
  show_replication "主节点" "$MASTER_PORT"
  show_replication "从节点" "$SLAVE_PORT"
  show_sentinel
}

confirm_break() {
  echo "⚠️  即将模拟主从断联（测试用途）"
  echo "    主: $REDIS_HOST:$MASTER_PORT"
  echo "    从: $REDIS_HOST:$SLAVE_PORT"
  echo "    模式: $1"
  echo
  read -r -p "确认继续? 输入 yes: " ans
  [[ "$ans" == "yes" ]] || die "已取消"
}

cmd_break() {
  local mode="${1:-replicaof-no-one}"
  confirm_break "$mode"

  case "$mode" in
    replicaof-no-one)
      info "从节点执行 REPLICAOF NO ONE（从库变独立实例，主从复制停止）"
      redis_cmd "$SLAVE_PORT" REPLICAOF NO ONE
      ;;
    kill-repl)
      info "主节点执行 CLIENT KILL TYPE replica（断开复制连接，从库会重连；若配置禁止则可能持续断联）"
      redis_cmd "$MASTER_PORT" CLIENT KILL TYPE replica || true
      ;;
    pause-repl)
      info "从节点 CLIENT PAUSE WRITE（暂停复制，模拟复制卡住）"
      redis_cmd "$SLAVE_PORT" CLIENT PAUSE 60000 WRITE
      ;;
    *)
      die "未知模式: $mode，可选: replicaof-no-one | kill-repl | pause-repl"
      ;;
  esac

  sleep 2
  info "断联后状态:"
  cmd_status
  echo "恢复命令: REDIS_HOST=$REDIS_HOST MASTER_PORT=$MASTER_PORT SLAVE_PORT=$SLAVE_PORT REDIS_PASSWORD=*** $0 restore"
}

cmd_restore() {
  info "从节点重新指向主节点: REPLICAOF $REDIS_HOST $MASTER_PORT"
  redis_cmd "$SLAVE_PORT" REPLICAOF "$REDIS_HOST" "$MASTER_PORT"
  sleep 3
  info "恢复后状态:"
  cmd_status

  local link
  link="$(redis_cmd "$SLAVE_PORT" INFO replication | grep '^master_link_status:' | cut -d: -f2 | tr -d '[:space:]')"
  if [[ "$link" == "up" ]]; then
    info "主从已恢复 (master_link_status=up)"
  else
    echo "WARN: master_link_status=$link，请检查密码、网络或 sentinel 是否介入 failover" >&2
  fi
}

usage() {
  cat <<EOF
模拟 Redis 主从断联（测试专用）

命令:
  status                         查看主/从复制状态
  break [mode]                   模拟断联
  restore                        恢复主从复制

断联模式:
  replicaof-no-one (默认)        从节点脱离主节点，最明显，适合测平台拓扑/告警
  kill-repl                      主节点断开复制连接（从节点通常会重连）
  pause-repl                     从节点暂停复制 60s（模拟复制延迟/卡住）

示例（哨兵应用 appId=3，按实际端口改）:
  export REDIS_HOST=139.196.144.53
  export MASTER_PORT=6380
  export SLAVE_PORT=6379
  export REDIS_PASSWORD='你的密码'
  ./simulate-replica-break.sh status
  ./simulate-replica-break.sh break
  ./simulate-replica-break.sh restore

注意:
  - 仅在测试/演练环境使用；生产慎用 replicaof-no-one（可能触发 sentinel failover）
  - 测完务必 restore
  - 平台采集有分钟级延迟，断联后等 1~2 分钟再看 CacheCloud 实例列表/拓扑巡检
EOF
}

main() {
  local cmd="${1:-}"
  case "$cmd" in
    status) shift; cmd_status "$@" ;;
    break) shift; cmd_break "${1:-replicaof-no-one}" ;;
    restore) shift; cmd_restore "$@" ;;
    -h|--help|help|"") usage ;;
    *) die "未知命令: $cmd，执行 $0 --help 查看用法" ;;
  esac
}

main "$@"
