/**
 * 集群统计与节点统计共用的时序图槽位定义。
 *
 * 两个页面原先各维护一份内容相同、仅键名前缀不同的副本（opsQps / qps），
 * 这里统一成不带前缀的一份；`source` 只有集群页会用到（它的数据分 app / ops 两个接口取），
 * 节点页走单接口，忽略该字段即可。
 */

export type MetricChartSource = "app" | "ops"

export interface MetricChartSlot {
  key: string
  title: string
  source: MetricChartSource
  statNames: string[]
  seriesNames: string[]
  /** 左轴单位名，双轴图由 dualHitRate / dualPersistence 决定，此项忽略 */
  yAxisName?: string
  /** 网络流量：按数量级自适应 byte / KB / MB */
  netUnit?: boolean
  /** 叠加一条总内存参考虚线 */
  memTotalLine?: boolean
  /** 双轴：左次数、右命中率(%) */
  dualHitRate?: boolean
  /** 双轴：左次数、右 ms */
  dualPersistence?: boolean
  /** CPU 百分比，tooltip 与轴标签追加 % */
  cpuPercent?: boolean
  /** 取值只可能是整数（如连接数），轴与 tooltip 都不显示小数 */
  integerOnly?: boolean
}

/** 单条序列，x 为时间戳 */
export interface MetricSeries {
  name: string
  data: [number, number][]
}

/** 页面把原始数据整理成这个形状交给 MetricChartGrid 渲染 */
export interface MetricSeriesConfig {
  seriesList: MetricSeries[]
  yAxisName?: string
  dualHitRate?: boolean
  dualPersistence?: boolean
  cpuPercent?: boolean
  integerOnly?: boolean
}

/** 图表槽位与默认顺序，也是「恢复默认顺序」的基准（共 16 张） */
export const METRIC_CHART_SLOTS: MetricChartSlot[] = [
  { key: "memory", title: "内存使用量", source: "app", statNames: ["usedMemory", "usedMemoryRss"], seriesNames: ["used_memory", "used_memory_rss"], yAxisName: "MB", memTotalLine: true },
  { key: "cpu", title: "CPU消耗率", source: "app", statNames: ["cpuSys", "cpuUser", "cpuUserChildren"], seriesNames: ["sys", "user", "user_children"], yAxisName: "%", cpuPercent: true },
  { key: "commands", title: "全命令统计", source: "app", statNames: ["commandCount"], seriesNames: ["命令趋势图"] },
  // 走 ops 源：这两个是按 diff_json 里的 cmdstat_* 现场归类汇总出来的，
  // app 源读的是 app_minute_statistics，那张表没有逐命令明细。
  { key: "readWrite", title: "读/写命令统计", source: "ops", statNames: ["read_command_count", "write_command_count"], seriesNames: ["读命令", "写命令"], yAxisName: "次" },
  { key: "net", title: "网络流量", source: "app", statNames: ["netInput", "netOutput"], seriesNames: ["net_input", "net_output"], netUnit: true },
  { key: "qps", title: "实时 QPS", source: "ops", statNames: ["instantaneous_ops_per_sec"], seriesNames: ["QPS"], yAxisName: "次/秒" },
  // 命中/未命中/命中率三条放在一张图里看才有意义：只看命中次数涨跌分不清是流量变了还是缓存失效了
  { key: "hits", title: "缓存命中统计", source: "app", statNames: ["hits", "misses", "hitPercent"], seriesNames: ["命中(hits)", "未命中(misses)", "命中率(%)"], dualHitRate: true },
  { key: "memFragRatio", title: "内存碎片率", source: "app", statNames: ["memFragRatio"], seriesNames: ["内存碎片率"], yAxisName: "比率" },
  { key: "replication", title: "主从复制", source: "ops", statNames: ["repl_lag_max", "repl_link_down"], seriesNames: ["复制滞后(秒)", "链路异常数"], yAxisName: "值", integerOnly: true },
  { key: "replOffset", title: "复制偏移量 Offset", source: "ops", statNames: ["master_repl_offset"], seriesNames: ["master_repl_offset"], yAxisName: "MB" },
  // 拒绝连接与连接数同源同轴：连接被拒往往就是连接数顶到 maxclients 的直接后果，
  // 分在两张图里得来回对时间点。为此本槽位改走 ops 源（app 源没有 rejected_connections）。
  { key: "clients", title: "客户端连接数 / 拒绝连接数", source: "ops", statNames: ["connected_clients", "rejected_connections"], seriesNames: ["客户端连接数", "拒绝连接数"], yAxisName: "个", integerOnly: true },
  { key: "replicationFault", title: "主从复制异常统计", source: "ops", statNames: ["sync_full", "sync_partial_err"], seriesNames: ["全量复制", "部分复制失败"], yAxisName: "次数" },
  { key: "dbsize", title: "键个数统计", source: "app", statNames: ["objectSize"], seriesNames: ["object_size"], yAxisName: "个" },
  { key: "expired", title: "键过期/淘汰数统计", source: "app", statNames: ["expiredKeys", "evictedKeys"], seriesNames: ["expired_keys", "evicted_keys"], yAxisName: "次" },
  { key: "persistence", title: "持久化阻塞事件记录 AOF/RDB", source: "ops", statNames: ["aof_delayed_fsync", "latest_fork_usec"], seriesNames: ["AOF刷盘延迟次数", "fork耗时(ms)"], dualPersistence: true },
  // 上一次 RDB / AOF 重写的写盘耗时，是快照不是增量，集群侧按节点取最大值
  { key: "persistenceCost", title: "持久化耗时统计 AOF/RDB", source: "ops", statNames: ["rdb_last_bgsave_time_sec", "aof_last_rewrite_time_sec"], seriesNames: ["上次RDB写盘耗时", "上次AOF重写耗时"], yAxisName: "秒" }
]

/** 图表配色，两个页面原先各自维护一份相同的数组 */
export const METRIC_CHART_COLORS = [
  "#2f7ed8",
  "#E3170D",
  "#0d233a",
  "#8bbc21",
  "#1aadce",
  "#492970",
  "#804000",
  "#f28f43"
]

/**
 * 把存储的顺序套用到当前槽位定义上。
 *
 * 存储的顺序只是参考：存储时已删除的槽位要丢掉（否则出现幽灵卡片），
 * 存储后新增的槽位要按它在默认定义中的相对次序补到末尾（否则新图表永远不显示）。
 */
export function mergeChartOrder(
  slots: MetricChartSlot[],
  storedKeys: string[] | null
): MetricChartSlot[] {
  if (!storedKeys?.length) return [...slots]
  const remaining = new Map(slots.map(slot => [slot.key, slot]))
  const ordered: MetricChartSlot[] = []
  for (const key of storedKeys) {
    const slot = remaining.get(key)
    if (slot) {
      ordered.push(slot)
      remaining.delete(key)
    }
  }
  for (const slot of slots) {
    if (remaining.has(slot.key)) ordered.push(slot)
  }
  return ordered
}
