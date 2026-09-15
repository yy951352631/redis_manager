export interface RedisClientField {
  key: string
  label: string
  value: string
}

const FLAG_LABELS: Record<string, string> = {
  A: "尽快关闭",
  b: "阻塞等待中",
  c: "回复后关闭",
  d: "WATCH 键已变更",
  i: "等待 VM I/O",
  M: "Master 客户端",
  N: "普通客户端",
  O: "MONITOR 附属",
  P: "Pub/Sub 客户端",
  r: "只读集群节点",
  S: "Slave 客户端",
  u: "未阻塞",
  U: "Unix 套接字",
  x: "事务执行中"
}

const FIELD_META: Record<string, { label: string, order: number }> = {
  "id": { label: "连接 ID", order: 10 },
  "addr": { label: "客户端地址", order: 20 },
  "laddr": { label: "本地地址", order: 30 },
  "port": { label: "客户端端口", order: 40 },
  "cmd": { label: "最近命令", order: 50 },
  "flags": { label: "客户端类型", order: 60 },
  "user": { label: "用户名", order: 70 },
  "age": { label: "连接时长", order: 80 },
  "idle": { label: "空闲时间", order: 90 },
  "db": { label: "数据库", order: 100 },
  "fd": { label: "套接字 FD", order: 110 },
  "events": { label: "IO 状态", order: 120 },
  "tot-mem": { label: "占用内存", order: 130 },
  "sub": { label: "频道订阅数", order: 140 },
  "psub": { label: "模式订阅数", order: 150 },
  "multi": { label: "事务状态", order: 160 },
  "redir": { label: "集群重定向", order: 170 },
  "qbuf": { label: "查询缓冲", order: 180 },
  "qbuf-free": { label: "查询缓冲剩余", order: 190 },
  "obl": { label: "输出缓冲", order: 200 },
  "oll": { label: "输出列表长度", order: 210 },
  "omem": { label: "输出内存", order: 220 },
  "name": { label: "连接名称", order: 230 }
}

export function parseClientDetail(detail: string): Record<string, string> {
  const text = detail.trim()
  if (!text) return {}

  if (text.startsWith("{") && text.endsWith("}")) {
    const result: Record<string, string> = {}
    for (const part of text.slice(1, -1).split(", ")) {
      const eq = part.indexOf("=")
      if (eq > 0) {
        result[part.slice(0, eq)] = part.slice(eq + 1)
      }
    }
    return result
  }

  const result: Record<string, string> = {}
  for (const token of text.split(/\s+/)) {
    const eq = token.indexOf("=")
    if (eq > 0) {
      result[token.slice(0, eq)] = token.slice(eq + 1)
    }
  }
  return result
}

export function formatClientFlags(flags?: string) {
  if (!flags) return "-"
  const labels = [...flags].map(ch => FLAG_LABELS[ch] ? `${ch}(${FLAG_LABELS[ch]})` : ch)
  return labels.join(" · ")
}

export function formatClientEvents(events?: string) {
  if (!events) return "-"
  return events
    .split("")
    .map(ch => (ch === "r" ? "可读" : ch === "w" ? "可写" : ch))
    .join(" · ")
}

function formatBytes(value?: string) {
  if (!value) return "-"
  const num = Number(value)
  if (Number.isNaN(num)) return value
  if (num < 1024) return `${num} B`
  if (num < 1024 * 1024) return `${(num / 1024).toFixed(1)} KB`
  return `${(num / 1024 / 1024).toFixed(2)} MB`
}

function formatSeconds(value?: string) {
  if (!value) return "-"
  const sec = Number(value)
  if (Number.isNaN(sec)) return value
  if (sec < 60) return `${sec} 秒`
  if (sec < 3600) return `${Math.floor(sec / 60)} 分 ${sec % 60} 秒`
  const hours = Math.floor(sec / 3600)
  const minutes = Math.floor((sec % 3600) / 60)
  return `${hours} 小时 ${minutes} 分`
}

function formatFieldValue(key: string, value: string) {
  if (key === "flags") return formatClientFlags(value)
  if (key === "events") return formatClientEvents(value)
  if (key === "age" || key === "idle") return formatSeconds(value)
  if (key === "tot-mem" || key === "omem" || key === "argv-mem") return formatBytes(value)
  if (key === "multi") return value === "-1" ? "无事务" : value
  if (key === "redir") return value === "-1" ? "无" : value
  return value || "-"
}

export function buildClientDisplayFields(detail: string, fallback?: { flags?: string, cmd?: string }) {
  const parsed = parseClientDetail(detail)
  if (fallback?.flags && !parsed.flags) parsed.flags = fallback.flags
  if (fallback?.cmd && !parsed.cmd) parsed.cmd = fallback.cmd

  const fields: RedisClientField[] = []
  const used = new Set<string>()

  const sortedKeys = Object.keys(FIELD_META).sort(
    (a, b) => FIELD_META[a].order - FIELD_META[b].order
  )

  for (const key of sortedKeys) {
    if (!(key in parsed)) continue
    used.add(key)
    fields.push({
      key,
      label: FIELD_META[key].label,
      value: formatFieldValue(key, parsed[key])
    })
  }

  for (const [key, value] of Object.entries(parsed)) {
    if (used.has(key)) continue
    fields.push({
      key,
      label: key,
      value: formatFieldValue(key, value)
    })
  }

  return fields
}

export function buildConnectionSummary(detail: string, fallback?: { flags?: string, cmd?: string }) {
  const parsed = parseClientDetail(detail)
  const cmd = parsed.cmd || fallback?.cmd || "-"
  const flags = formatClientFlags(parsed.flags || fallback?.flags)
  const idle = parsed.idle ? `空闲 ${formatSeconds(parsed.idle)}` : ""
  const addr = parsed.addr ? `来自 ${parsed.addr}` : ""
  return [cmd, flags, idle, addr].filter(Boolean).join(" · ")
}
