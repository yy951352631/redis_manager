/**
 * 版本名在后端以 `redis-6.2.13` 的形式存储，该前缀同时用于拼接安装目录，不能改存储格式，
 * 只在展示时去掉。
 */
export function formatRedisVersion(name?: string | null): string {
  if (!name) return ""
  return name.replace(/^redis-/i, "")
}
