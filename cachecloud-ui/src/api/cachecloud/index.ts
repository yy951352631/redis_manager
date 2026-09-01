import type {
  AppDetailResponseData,
  AppListResponseData,
  AppOfflineResultResponseData,
  ConfigCheckDetailResponseData,
  ConfigCheckListResponseData,
  DashboardOpsResponseData,
  CommandCheckDetailResponseData,
  CommandCheckListResponseData,
  DashboardDetailsResponseData,
  DashboardOverviewResponseData,
  DashboardResponseData,
  InstanceOpsOptionsResponseData,
  RestartRecordPageResponseData,
  ServerStatAppsResponseData,
  UserListResponseData,
  QuartzJobListResponseData,
  ExternalRedisListResponseData,
  ExternalNodeListPage,
  ExternalRedisNode,
  InstanceAlertPageResponseData,
  OperationAuditPageResponseData,
  AlertRecordPageResponseData,
  RiskAssessHistoryResponseData,
  RiskAssessOverviewResponseData,
  DataModelOptionsResponseData,
  DataModelResultResponseData,
  RiskRulesResponseData,
  RiskAssessReportResponseData,
  TaskFlowDetailResponseData,
  MigrateListPageResponseData,
  MigrateInitResponseData,
  MigrateActionResultResponseData,
  MigrateAppInstancesResponseData,
  MigrateTextResponseData,
  AppOpsInstancePageResponseData,
  AppOpsMachineListResponseData,
  AppPasswordResponseData,
  AppConfigConsistencyResponseData,
  AppTopologyResponseData,
  AppClientListResponseData,
  AppCommandClimax,
  AppDetailPanelResponseData,
  AppMachineTopologyResponseData,
  AppStatOverviewResponseData,
  ChartPointListResponseData,
  AppDailyResponseData,
  WikiContentResponseData,
  AppLatencyResponseData,
  KeyAnalysisPageResponseData,
  KeyAnalysisProgressResponseData,
  KeyAnalysisStartResultResponseData,
  KeyAnalysisResultResponseData,
  OfflineAnalysisPageResponseData,
  OfflineAnalysisRecordResponseData,
  ExternalRedisCreateFormResponseData,
  OpsActionResultResponseData,
  AppScrollRestartRequest,
  TopologyExamResponseData,
  FaultDiagnosticReportResponseData,
  FaultDiagnosticHistoryResponseData,
  DiagnosticAppListResponseData,
  DiagnosticInstanceListResponseData,
  DiagnosticTaskListResponseData,
  DiagnosticResultResponseData,
  OnlineHealthCheckResponseData,
  InstanceDetailResponseData,
  InstanceStatResponseData,
  InstanceFaultListResponseData,
  InstanceClientListResponseData,
  InstanceLogResponseData,
  InstanceConfigResponseData,
  InstanceConfigUpdateResponseData,
  InstanceCommandResultResponseData,
  InstanceCommandAnalysisResponseData,
  InstanceCommandChartResponseData,
  AppStatChartsBatchResponseData,
  BenchmarkProgress,
  BenchmarkResult
} from "./type"
import { getToken } from "@@/utils/local-storage"
import { request } from "@/http/axios"
import axios from "axios"

/** 集群管理列表（M3） */
export function getAppListApi(params: {
  appStatus?: number
  appParam?: string
  ip?: string
  pageNo?: number
  pageSize?: number
}) {
  return request<AppListResponseData>({
    url: "apps",
    method: "get",
    params
  })
}

export function offlineAppApi(appId: number, appAuditId?: number) {
  return request<AppOfflineResultResponseData>({
    url: `apps/${appId}/offline`,
    method: "post",
    params: appAuditId != null ? { appAuditId } : undefined
  })
}

/** 集群详情框架（M3） */
export function recoverAppApi(appId: number) {
  return request<AppOfflineResultResponseData>({
    url: `apps/${appId}/recover`,
    method: "post"
  })
}

export function permanentlyDeleteOfflineAppApi(appId: number) {
  return request<void>({
    url: `apps/${appId}`,
    method: "DELETE"
  })
}

export function getAppDetailApi(appId: number | string) {
  return request<AppDetailResponseData>({
    url: `apps/${appId}`,
    method: "get"
  })
}


/** 节点运维选项（M8） */
export function getInstanceOpsOptionsApi() {
  return request<InstanceOpsOptionsResponseData>({
    url: "instance-ops/options",
    method: "get"
  })
}

/** 配置检测记录列表（M8） */
export function getConfigCheckListApi() {
  return request<ConfigCheckListResponseData>({
    url: "instance-ops/config-checks",
    method: "get"
  })
}

/** 执行配置检测（M8） */
export function runConfigCheckApi(data: {
  appId?: number
  versionId?: number
  configName: string
  compareType: number
  expectValue?: string
}) {
  return request<ApiResponseData<string>>({
    url: "instance-ops/config-checks",
    method: "post",
    data
  })
}

/** 配置检测异常详情（M8） */
export function getConfigCheckDetailApi(uuid: string) {
  return request<ConfigCheckDetailResponseData>({
    url: `instance-ops/config-checks/${uuid}`,
    method: "get"
  })
}

/** 命令检测记录列表（M8） */
export function getCommandCheckListApi() {
  return request<CommandCheckListResponseData>({
    url: "instance-ops/command-checks",
    method: "get"
  })
}

/** 执行命令检测（M8） */
export function runCommandCheckApi(data: {
  machineIps?: string
  podIp?: string
  command: string
}) {
  return request<ApiResponseData<string>>({
    url: "instance-ops/command-checks",
    method: "post",
    data
  })
}

/** 命令检测异常详情（M8） */
export function getCommandCheckDetailApi(uuid: string) {
  return request<CommandCheckDetailResponseData>({
    url: `instance-ops/command-checks/${uuid}`,
    method: "get"
  })
}

/** 重启记录列表（M8） */
export function getRestartRecordListApi(params: {
  appId?: number | string
  pageNo?: number
  pageSize?: number
}) {
  return request<RestartRecordPageResponseData>({
    url: "instance-ops/restart-records",
    method: "get",
    params
  })
}

/** 停止滚动重启（M8） */
export function stopRestartApi(appId: number) {
  return request<ApiResponseData<string>>({
    url: `instance-ops/restart-records/${appId}/stop`,
    method: "post"
  })
}

/** server 统计列表（M2） */
export function getServerStatAppsApi(params?: { searchDate?: string, appId?: number | string }) {
  return request<ServerStatAppsResponseData>({
    url: "stats/server/apps",
    method: "get",
    params
  })
}

/** 发送异常集群日报（M2） */
export function sendServerStatDailyEmailApi(searchDate?: string) {
  return request<ApiResponseData<null>>({
    url: "stats/server/send-daily-email",
    method: "post",
    params: searchDate ? { searchDate } : undefined
  })
}

/** 拓扑诊断更新（M2） */
export function updateServerTopologyApi() {
  return request<ApiResponseData<null>>({
    url: "stats/server/topology-update",
    method: "post"
  })
}

/** 用户列表（M7） */
export function getUserListApi(searchChName?: string) {
  return request<UserListResponseData>({
    url: "users",
    method: "get",
    params: searchChName ? { searchChName } : undefined
  })
}

export function saveUserApi(data: Record<string, unknown>) {
  return request<ApiResponseData<null>>({ url: "users", method: "post", data })
}

export function deleteUserApi(userId: number) {
  return request<ApiResponseData<null>>({ url: `users/${userId}`, method: "delete" })
}

export function resetUserPasswordApi(userId: number) {
  return request<ApiResponseData<null>>({ url: `users/${userId}/reset-password`, method: "post" })
}

export function updateUserPasswordApi(userId: number, password: string) {
  return request<ApiResponseData<null>>({
    url: `users/${userId}/password`,
    method: "put",
    data: { password }
  })
}

/** 调度任务（M15） */
export function getQuartzJobListApi(query?: string) {
  return request<QuartzJobListResponseData>({
    url: "quartz/jobs",
    method: "get",
    params: query ? { query } : undefined
  })
}

export function pauseQuartzJobApi(triggerName: string, triggerGroup: string) {
  return request<ApiResponseData<null>>({
    url: "quartz/jobs/pause",
    method: "post",
    params: { triggerName, triggerGroup }
  })
}

export function resumeQuartzJobApi(triggerName: string, triggerGroup: string) {
  return request<ApiResponseData<null>>({
    url: "quartz/jobs/resume",
    method: "post",
    params: { triggerName, triggerGroup }
  })
}

export function deleteQuartzJobApi(triggerName: string, triggerGroup: string) {
  return request<ApiResponseData<null>>({
    url: "quartz/jobs",
    method: "delete",
    params: { triggerName, triggerGroup }
  })
}

/** 节点管理（M4） */
export async function getExternalRedisListApi(params?: {
  ip?: string
  status?: number
  includeSentinel?: boolean
  pageNo?: number
  pageSize?: number
}) {
  const pageNo = Math.max(params?.pageNo ?? 1, 1)
  const pageSize = params?.pageSize && params.pageSize > 0 ? params.pageSize : 20
  const res = await request<ApiResponseData<ExternalNodeListPage | ExternalRedisNode[]>>({
    url: "external-redis",
    method: "get",
    params: { ...params, pageNo, pageSize }
  })
  const payload = res.data
  if (Array.isArray(payload)) {
    const totalCount = payload.length
    const start = (pageNo - 1) * pageSize
    const items = payload.slice(start, start + pageSize)
    return {
      ...res,
      data: {
        items,
        pageNo,
        pageSize,
        totalCount,
        totalPages: pageSize > 0 ? Math.ceil(totalCount / pageSize) : 0
      }
    } as ExternalRedisListResponseData
  }
  return res as ExternalRedisListResponseData
}
export function getExternalRedisCreateFormApi() {
  return request<ExternalRedisCreateFormResponseData>({ url: "external-redis/create-form", method: "get" })
}
export function checkExternalRedisApi(data: Record<string, unknown>) {
  return request<ApiResponseData<{ message: string, redisVersion: string }>>({ url: "external-redis/check", method: "post", data })
}
export function saveExternalRedisApi(data: Record<string, unknown>) {
  return request<ApiResponseData<null>>({ url: "external-redis", method: "post", data })
}
export function checkExternalRedisNameApi(name: string) {
  return request<ApiResponseData<null>>({ url: "external-redis/check-name", method: "get", params: { name } })
}
/** 为已纳管集群追加节点，每行 ip:port（sentinel 为 ip:port:masterName） */
export function addExternalRedisInstancesApi(appId: number, appInstanceInfo: string) {
  return request<ApiResponseData<{ message: string }>>({
    url: `external-redis/${appId}/instances`,
    method: "post",
    data: { appInstanceInfo }
  })
}

/** 把节点从平台移除（不向 Redis 下发任何命令，真实集群拓扑不变） */
export function removeExternalRedisInstanceApi(appId: number, instanceId: number) {
  return request<ApiResponseData<{ message: string }>>({
    url: `external-redis/${appId}/instances/${instanceId}`,
    method: "delete"
  })
}

export function repairExternalRedisApi(appId: number) {
  return request<ApiResponseData<{ message: string }>>({
    url: `external-redis/${appId}/repair-instances`,
    method: "post"
  })
}

/** 报警配置（M11） */
export function getInstanceAlertPageApi() {
  return request<InstanceAlertPageResponseData>({ url: "instance-alerts", method: "get" })
}
export function addInstanceAlertApi(data: Record<string, unknown>) {
  return request<ApiResponseData<null>>({ url: "instance-alerts", method: "post", data })
}
export function addAppInstanceAlertApi(data: Record<string, unknown>) {
  return request<ApiResponseData<null>>({ url: "instance-alerts/app", method: "post", data })
}
export function updateInstanceAlertApi(id: number, params: Record<string, unknown>) {
  return request<ApiResponseData<null>>({ url: `instance-alerts/${id}`, method: "put", params })
}
export function deleteInstanceAlertApi(id: number) {
  return request<ApiResponseData<null>>({ url: `instance-alerts/${id}`, method: "delete" })
}

/** 任务流详情：任务管理页面已下线，仅「键值分析」用它查分析任务进度 */
export function getTaskFlowApi(taskId: number) {
  return request<TaskFlowDetailResponseData>({ url: `tasks/${taskId}`, method: "get" })
}

/** 数据迁移（M9） */
export function getMigrateListApi(params?: Record<string, unknown>) {
  return request<MigrateListPageResponseData>({ url: "migrates", method: "get", params })
}
export function getMigrateInitApi(importId?: number) {
  return request<MigrateInitResponseData>({
    url: "migrates/init",
    method: "get",
    params: importId ? { importId } : undefined
  })
}
export function getMigrateAppInstancesApi(appId: number, migrateTool = 0) {
  return request<MigrateAppInstancesResponseData>({
    url: "migrates/app-instances",
    method: "get",
    params: { appId, migrateTool }
  })
}
export function checkMigrateApi(data: Record<string, unknown>) {
  return request<MigrateActionResultResponseData>({ url: "migrates/check", method: "post", data })
}
export function startMigrateApi(data: Record<string, unknown>) {
  return request<MigrateActionResultResponseData>({ url: "migrates/start", method: "post", data })
}
export function stopMigrateApi(id: number, migrateTool = 0) {
  return request<MigrateActionResultResponseData>({
    url: `migrates/${id}/stop`,
    method: "post",
    params: { migrateTool }
  })
}
export function resyncMigrateApi(id: number) {
  return request<MigrateActionResultResponseData>({ url: `migrates/${id}/resync`, method: "post" })
}
export function deleteMigrateApi(id: number) {
  return request<ApiResponseData<null>>({ url: `migrates/${id}`, method: "delete" })
}
export function compareMigrateKeyCountApi(id: number) {
  return request<ApiResponseData<{ sourceKeyCount: number, targetKeyCount: number, difference: number, consistent: boolean, running: boolean }>>({
    url: `migrates/${id}/key-count-compare`,
    method: "get"
  })
}
export function getMigrateLogApi(id: number, pageSize = 100) {
  return request<MigrateTextResponseData>({
    url: `migrates/${id}/log`,
    method: "get",
    params: { pageSize }
  })
}
export function getMigrateConfigApi(id: number) {
  return request<MigrateTextResponseData>({ url: `migrates/${id}/config`, method: "get" })
}
export function getMigrateProcessApi(id: number, migrateTool = 0) {
  return request<ApiResponseData<Record<string, any>>>({
    url: `migrates/${id}/process`,
    method: "get",
    params: { migrateTool }
  })
}

/** 集群运维 */
export function getAppOpsInstancesApi(appId: number) {
  return request<AppOpsInstancePageResponseData>({ url: `apps/${appId}/ops/instances`, method: "get" })
}
export function getAppOpsMachinesApi(appId: number) {
  return request<AppOpsMachineListResponseData>({ url: `apps/${appId}/ops/machines`, method: "get" })
}
export function getAppPasswordApi(appId: number) {
  return request<AppPasswordResponseData>({ url: `apps/${appId}/ops/password`, method: "get" })
}
export function getAppConfigConsistencyApi(appId: number) {
  return request<AppConfigConsistencyResponseData>({
    url: `apps/${appId}/ops/config-consistency`,
    method: "get"
  })
}
export function updateAppPasswordApi(appId: number, password: string) {
  return request<ApiResponseData<{ success: boolean }>>({
    url: `apps/${appId}/ops/password`,
    method: "post",
    data: { password }
  })
}
export function checkAppPasswordApi(appId: number) {
  return request<ApiResponseData<{ success: boolean }>>({
    url: `apps/${appId}/ops/password/check`,
    method: "get"
  })
}
export function startInstanceOpsApi(appId: number, instanceId: number) {
  return request<OpsActionResultResponseData>({
    url: `apps/${appId}/ops/instances/${instanceId}/start`,
    method: "post"
  })
}
export function shutdownInstanceOpsApi(appId: number, instanceId: number) {
  return request<OpsActionResultResponseData>({
    url: `apps/${appId}/ops/instances/${instanceId}/shutdown`,
    method: "post"
  })
}
export function forgetInstanceOpsApi(appId: number, instanceId: number) {
  return request<OpsActionResultResponseData>({
    url: `apps/${appId}/ops/instances/${instanceId}/forget`,
    method: "post"
  })
}
export function getTopologyExamApi(appId: number, refresh = false) {
  return request<TopologyExamResponseData>({
    url: `apps/${appId}/ops/topology-exam`,
    method: "get",
    params: refresh ? { refresh: 1 } : undefined
  })
}
export function runFaultDiagnosticApi(appId: number, scenario?: string) {
  return request<FaultDiagnosticReportResponseData>({
    url: `apps/${appId}/ops/fault-diagnostic/run`,
    method: "post",
    params: scenario ? { scenario } : undefined
  })
}
export function getFaultDiagnosticHistoryApi(appId: number, limit = 10) {
  return request<FaultDiagnosticHistoryResponseData>({
    url: `apps/${appId}/ops/fault-diagnostic/history`,
    method: "get",
    params: { limit }
  })
}
export function clusterDelNodeApi(appId: number, instanceId: number) {
  return request<OpsActionResultResponseData>({
    url: `apps/${appId}/ops/cluster-del-node`,
    method: "post",
    params: { instanceId }
  })
}
export function addSlaveApi(appId: number, masterInstanceId: number, slaveHost: string) {
  return request<OpsActionResultResponseData>({
    url: `apps/${appId}/ops/add-slave`,
    method: "post",
    params: { masterInstanceId, slaveHost }
  })
}
export function clusterSlaveFailoverApi(appId: number, slaveInstanceId: number, failoverParam?: string) {
  return request<OpsActionResultResponseData>({
    url: `apps/${appId}/ops/cluster-slave-failover`,
    method: "post",
    params: { slaveInstanceId, ...(failoverParam ? { failoverParam } : {}) }
  })
}
export function sentinelFailoverApi(appId: number, slaveInstanceId = 0, failoverParam?: string) {
  return request<OpsActionResultResponseData>({
    url: `apps/${appId}/ops/sentinel-failover`,
    method: "post",
    params: {
      slaveInstanceId,
      ...(failoverParam ? { failoverParam } : {})
    }
  })
}
export function scrollRestartApi(appId: number, data: AppScrollRestartRequest) {
  return request<ApiResponseData<{ message: string }>>({
    url: `apps/${appId}/ops/scroll-restart`,
    method: "post",
    data
  })
}
export function updateConfigRestartApi(appId: number, data: AppScrollRestartRequest) {
  return request<ApiResponseData<Record<string, unknown>>>({
    url: `apps/${appId}/ops/update-config-restart`,
    method: "post",
    data
  })
}

/** 集群详情 Tab 数据 */
export function getAppDetailPanelApi(appId: number) {
  return request<AppDetailPanelResponseData>({ url: `apps/${appId}/detail-panel`, method: "get" })
}
export function updateAppDetailInfoApi(appId: number, data: { appName: string, intro: string, officerId: string }) {
  return request<ApiResponseData<null>>({ url: `apps/${appId}/detail-panel/app-info`, method: "put", data })
}
export function updateAppAlertConfigApi(appId: number, data: {
  isAccessMonitor: number
}) {
  return request<ApiResponseData<null>>({ url: `apps/${appId}/detail-panel/alert-config`, method: "put", data })
}
export function addAppDetailUsersApi(appId: number, data: { userIds: number[] }) {
  return request<ApiResponseData<null>>({ url: `apps/${appId}/detail-panel/users`, method: "post", data })
}
export function deleteAppDetailUserApi(appId: number, userId: number) {
  return request<ApiResponseData<null>>({ url: `apps/${appId}/detail-panel/users/${userId}`, method: "delete" })
}
export function updateAppDetailUserApi(appId: number, userId: number, data: {
  name: string
  chName: string
  email: string
  mobile: string
  company: string
  isAlert: number
  type: number
}) {
  return request<ApiResponseData<null>>({ url: `apps/${appId}/detail-panel/users/${userId}`, method: "put", data })
}
export function getAppTopologyApi(appId: number, options?: { live?: boolean }) {
  return request<AppTopologyResponseData>({
    url: `apps/${appId}/topology`,
    method: "get",
    params: options?.live ? { live: true } : undefined
  })
}
export function getAppClientListApi(appId: number, condition = 3, includeDetails = false) {
  return request<AppClientListResponseData>({
    url: `apps/${appId}/clients`,
    method: "get",
    params: { condition, includeDetails }
  })
}

export function getAppStatOverviewApi(appId: number, params?: { startDate?: string, endDate?: string, includeClimax?: boolean }) {
  return request<AppStatOverviewResponseData>({
    url: `apps/${appId}/stat-overview`,
    method: "get",
    params
  })
}

export function getAppStatClimaxApi(appId: number, params?: { startDate?: string, endDate?: string }) {
  return request<ApiResponseData<AppCommandClimax[]>>({
    url: `apps/${appId}/stat-climax`,
    method: "get",
    params
  })
}

export function getAppStatChartApi(appId: number, params?: { statName?: string, startDate?: string, endDate?: string }) {
  return request<ChartPointListResponseData>({
    url: `apps/${appId}/charts/app-stats`,
    method: "get",
    params
  })
}

export function getAppStatChartsBatchApi(appId: number, params: { statName: string, startDate?: string, endDate?: string }) {
  return request<AppStatChartsBatchResponseData>({
    url: `apps/${appId}/charts/app-stats/batch`,
    method: "get",
    params
  })
}

export function getAppOpsStatChartsApi(appId: number, params: { statName: string, startDate?: string, endDate?: string }) {
  return request<AppStatChartsBatchResponseData>({
    url: `apps/${appId}/charts/app-ops-stats`,
    method: "get",
    params
  })
}

export function getAppMachineTopologyApi(appId: number) {
  return request<AppMachineTopologyResponseData>({ url: `apps/${appId}/machine-topology`, method: "get" })
}

export function getAppCommandNamesApi(appId: number, params?: { startDate?: string, endDate?: string }) {
  return request<ApiResponseData<string[]>>({
    url: `apps/${appId}/charts/command-names`,
    method: "get",
    params
  })
}

export function getAppCommandChartsBatchApi(
  appId: number,
  params: { commands: string, startDate?: string, endDate?: string }
) {
  return request<AppStatChartsBatchResponseData>({
    url: `apps/${appId}/charts/commands/batch`,
    method: "get",
    params
  })
}

export function getAppCommandChartApi(appId: number, params?: { commandName?: string, startDate?: string, endDate?: string }) {
  return request<ChartPointListResponseData>({
    url: `apps/${appId}/charts/commands`,
    method: "get",
    params
  })
}

export function getAppTop5CommandsChartApi(appId: number, params?: { startDate?: string, endDate?: string }) {
  return request<ChartPointListResponseData>({
    url: `apps/${appId}/charts/top5-commands`,
    method: "get",
    params
  })
}

export function getAppDailyApi(appId: number, dailyDate?: string) {
  return request<AppDailyResponseData>({
    url: `apps/${appId}/daily`,
    method: "get",
    params: dailyDate ? { dailyDate } : undefined
  })
}

export function executeAppCommandApi(appId: number, data: { command: string }) {
  return request<ApiResponseData<{ result: string }>>({
    url: `apps/${appId}/commands/execute`,
    method: "post",
    data
  })
}

export function getWikiClientAccessApi() {
  return request<WikiContentResponseData>({ url: "wiki/access/client", method: "get" })
}

export function getAppLatencyApi(appId: number, params?: { startDate?: string, endDate?: string, minCostMillis?: number }) {
  return request<AppLatencyResponseData>({
    url: `apps/${appId}/latency`,
    method: "get",
    params
  })
}

/** 清理前预估：早于「保留 keepDays 天」的平台侧慢查询记录条数 */
export function countCleanableSlowLogsApi(appId: number, keepDays: number) {
  return request<ApiResponseData<{ count: number }>>({
    url: `apps/${appId}/slow-logs/cleanable`,
    method: "get",
    params: { keepDays }
  })
}

/** 清理平台侧慢查询记录（不影响 Redis 自身的 SLOWLOG） */
export function cleanAppSlowLogsApi(appId: number, keepDays: number) {
  return request<ApiResponseData<{ deleted: number }>>({
    url: `apps/${appId}/slow-logs`,
    method: "delete",
    params: { keepDays }
  })
}

export function getKeyAnalysisPageApi(appId: number) {
  return request<KeyAnalysisPageResponseData>({ url: `apps/${appId}/key-analysis`, method: "get" })
}

export function startKeyAnalysisApi(appId: number, data: {
  reason?: string
  nodeInfo?: string
  confirmEmpty?: boolean
  bigKeyStringBytes?: number
  bigKeyCollectionElements?: number
}) {
  return request<KeyAnalysisStartResultResponseData>({
    url: `apps/${appId}/key-analysis/start`,
    method: "post",
    data
  })
}

export function getKeyAnalysisProgressApi(appId: number, taskId: number) {
  return request<KeyAnalysisProgressResponseData>({
    url: `apps/${appId}/key-analysis/progress`,
    method: "get",
    params: { taskId }
  })
}

/** 离线数据分析（上传 RDB 解析） */
export function getOfflineAnalysisListApi(params?: { pageNo?: number, pageSize?: number }) {
  return request<OfflineAnalysisPageResponseData>({
    url: "offline-analysis",
    method: "get",
    params: { pageNo: params?.pageNo ?? 1, pageSize: params?.pageSize ?? 20 }
  })
}

export function uploadOfflineAnalysisApi(file: File, onProgress?: (percent: number) => void) {
  const form = new FormData()
  form.append("file", file)
  return request<OfflineAnalysisRecordResponseData>({
    url: "offline-analysis",
    method: "post",
    data: form,
    // 大文件上传耗时远超默认超时
    timeout: 30 * 60 * 1000,
    onUploadProgress: (event: { loaded: number, total?: number }) => {
      if (onProgress && event.total) {
        onProgress(Math.round(event.loaded * 100 / event.total))
      }
    }
  })
}

/** 结果结构与键值分析一致，直接喂给同一个报告组件 */
export function getOfflineAnalysisResultApi(id: number) {
  return request<KeyAnalysisResultResponseData>({
    url: `offline-analysis/${id}/result`,
    method: "get"
  })
}

export function deleteOfflineAnalysisApi(id: number) {
  return request<ApiResponseData<null>>({
    url: `offline-analysis/${id}`,
    method: "delete"
  })
}

export function getKeyAnalysisResultApi(appId: number, auditId: number) {
  return request<KeyAnalysisResultResponseData>({
    url: `apps/${appId}/key-analysis/${auditId}/result`,
    method: "get"
  })
}

/** 数据管理诊断（M5） */
export function getDiagnosticAppsApi() {
  return request<DiagnosticAppListResponseData>({ url: "diagnostics/apps", method: "get" })
}

export function getDiagnosticInstancesApi(appId: number) {
  return request<DiagnosticInstanceListResponseData>({
    url: `diagnostics/apps/${appId}/instances`,
    method: "get"
  })
}

export function getDiagnosticTasksApi(params?: Record<string, unknown>) {
  return request<DiagnosticTaskListResponseData>({ url: "diagnostics/tasks", method: "get", params })
}

export function submitDiagnosticTaskApi(data: Record<string, unknown>) {
  return request<ApiResponseData<{ taskId: number }>>({ url: "diagnostics/tasks", method: "post", data })
}

export function deleteDiagnosticTaskApi(recordId: number) {
  return request<ApiResponseData<void>>({ url: `diagnostics/tasks/${recordId}`, method: "delete" })
}

export function confirmDiagnosticDeleteApi(recordId: number) {
  return request<ApiResponseData<{ taskId: number }>>({
    url: `diagnostics/tasks/${recordId}/delete-keys`,
    method: "post"
  })
}

export function getDiagnosticResultApi(redisKey: string, type: number, err = false) {
  return request<DiagnosticResultResponseData>({
    url: "diagnostics/tasks/data",
    method: "get",
    params: { redisKey, type, err }
  })
}

export function runOnlineVerifyApi(data: { servers: string, verifyType: string }) {
  return request<OnlineHealthCheckResponseData>({ url: "diagnostics/online-verify", method: "post", data })
}

export function executeDiagnosticCommandApi(data: { appId: number, node: string, command: string, timeout?: number }) {
  return request<ApiResponseData<{ result: string }>>({ url: "diagnostics/command", method: "post", data })
}

/** 全局统计首屏（轻量） */
export function getDashboardOpsApi() {
  return request<DashboardOpsResponseData>({ url: "dashboard/ops", method: "get" })
}

export function getDashboardOverviewApi() {
  return request<DashboardOverviewResponseData>({
    url: "dashboard/overview",
    method: "get"
  })
}

/** 全局统计详情（异常 + 图表，较重） */
export function getDashboardDetailsApi() {
  return request<DashboardDetailsResponseData>({
    url: "dashboard/details",
    method: "get"
  })
}

/** 全局统计完整数据（兼容） */
export function getDashboardApi() {
  return request<DashboardResponseData>({
    url: "dashboard",
    method: "get"
  })
}

/** 压测工具 */
export function getBenchmarkCommandsApi() {
  return request<ApiResponseData<Record<string, { name: string, kind: string }[]>>>({
    url: "benchmark/commands",
    method: "get"
  })
}
export function getBenchmarkTargetsApi(appId: number) {
  return request<ApiResponseData<{ hostPort: string, slotRange: string, slotCount: number }[]>>({
    url: "benchmark/targets",
    method: "get",
    params: { appId }
  })
}
export function startBenchmarkApi(data: Record<string, unknown>) {
  return request<ApiResponseData<{ taskId: number }>>({ url: "benchmark/start", method: "post", data })
}
export function stopBenchmarkApi(taskId: number) {
  return request<ApiResponseData<null>>({ url: `benchmark/${taskId}/stop`, method: "post" })
}
export function getBenchmarkProgressApi(taskId: number) {
  return request<ApiResponseData<BenchmarkProgress>>({ url: `benchmark/${taskId}/progress`, method: "get" })
}
export function deleteBenchmarkApi(taskId: number) {
  return request<ApiResponseData<null>>({ url: `benchmark/${taskId}`, method: "delete" })
}
export function getBenchmarkListApi(params?: Record<string, unknown>) {
  return request<ApiResponseData<{ items: BenchmarkResult[], totalCount: number }>>({
    url: "benchmark",
    method: "get",
    params
  })
}

/** 节点详情 */
export function getInstanceDetailApi(instanceId: number, params?: { appId?: number, fromNode?: boolean }) {
  return request<InstanceDetailResponseData>({
    url: `instances/${instanceId}`,
    method: "get",
    params
  })
}
export function getInstanceStatApi(instanceId: number, params?: { startDate?: string, endDate?: string }) {
  return request<InstanceStatResponseData>({
    url: `instances/${instanceId}/stat`,
    method: "get",
    params
  })
}

export function deleteKeyAnalysisRecordApi(appId: number, auditId: number) {
  return request<ApiResponseData<null>>({
    url: `apps/${appId}/key-analysis/${auditId}`,
    method: "delete"
  })
}
export function getInstanceStatChartsApi(instanceId: number, params: {
  statName: string
  startDate?: string
  endDate?: string
}) {
  return request<AppStatChartsBatchResponseData>({
    url: `instances/${instanceId}/stat-charts`,
    method: "get",
    params
  })
}
export function getInstanceLogApi(instanceId: number, pageSize = 100) {
  return request<InstanceLogResponseData>({
    url: `instances/${instanceId}/log`,
    method: "get",
    params: { pageSize }
  })
}
/** 旧版 JSP 表单接口：须 x-www-form-urlencoded + Cookie 会话 */
function legacyFormPost<T>(url: string, params: Record<string, string | number | undefined>) {
  const body = new URLSearchParams()
  Object.entries(params).forEach(([key, value]) => {
    if (value !== undefined && value !== null) body.append(key, String(value))
  })
  const token = getToken()
  return axios.post<T>(url, body, {
    headers: {
      "Content-Type": "application/x-www-form-urlencoded",
      ...(token ? { Authorization: `Bearer ${token}` } : {})
    },
    withCredentials: true
  })
}

export function getInstanceConfigApi(instanceId: number) {
  return request<InstanceConfigResponseData>({
    url: `instances/${instanceId}/config`,
    method: "get"
  })
}

export function updateInstanceConfigApi(instanceId: number, data: { configName: string, configValue: string }) {
  return request<InstanceConfigUpdateResponseData>({
    url: `instances/${instanceId}/config`,
    method: "put",
    data
  })
}

/** 与旧版 appOps 一致：从 Redis CONFIG GET 拉取完整配置项列表 */
export function getAppRedisConfigApi(appId: number, instanceId: number) {
  return legacyFormPost<{
    status?: number | string
    redisConfigMap?: Record<string, string>
    message?: string
  }>("/admin/app/redisConfig", { appId, instanceId })
}

export function addInstanceConfigChangeApi(data: {
  appId: number
  host: string
  port: number
  instanceConfigKey: string
  instanceConfigValue: string
}) {
  return legacyFormPost<{ status?: number | string, message?: string }>(
    "/manage/instance/addInstanceConfigChange",
    data
  )
}

export function formatRedisConfigOptionLabel(key: string, value: string) {
  return `配置项：${key} | 配置值：${value}`
}
export function getInstanceClientsApi(
  instanceId: number,
  condition = 3,
  options?: {
    includeRaw?: boolean
    includeDetails?: boolean
    addr?: string
    detailLimit?: number
  }
) {
  return request<InstanceClientListResponseData>({
    url: `instances/${instanceId}/clients`,
    method: "get",
    params: {
      condition,
      includeRaw: options?.includeRaw ?? false,
      includeDetails: options?.includeDetails ?? false,
      addr: options?.addr,
      detailLimit: options?.detailLimit ?? 200
    }
  })
}
export function getInstanceFaultsApi(
  instanceId: number,
  params?: { pageNo?: number, pageSize?: number }
) {
  return request<InstanceFaultListResponseData>({
    url: `instances/${instanceId}/faults`,
    method: "get",
    params: {
      pageNo: params?.pageNo ?? 1,
      pageSize: params?.pageSize ?? 20
    }
  })
}
export function executeInstanceCommandApi(instanceId: number, command: string) {
  return request<InstanceCommandResultResponseData>({
    url: `instances/${instanceId}/command`,
    method: "post",
    data: { command }
  })
}
export function getInstanceCommandAnalysisApi(instanceId: number, params?: { startDate?: string, endDate?: string }) {
  return request<InstanceCommandAnalysisResponseData>({
    url: `instances/${instanceId}/command-analysis`,
    method: "get",
    params
  })
}
export function getInstanceCommandChartApi(instanceId: number, params: {
  commandName: string
  startDate?: string
  endDate?: string
}) {
  return request<InstanceCommandChartResponseData>({
    url: `instances/${instanceId}/command-chart`,
    method: "get",
    params
  })
}

export function getInstanceCommandNamesApi(instanceId: number, params?: { startDate?: string, endDate?: string }) {
  return request<ApiResponseData<string[]>>({
    url: `instances/${instanceId}/command-names`,
    method: "get",
    params
  })
}

export function getInstanceCommandChartsBatchApi(instanceId: number, params: {
  commands: string
  startDate?: string
  endDate?: string
}) {
  return request<AppStatChartsBatchResponseData>({
    url: `instances/${instanceId}/command-charts`,
    method: "get",
    params
  })
}

export * from "./type"

/** 审计日志：平台上的所有变更操作 */
export function getOperationAuditsApi(params: {
  userName?: string
  module?: string
  keyword?: string
  success?: number
  startTime?: string
  endTime?: string
  pageNo?: number
  pageSize?: number
}) {
  return request<OperationAuditPageResponseData>({
    url: "audits",
    method: "get",
    params
  })
}

/** 报警记录：分页查询（仅管理员） */
export function getAlertRecordsApi(params: {
  importantLevel?: number
  appId?: number
  ip?: string
  keyword?: string
  startTime?: string
  endTime?: string
  pageNo?: number
  pageSize?: number
}) {
  return request<AlertRecordPageResponseData>({
    url: "alert-records",
    method: "get",
    params
  })
}

/** 风险评估：总览清单（全部在线集群 + 各自最近一次结果） */
/** 数据模型：筛选项可选值 */
export function getDataModelOptionsApi() {
  return request<DataModelOptionsResponseData>({ url: "data-model/options", method: "get" })
}

/** 数据模型：某个主题的聚合结果。topic 与后端路径一一对应 */
export function getDataModelTopicApi(topic: string, params: Record<string, unknown>) {
  return request<DataModelResultResponseData>({ url: `data-model/${topic}`, method: "get", params })
}

/** 评估策略：各维度的判定规则 */
export function getRiskAssessRulesApi() {
  return request<RiskRulesResponseData>({ url: "risk-assess/rules", method: "get" })
}

export function getRiskAssessOverviewApi(params: {
  keyword?: string
  level?: string
  pageNo?: number
  pageSize?: number
}) {
  return request<RiskAssessOverviewResponseData>({ url: "risk-assess/overview", method: "get", params })
}

/** 触发一次评估，同步返回完整报告 */
export function runRiskAssessApi(appId: number, windowHours: number) {
  return request<RiskAssessReportResponseData>({
    url: `risk-assess/apps/${appId}`,
    method: "post",
    params: { windowHours },
    timeout: 120000
  })
}

export function getRiskAssessReportApi(reportId: number) {
  return request<RiskAssessReportResponseData>({ url: `risk-assess/reports/${reportId}`, method: "get" })
}

export function getRiskAssessHistoryApi(appId: number, limit = 10) {
  return request<RiskAssessHistoryResponseData>({
    url: `risk-assess/apps/${appId}/history`,
    method: "get",
    params: { limit }
  })
}
