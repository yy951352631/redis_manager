/**
 * 集群类型在后端以 `redis-cluster` / `redis-sentinel` / `redis-standalone` 的形式返回，
 * 前缀对每一行都相同、不携带信息，展示时去掉以腾出列宽。
 */
export function formatClusterType(typeDesc?: string | null): string {
  if (!typeDesc) return ""
  return typeDesc.replace(/^redis-/i, "")
}
