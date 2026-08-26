/** 对外展示的集群编码（cluster_no，从 100001 起） */
export function displayClusterNo(clusterNo?: number | null, fallbackAppId?: number): number {
  if (clusterNo && clusterNo > 0) return clusterNo
  return fallbackAppId ?? 0
}

export function formatClusterNo(clusterNo?: number | null, fallbackAppId?: number): string {
  const id = displayClusterNo(clusterNo, fallbackAppId)
  return id > 0 ? String(id) : "-"
}

/** 路由/API 路径优先使用 cluster_no */
export function clusterRouteId(clusterNo?: number | null, fallbackAppId?: number): number {
  return displayClusterNo(clusterNo, fallbackAppId)
}
