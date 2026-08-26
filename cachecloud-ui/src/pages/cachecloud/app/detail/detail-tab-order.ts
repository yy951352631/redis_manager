import type { AppDetailTab } from "@/api/cachecloud"

/**
 * 集群详情 tab 的展示顺序（按业务使用频次排列，非功能分组）。
 *
 * 注：app_ops_password（集群密码修改）已改为「节点列表」工具栏上的抽屉按钮，
 * 不再作为 tab 出现，因此不在本列表中。
 * app_ops_config / command / restart 仅在机器池可用时出现，排在集群详情之前。
 */
export const DETAIL_TAB_ORDER: string[] = [
  "app_stat", // 集群统计信息
  "app_topology", // 节点列表
  "app_latency", // 延迟监控
  "app_command_analysis", // 命令曲线
  "app_top_pic", // 集群拓扑
  "app_ops_config_consistency", // 配置一致性校验
  "app_key_analysis", // 键值分析
  "app_daily", // 日报统计
  "app_alert_record", // 故障报警
  "app_ops_fault", // 故障诊断
  "app_ops_topology", // 集群拓扑诊断
  "app_clientList", // 连接信息
  "app_ops_config", // 配置检测（机器池可用时）
  "app_ops_command", // 命令检测（机器池可用时）
  "app_ops_restart", // 重启记录（机器池可用时）
  "app_detail" // 集群详情
]

const ORDER_INDEX = new Map(DETAIL_TAB_ORDER.map((key, index) => [key, index]))

export function sortDetailTabs(tabs: AppDetailTab[]): AppDetailTab[] {
  return [...tabs].sort((a, b) => {
    const ia = ORDER_INDEX.get(a.key) ?? 999
    const ib = ORDER_INDEX.get(b.key) ?? 999
    return ia - ib
  })
}
