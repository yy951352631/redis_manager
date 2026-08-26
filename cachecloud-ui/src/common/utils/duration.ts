/**
 * Redis 运行时长的统一展示口径。
 *
 * 节点管理列表和节点统计信息页共用：同一个实例在两个页面必须显示同一个时长，
 * 各写一份迟早会因为进位规则不同而对不上。
 */
export function formatUptime(seconds?: number | null): string {
  if (!seconds || seconds < 0) return "-"
  if (seconds < 60) return `${seconds}秒`
  const minutes = Math.floor(seconds / 60)
  if (minutes < 60) return `${minutes}分钟`
  const hours = Math.floor(minutes / 60)
  if (hours < 24) return `${hours}小时${minutes % 60 ? `${minutes % 60}分钟` : ""}`
  const days = Math.floor(hours / 24)
  return `${days}天${hours % 24 ? `${hours % 24}小时` : ""}`
}
