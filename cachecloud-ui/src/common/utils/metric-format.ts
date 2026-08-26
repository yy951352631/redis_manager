/**
 * 监控图表统一数值格式化。
 *
 * 规则只有一条：有小数部分的保留两位，整数原样输出，两者都加千分位分隔符。
 * tooltip、Y 轴标签、放大视图的摘要与明细表共用它，避免同一个数在四处显示成四个样子。
 */

const INTEGER_FORMAT = new Intl.NumberFormat("zh-CN", { maximumFractionDigits: 0 })
const DECIMAL_FORMAT = new Intl.NumberFormat("zh-CN", {
  minimumFractionDigits: 2,
  maximumFractionDigits: 2
})

/** 两位小数会被舍成 0.00、但本身不为 0 的极小值，改用两位有效数字，避免看起来像没数据 */
function formatTinyValue(value: number) {
  return Number(value.toPrecision(2)).toString()
}

export function formatMetricValue(value: unknown): string {
  if (value === null || value === undefined || value === "") return "-"
  const num = Number(value)
  if (Number.isNaN(num)) return "-"
  if (!Number.isFinite(num)) return num > 0 ? "∞" : "-∞"
  if (Number.isInteger(num)) return INTEGER_FORMAT.format(num)
  if (num !== 0 && Math.abs(num) < 0.005) return formatTinyValue(num)
  return DECIMAL_FORMAT.format(num)
}

/** 带单位后缀，单位为空时等价于 formatMetricValue */
export function formatMetricValueWithUnit(value: unknown, unit?: string): string {
  const text = formatMetricValue(value)
  if (!unit || text === "-") return text
  return unit === "%" ? `${text}%` : `${text} ${unit}`
}

/** 时间戳 → `MM-DD HH:mm:ss`，明细表用（同一次查询最长 7 天，不需要年份） */
export function formatMetricTime(timestamp: number): string {
  const d = new Date(timestamp)
  const p = (n: number) => String(n).padStart(2, "0")
  return `${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
}
