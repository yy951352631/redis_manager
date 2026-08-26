/** 全局统计大盘 API 类型 */

export interface ChartItem {
  name: string
  value: number
}

export interface DashboardOverview {
  totalRunningApps: string
  totalMachineCount: string
  totalRunningInstance: string
  redisTypeCount: string
  redisVersions: ChartItem[]
  onlineClusterNames: string[]
  onlineMachineIps: string[]
  machineMemUsedGb: number
  machineMemTotalGb: number
  machineMemUsedRatio: number
  instanceMemUsedGb: number
  instanceMemTotalGb: number
  instanceMemUsedRatio: number
}

export interface DashboardMachineStats {
  machineRoom: string
  machineMemUsedGb: number
  machineMemTotalGb: number
  machineMemUsedRatio: number
  instanceMemUsedGb: number
  instanceMemTotalGb: number
  instanceMemUsedRatio: number
}

export interface DashboardQuartzStats {
  triggerTotalCount: number
  triggerWaitingCount: number
  triggerErrorCount: number
  triggerPausedCount: number
  triggerAcquiredCount: number
  triggerBlockedCount: number
  misfireCount: number
}

export interface DashboardTaskStats {
  totalTaskCount: number
  newTaskCount: number
  runningTaskCount: number
  abortTaskCount: number
  successTaskCount: number
}

export interface DashboardAbnormalApp {
  appId: number
  clusterNo?: number
  appName?: string
  typeDesc?: string
  versionName?: string
  masterNum?: number
  slaveNum?: number
  topologyAbnormal: boolean
  instanceAbnormal: boolean
  unknownStatus?: boolean
  unknownStatusDetail?: string
  instanceAbnormalCount?: number
  instanceAbnormalDetail?: string
  probeAbnormal?: boolean
  probeAbnormalDetail?: string
  slotAbnormal: boolean
  coveredSlots?: number
  totalSlots?: number
  lostSlotsCount?: number
  fetchOk?: boolean
  statusDesc?: string
  lossDetail?: string
}

export interface DashboardAbnormalOverview {
  topologySearchDate: string
  topologyAbnormalTotal: number
  slotAbnormalTotal: number
  instanceAbnormalTotal: number
  abnormalOverviewTotal: number
  hasSlotAbnormal: boolean
  hasInstanceAbnormal: boolean
  apps: DashboardAbnormalApp[]
}

export interface DashboardCharts {
  appTypeDistribute: ChartItem[]
  appMemUsageDistribute: ChartItem[]
  appShardDistribute: ChartItem[]
  instanceStatusDistribute: ChartItem[]
}

export interface DashboardData {
  overview: DashboardOverview
  abnormalOverview: DashboardAbnormalOverview
  charts: DashboardCharts
  machineStats: DashboardMachineStats[]
  quartz: DashboardQuartzStats
  tasks: DashboardTaskStats
}

export type DashboardResponseData = ApiResponseData<DashboardData>

export interface DashboardOverviewBundle {
  overview: DashboardOverview
  machineStats: DashboardMachineStats[]
  quartz: DashboardQuartzStats
  tasks: DashboardTaskStats
}

export interface DashboardDetails {
  abnormalOverview: DashboardAbnormalOverview
  charts: DashboardCharts
}

export type DashboardOverviewResponseData = ApiResponseData<DashboardOverviewBundle>
export type DashboardDetailsResponseData = ApiResponseData<DashboardDetails>

/** M3 集群管理列表 */
export interface AppListItem {
  appId: number
  clusterNo?: number
  appName: string
  typeDesc: string
  externalManaged: boolean
  sourceLabel: string
  nodeLines: string[]
  extraNodeCount: number
  extraNodeLines?: string[]
  allNodeLines?: string[]
  instanceCount: number
  matchedNodes: string[]
  runtimeStatus: number
  runtimeStatusLabel: string
  hasOfflineInstances: boolean
  versionName?: string
  mem: number
  memUsePercent: number
  highestMemFragRatio: number
  instIdWithHighestMemFragRatio: number
  hitPercent: number
  keyCount: number
  cpuUsePercent: number
  uptimeSeconds: number
  appRunDays: number
}

export interface AppListPage {
  items: AppListItem[]
  pageNo: number
  pageSize: number
  totalCount: number
  totalPages: number
  hostOpsEnabled?: boolean
}

export interface AppOfflineResult {
  appId: number
  taskId: number
  message: string
}

export type AppListResponseData = ApiResponseData<AppListPage>
export type AppOfflineResultResponseData = ApiResponseData<AppOfflineResult>

/** M3 集群详情框架 */
export interface AppDetailTab {
  key: string
  label: string
}

export interface AppDetail {
  appId: number
  clusterNo?: number
  appName: string
  intro?: string
  type: number
  typeDesc: string
  runtimeStatus: number
  runtimeStatusLabel: string
  versionName?: string
  externalManaged: boolean
  sourceLabel: string
  installModuleFlag: boolean
  instanceCount: number
  mem: number
  memUsePercent: number
  hitPercent: number
  appRunDays: number
  hasOfflineInstances: boolean
  opsEnabled: boolean
  defaultTab: string
  tabs: AppDetailTab[]
}

export type AppDetailResponseData = ApiResponseData<AppDetail>

/** M8 节点运维 */
export interface CompareTypeOption {
  type: number
  label: string
}

export interface RedisVersionOption {
  id: number
  name: string
}

export interface InstanceOpsOptions {
  redisVersions: RedisVersionOption[]
  compareTypes: CompareTypeOption[]
}

export interface ConfigCheckRecord {
  key: string
  versionId?: number
  versionName: string
  userName: string
  createTime: string
  configName: string
  compareType: number
  compareTypeLabel: string
  expectValue: string
  success: boolean
}

export interface ConfigCheckDetailRow {
  instanceId: number
  hostPort: string
  versionName: string
  appId: number
  appName: string
  createTime: string
  configName: string
  compareType: number
  compareTypeLabel: string
  expectValue: string
  realValue: string
}

export interface ConfigCheckDetail {
  key: string
  rows: ConfigCheckDetailRow[]
}

export interface CommandCheckRecord {
  key: string
  userName: string
  createTime: string
  machineIps?: string
  podIp?: string
  command: string
  success: boolean
}

export interface CommandCheckDetailRow {
  instanceId: number
  hostPort: string
  createTime: string
  command: string
  message: string
}

export interface CommandCheckDetail {
  key: string
  rows: CommandCheckDetailRow[]
}

export interface AppScrollRestartRequest {
  recordId?: number
  configFlag?: boolean
  transferFlag?: boolean
  instanceIds?: number[]
  configList?: { configName: string, configValue: string }[]
}

export interface RestartRecordInstance {
  instanceId: number
  hostPort: string
}

export interface RestartRecord {
  id: number
  userName: string
  appId?: number
  appName: string
  instances: RestartRecordInstance[]
  operateType: number
  operateTypeLabel: string
  logs: string[]
  startTime: string
  endTime: string
  status?: number
  statusLabel: string
  stoppable: boolean
}

export interface RestartRecordPage {
  items: RestartRecord[]
  pageNo: number
  pageSize: number
  totalCount: number
  totalPages: number
}

export type InstanceOpsOptionsResponseData = ApiResponseData<InstanceOpsOptions>
export type ConfigCheckListResponseData = ApiResponseData<ConfigCheckRecord[]>
export type ConfigCheckDetailResponseData = ApiResponseData<ConfigCheckDetail>
export type CommandCheckListResponseData = ApiResponseData<CommandCheckRecord[]>
export type CommandCheckDetailResponseData = ApiResponseData<CommandCheckDetail>
export type RestartRecordPageResponseData = ApiResponseData<RestartRecordPage>

/** M2 server 统计 */
export interface ServerStatAppRow {
  appId: number
  clusterNo?: number
  appName: string
  testApp: boolean
  testLabel: string
  typeDesc: string
  versionName: string
  masterNum: number
  slaveNum: number
  nodeBalanced: boolean
  shardMemGb: number
  shardSpec: string
  memTotalGb: number
  memUsedGb: number
  memUsedRssGb: number
  memUsageRatio: number
  memUsageRatioRss: number
  memUsePercent: number
  slowLogCount: number
  connectedClients: number
  objectSize: number
  usedMemoryMb: number
  usedMemoryRssMb: number
  avgMemFragRatio: number
  maxCpuSys: number
  maxCpuUser: number
  topologyExamResult?: number
  topologyExamLabel: string
}

export interface ServerStatApps {
  searchDate: string
  items: ServerStatAppRow[]
}

export type ServerStatAppsResponseData = ApiResponseData<ServerStatApps>

/** M7 用户管理 */
export interface UserListItem {
  id?: number
  name: string
  chName: string
  email: string
  mobile: string
  company?: string
  isAlert: number
  isAlertLabel: string
  type: number
  typeLabel: string
  registerTime?: string
}

export interface QuartzJob {
  triggerName: string
  triggerGroup: string
  cron: string
  nextFireDate: string
  prevFireDate: string
  startDate: string
  triggerState: string
  paused: boolean
}

export interface ExternalRedisNode {
  instanceId: number
  ip: string
  port: number
  nodeTypeDesc: string
  roleDesc: string
  appId: number
  clusterNo?: number
  appName: string
  appTypeDesc: string
  status: number
  statusDesc: string
  cmd?: string
  synced: boolean
  memMb: number
  memUsePercent: number
  usedMemGb: number
  totalMemGb: number
  keyCount: number
  cpuUsePercent: number
  uptimeSeconds: number
  memFragmentationRatio: number
  connectedClients: number
}

export type UserListResponseData = ApiResponseData<UserListItem[]>
export type QuartzJobListResponseData = ApiResponseData<QuartzJob[]>
export interface ExternalNodeListPage {
  items: ExternalRedisNode[]
  pageNo: number
  pageSize: number
  totalCount: number
  totalPages: number
}

export type ExternalRedisListResponseData = ApiResponseData<ExternalNodeListPage>

export interface SelectOption {
  id: number
  name: string
  label?: string
}
export interface ExternalRedisCreateForm {
  defaultVersionId: number
  currentUserId: number
  users: SelectOption[]
  versions: SelectOption[]
}
export interface OpsActionResult {
  success: boolean
  message?: string
  status: number
}
export interface FaultDiagnosticCheck {
  code?: string
  name?: string
  group?: string
  level?: string
  summary?: string
  detail?: string
  raw?: string
  skipped?: boolean
  skipReason?: string
  order?: number
}
export interface FaultDiagnosticReport {
  reportId: string
  appId: number
  appName?: string
  appTypeDesc?: string
  scenario?: string
  scenarioLabel?: string
  durationMs?: number
  createTime?: string | number
  passCount: number
  warnCount: number
  failCount: number
  skipCount: number
  totalCount: number
  sshCapable?: boolean
  externalManaged?: boolean
  checks?: FaultDiagnosticCheck[]
  aiSummary?: string
}
export type ExternalRedisCreateFormResponseData = ApiResponseData<ExternalRedisCreateForm>
export type OpsActionResultResponseData = ApiResponseData<OpsActionResult>
export type TopologyExamResponseData = ApiResponseData<TopologyExam>
export interface TopologyExamTip {
  appId?: string
  type?: string
  status?: string
  desc?: string
}
export interface TopologyExamDiagItem {
  no: number
  title: string
  ok: boolean
  statusText: string
  conclusion: string
}
export interface TopologyExamSlotRow {
  hostPort: string
  slotRanges: string
  slotCount: number
  percent: string
}
export interface TopologyExamSlotLossRow {
  hostPort: string
  segments: string
}
export interface TopologyExamSlot {
  totalSlots: number
  coveredSlots: number
  lostSlotsCount: number
  fetchOk: boolean
  slotComplete: boolean
  imbalance: boolean
  zeroSlotMaster: boolean
  imbalanceRatio: string
  minSlotCount: number
  maxSlotCount: number
  masterCount: number
  ok: boolean
  lossRows?: TopologyExamSlotLossRow[]
  distributionRows: TopologyExamSlotRow[]
}
export interface TopologyExamInstanceItem {
  id: number
  ip: string
  port: number
  hostPort: string
  roleDesc: string
  realIp: string
}
export interface TopologyExamMasterSlaveGroup {
  master: TopologyExamInstanceItem
  slaves: TopologyExamInstanceItem[]
  samePhysicalMachine?: "yes" | "no" | "no_slave"
}
export interface TopologyExamMachineGroup {
  realIp: string
  room?: string
  rack?: string
  instanceCount: number
  instances: TopologyExamInstanceItem[]
}
export interface TopologyExam {
  appId: number
  clusterNo?: number
  appName: string
  appType: number
  typeDesc: string
  overallOk: boolean
  issueCount: number
  clusterOrSentinel: boolean
  diagPass: number
  diagTotal: number
  slaveNum: number
  masterCount: number
  sentinelCount: number
  machineGroupCount: number
  msFlag: boolean
  sameNetSegment: boolean
  failoverOk?: boolean | null
  diagnostics: TopologyExamDiagItem[]
  tips: TopologyExamTip[]
  slotExam?: TopologyExamSlot
  sentinels: TopologyExamInstanceItem[]
  masterSlaves: TopologyExamMasterSlaveGroup[]
  machines: TopologyExamMachineGroup[]
}
export type FaultDiagnosticReportResponseData = ApiResponseData<FaultDiagnosticReport>
export type FaultDiagnosticHistoryResponseData = ApiResponseData<FaultDiagnosticReport[]>

/** 报警配置 */
export interface AlertConfigOption { value: string, info: string }
export interface EnumOption { value: number, label: string }
export interface InstanceAlertItem {
  id: number
  alertConfig: string
  alertValue: string
  compareType: number
  compareInfo?: string
  configInfo?: string
  type: number
  instanceId: number
  instanceHostPort?: string
  checkCycle: number
  importantLevel?: number
  updateTime?: string
  lastCheckTime?: string
}
export interface InstanceAlertPage {
  checkCycles: EnumOption[]
  compareTypes: EnumOption[]
  alertConfigs: AlertConfigOption[]
  usedGlobalConfigs: AlertConfigOption[]
  globalAlerts: InstanceAlertItem[]
  specialAlerts: InstanceAlertItem[]
}

/** 任务管理 */
export interface TaskListItem {
  id: number
  appId: number
  clusterNo?: number
  className: string
  status: number
  statusDesc: string
  progress: string
  progressValue: number
  createTime?: string
  startTime?: string
  endTime?: string
  costSeconds?: string
  executeIpPort?: string
  finished: boolean
  running: boolean
}
export interface RiskAssessOverviewItem {
  appId: number
  appName: string
  typeDesc?: string
  versionName?: string
  instanceCount: number
  reportId?: number | null
  level: string
  levelLabel: string
  score?: number | null
  windowHours?: number | null
  evaluatedDimensions?: number | null
  totalDimensions?: number | null
  lastAssessTime?: string | null
}
export interface RiskAssessOverview {
  items: RiskAssessOverviewItem[]
  pageNo: number
  pageSize: number
  totalCount: number
  totalPages: number
  levelCount: Record<string, number>
}
export interface RiskAssessDimensionItem {
  dimension: string
  dimensionName: string
  level: string
  levelLabel: string
  actualValue?: number | null
  threshold?: number | null
  instanceId?: number | null
  instanceHostPort?: string | null
  sampleCount?: number | null
  sampleWindow?: string | null
  summary?: string
  suggestion?: string | null
  evidence?: string | null
}
export interface RiskAssessReport {
  reportId: number
  appId: number
  appName: string
  windowHours: number
  windowStart?: string
  windowEnd?: string
  level: string
  levelLabel: string
  score: number
  evaluatedDimensions: number
  totalDimensions: number
  instanceCount: number
  collectedInstanceCount: number
  operator?: string
  costMs: number
  createTime?: string
  dimensions: RiskAssessDimensionItem[]
}
export interface OperationAuditItem {
  id: number
  userName: string
  module: string
  httpMethod: string
  requestUri: string
  handler?: string
  appId?: number | null
  appName?: string | null
  instanceId?: number | null
  params?: string
  clientIp?: string
  statusCode?: number
  success: boolean
  errorMsg?: string | null
  costMs?: number
  createTime?: string
}
export interface OperationAuditPage {
  items: OperationAuditItem[]
  pageNo: number
  pageSize: number
  totalCount: number
  totalPages: number
  modules: string[]
}
export interface AlertRecordItem {
  id: number
  createTime?: string
  importantLevel: number
  importantLevelDesc: string
  appId?: number | null
  appName?: string | null
  instanceId?: number | null
  ip?: string | null
  port?: number | null
  title?: string
  content?: string
}
export interface AlertRecordPage {
  items: AlertRecordItem[]
  pageNo: number
  pageSize: number
  totalCount: number
  totalPages: number
  importantLevels: { value: number, label: string }[]
  apps: { appId: number, appName: string }[]
}
export interface TaskListPage {
  items: TaskListItem[]
  pageNo: number
  pageSize: number
  totalCount: number
  totalPages: number
}
export interface TaskFlowStep {
  id: number
  stepName: string
  orderNo: number
  status: number
  statusDesc: string
  startTime?: string
  endTime?: string
  logs: string[]
}
export interface TaskFlowDetail {
  taskId: number
  appId: number
  appName: string
  className: string
  status: number
  statusDesc: string
  progress: string
  progressValue: number
  prettyParam?: string
  currentStep?: string
  steps: TaskFlowStep[]
}

/** 数据迁移 */
export interface MigrateListItem {
  id: number
  migrateId: string
  migrateTool: number
  migrateMachineIp?: string
  sourceAppId: number
  targetAppId: number
  sourceAppName?: string
  targetAppName?: string
  sourceServers?: string
  targetServers?: string
  userName?: string
  status: number
  statusDesc: string
  startTime?: string
  endTime?: string
  migrateToolLabel?: string
  migrateMachine?: string
  sourceMigrateTypeDesc?: string
  targetMigrateTypeDesc?: string
  redisSourceVersion?: string
  redisTargetVersion?: string
}
export interface MigrateListPage {
  items: MigrateListItem[]
  pageNo: number
  pageSize: number
  totalCount: number
  totalPages: number
}
export interface MigrateMachineOption { ip: string, runningCount: number }
export interface MigrateToolOption { id: number, name: string, intro?: string }
export interface MigrateAppOption { value: number, label: string, appType: number }
export interface MigrateInit {
  machines: MigrateMachineOption[]
  tools: MigrateToolOption[]
  apps: MigrateAppOption[]
  importId?: number
  targetAppId?: number
  sourceServers?: string
  redisSourcePass?: string
  sourceType?: number
  sourceDataType?: number
  redisSourceVersion?: string
}
export interface MigrateActionResult { status: number, message?: string, migrateId?: string }
export interface MigrateAppInstances {
  instances: string
  password: string
  appType: number
  appName: string
  redisVersion: string
}
export interface MigrateText { lines: string[] }
export type MigrateInitResponseData = ApiResponseData<MigrateInit>
export type MigrateActionResultResponseData = ApiResponseData<MigrateActionResult>
export type MigrateAppInstancesResponseData = ApiResponseData<MigrateAppInstances>
export type MigrateTextResponseData = ApiResponseData<MigrateText>

/** 集群运维 */
export interface AppOpsMachineItem {
  ip: string
  realIp?: string
  roomName?: string
  machineMemory: number
  usedMemory: number
  memoryTotal?: number
  memoryFree?: number
  memoryAllocated?: number
  memoryUsageRatio?: string
  memoryAllocatedRatio?: string
  cpuUsage?: string
  traffic?: string
  load?: string
  modifyTime?: string
  virtual?: boolean
  instanceCount?: number
}
export interface AppOpsInstancePage {
  appId: number
  clusterNo?: number
  appType: number
  appName: string
  sentinelApp: boolean
  hostOpsEnabled: boolean
  sshCapable?: boolean
  externalManaged?: boolean
  managedMachineCount?: number
  k8sIps?: string[]
  instances: AppTopologyInstance[]
  lossSlotsSegmentMap?: Record<string, string>
}
export interface AppPasswordInfo {
  appId: number
  appPassword?: string
  customPassword?: string
}
export interface AppConfigConsistencyNode {
  instanceId: number
  hostPort: string
  group: string
  role: string
  success: boolean
  configCount: number
  message?: string
}
export interface AppConfigConsistencyItem {
  group: string
  configName: string
  consistent: boolean
  sensitive: boolean
  values: Record<string, string>
}
export interface AppConfigConsistency {
  appId: number
  checkedAt: string
  consistent: boolean
  totalInstances: number
  checkedInstances: number
  failedInstances: number
  comparedConfigCount: number
  inconsistentConfigCount: number
  ignoredConfigCount: number
  ignoredConfigs: string[]
  nodes: AppConfigConsistencyNode[]
  items: AppConfigConsistencyItem[]
}
export type AppOpsInstancePageResponseData = ApiResponseData<AppOpsInstancePage>
export type AppOpsMachineListResponseData = ApiResponseData<AppOpsMachineItem[]>
export type AppPasswordResponseData = ApiResponseData<AppPasswordInfo>
export type AppConfigConsistencyResponseData = ApiResponseData<AppConfigConsistency>

/** 集群详情 Tab */
export interface AppTopologyInstance {
  id: number
  ip: string
  port: number
  hostPort: string
  hostId: number
  status: number
  statusDesc: string
  roleDesc?: string
  masterInstanceId: number
  usedMemory: number
  maxMemory: number
  effectiveMaxMemory: number
  memUsePercent: number
  currItems: number
  currConnections: number
  hitPercent?: string
  memFragmentationRatio: number
  external: boolean
  instanceType?: number
  updateTimeDesc?: string
  masterStar: boolean
  modules: string[]
}
export interface AppTopology {
  instances: AppTopologyInstance[]
  connectErrors: string[]
  hasExternalInstance: boolean
}
export interface AppClientInstanceStat {
  instanceId: number
  hostPort: string
  count: number
  connectionDetails?: InstanceClientConnection[]
  detailTotal?: number
  detailsTruncated?: boolean
}
export interface AppClientItem {
  addr: string
  size: number
  flags: string[]
  instanceStats: AppClientInstanceStat[]
}

export interface AppDetailPanelInfo {
  appId: number
  clusterNo?: number
  appName: string
  typeDesc: string
  importantLevelLabel: string
  alertUsersText: string
  officerText: string
  officerId?: string
  memTotalGb: string
  machineNum: number
  masterNum: number
  slaveNum: number
  intro: string
  masterName?: string
  hasSentinelPwd?: string
}
export interface AppDetailPanelAlertConfig {
  isAccessMonitor: number
}
export interface AppDetailPanelAlertMetric {
  id: number
  alertKey: string
  threshold: string
  cycle: string
}
export interface AppDetailPanelUser {
  id: number
  name: string
  chName: string
  email: string
  mobile: string
  company: string
  alert: boolean
  type: number
}
export interface AppDetailPanel {
  hasAuth: boolean
  appInfo: AppDetailPanelInfo
  alertConfig: AppDetailPanelAlertConfig
  alertMetrics: AppDetailPanelAlertMetric[]
  users: AppDetailPanelUser[]
}

export type InstanceAlertPageResponseData = ApiResponseData<InstanceAlertPage>
export interface RiskRuleItem {
  subItem?: string | null
  level: string
  levelName: string
  operator: string
  operatorName: string
  threshold?: number | null
  minAbsolute?: number | null
  /** 分类型判定条件原文；有值时优先于 operator+threshold 展示 */
  conditionText?: string | null
  enabled: boolean
  customized: boolean
  description?: string | null
  suggestion?: string | null
}

export interface RiskRuleDimension {
  dimension: string
  dimensionName: string
  minWindowHours: number
  clusterOnly: boolean
  notImplemented: boolean
  metricDesc?: string | null
  items: RiskRuleItem[]
}

// ---- 数据模型 ----
export interface DataModelPoint {
  x: number
  y: number
  label?: string
}

export interface DataModelScatterSeries {
  name: string
  points: DataModelPoint[]
}

export interface DataModelCorrelation {
  sampleCount: number
  coefficient?: number | null
  slope?: number | null
  strength: string
  conclusion: string
  line: DataModelPoint[]
}

export interface DataModelReferenceLine {
  label: string
  value: number
}

export interface DataModelComparisonRow {
  appId: number
  appName: string
  typeDesc: string
  osArch: string
  majorVersion: string
  instanceCount: number
  hitPercent?: number | null
  memUsePercent?: number | null
  qps?: number | null
  memFragRatio?: number | null
  avgKeyBytes?: number | null
  connectedClients?: number | null
  keysCount?: number | null
  usedMemoryMb?: number | null
}

export interface DataModelResult {
  sampleCount: number
  instanceCount: number
  coverDays: number
  sampleNote?: string | null
  emptyReason?: string | null
  groups: string[]
  categories: string[]
  series: (number | null)[][]
  referenceLines: DataModelReferenceLine[]
  scatterSeries: DataModelScatterSeries[]
  xAxisName?: string
  yAxisName?: string
  correlation?: DataModelCorrelation | null
  comparisonRows: DataModelComparisonRow[]
}

export interface DataModelOptions {
  apps: { appId: number, appName: string, testApp: boolean }[]
  archs: string[]
  majorVersions: string[]
  commands: string[]
}

export type DataModelResultResponseData = ApiResponseData<DataModelResult>
export type DataModelOptionsResponseData = ApiResponseData<DataModelOptions>

export type RiskRulesResponseData = ApiResponseData<RiskRuleDimension[]>

export type RiskAssessOverviewResponseData = ApiResponseData<RiskAssessOverview>
export type RiskAssessReportResponseData = ApiResponseData<RiskAssessReport>
export type RiskAssessHistoryResponseData = ApiResponseData<RiskAssessReport[]>
export type OperationAuditPageResponseData = ApiResponseData<OperationAuditPage>
export type AlertRecordPageResponseData = ApiResponseData<AlertRecordPage>
export type TaskListPageResponseData = ApiResponseData<TaskListPage>
export type TaskFlowDetailResponseData = ApiResponseData<TaskFlowDetail>
export type MigrateListPageResponseData = ApiResponseData<MigrateListPage>
export type AppTopologyResponseData = ApiResponseData<AppTopology>
export type AppClientListResponseData = ApiResponseData<AppClientItem[]>
export type AppDetailPanelResponseData = ApiResponseData<AppDetailPanel>

/** 图表点 */
export interface ChartPoint {
  x?: number
  y?: number
  yDouble?: number
  ydouble?: number
  date?: string
  commandName?: string
}

export interface AppCommandClimax {
  commandName: string
  commandCount: number
  createTime: string
}

export interface AppStatOverview {
  appId: number
  appName: string
  mem: number
  memUsePercent: number
  hitPercent: number
  instanceCount: number
  startDate: string
  endDate: string
  top5Commands: ChartPoint[]
  top5Climax: AppCommandClimax[]
  conn: number
  versionName?: string
  typeDesc?: string
  masterNum: number
  slaveNum: number
  currentObjNum: number
  statusDesc?: string
  machineNum: number
  statRangeClamped?: boolean
}

export interface AppDaily {
  dailyDate: string
  hasData: boolean
  /** 是否来自 app_daily 表 */
  persisted?: boolean
  /** 测试应用定时任务不写日报 */
  testApp?: boolean
  /** 是否有客户端 SDK 上报 */
  clientMetricsAvailable?: boolean
  bigKeyTimes: number
  bigKeyInfo?: string
  valueSizeDistributeDescHtml?: string
  slowLogCount: number
  latencyCount: number
  clientExceptionCount: number
  clientCmdCount: number
  clientAvgCmdCost: number
  clientConnExpCount: number
  clientAvgConnExpCost: number
  clientCmdExpCount: number
  clientAvgCmdExpCost: number
  maxMinuteClientCount: number
  avgMinuteClientCount: number
  maxMinuteCommandCount: number
  avgMinuteCommandCount: number
  avgHitRatio: number
  minMinuteHitRatio: number
  maxMinuteHitRatio: number
  avgUsedMemory: number
  maxUsedMemory: number
  expiredKeysCount: number
  evictedKeysCount: number
  avgObjectSize: number
  maxObjectSize: number
  avgMinuteNetInputByte: number
  maxMinuteNetInputByte: number
  avgMinuteNetOutputByte: number
  maxMinuteNetOutputByte: number
}

export interface AppMachineTopologyNode {
  id: number
  ip: string
  port: number
  hostPort: string
  groupId: number
  roleDesc: string
  instanceType?: number
  status: number
  offline: boolean
}
export interface AppMachineTopologyRow {
  ip: string
  nodes: AppMachineTopologyNode[]
}
export interface AppMachineTopology {
  appType: number
  sentinelApp: boolean
  groupLabel: string
  groupCount: number
  warnings: string[]
  machines: AppMachineTopologyRow[]
}
export type AppMachineTopologyResponseData = ApiResponseData<AppMachineTopology>

/** 诊断工具 */
export interface DiagnosticAppOption {
  appId: number
  clusterNo?: number
  type?: number
  appName: string
  versionName?: string
  typeDesc?: string
}
export interface DiagnosticInstance {
  id: number
  ip: string
  port: number
  hostPort: string
}
export interface DiagnosticTask {
  id: number
  taskId: number
  parentTaskId: number
  auditId: number
  type: number
  status: number
  appId: number
  clusterNo?: number
  appName?: string
  node?: string
  diagnosticCondition?: string
  redisKey?: string
  deleteStatus: number
  resultCount: number
  formatCostTime?: string
  createTime?: string
}
export interface DiagnosticResult {
  count: number
  listResult?: string[]
  mapResult?: Record<string, string>
}

export type AppStatOverviewResponseData = ApiResponseData<AppStatOverview>
export type ChartPointListResponseData = ApiResponseData<ChartPoint[]>
export type AppStatChartsBatchResponseData = ApiResponseData<Record<string, ChartPoint[]>>
export type AppDailyResponseData = ApiResponseData<AppDaily>

/** Wiki 文档 */
export interface WikiContent {
  html?: string
  toc?: string
}
export type WikiContentResponseData = ApiResponseData<WikiContent>

/** 延迟监控 */
export interface LatencyChartPoint {
  timestamp: number
  count: number
}
export interface LatencyChartSeries {
  event: string
  points: LatencyChartPoint[]
}
export interface LatencyInstanceCount {
  hostPort: string
  count: number
  instanceId?: number
}
export interface AppSlowLogItem {
  id: number
  slowLogId: number
  instanceId: number
  ip: string
  port: number
  hostPort: string
  costTime: number
  command: string
  executeTime?: string
  clientIp?: string
}
export interface LatencyEventDetail {
  id: number
  instanceId: number
  hostPort: string
  event: string
  executeTime: string
  executionCost: number
}
export interface AppLatency {
  appId: number
  searchDate: string
  latencyDataEmpty: boolean
  chartSeries: LatencyChartSeries[]
  latencyEvents: LatencyEventDetail[]
  instanceLatencies: LatencyInstanceCount[]
  instanceSlowLogCounts: LatencyInstanceCount[]
  slowLogs: AppSlowLogItem[]
  groupedSlowLogs: Record<string, AppSlowLogItem[]>
  instanceIdByHostPort: Record<string, number>
  instances: string[]
}
export type AppLatencyResponseData = ApiResponseData<AppLatency>

/** 键值分析 */
export interface ParamCountItem {
  name: string
  count: number
  extra?: string
}
export interface KeyAnalysisInstance {
  id: number
  ip: string
  port: number
  hostPort: string
  roleDesc?: string
  slave: boolean
  currItems: number
}
export interface KeyAnalysisAuditItem {
  id: number
  status: number
  statusDesc: string
  taskId: number
  info?: string
  userName?: string
  createTime?: string
  refuseReason?: string
  nodeInfo?: string
  totalKeyCount?: number
  riskInfo?: string
  running: boolean
  passed: boolean
  rejected: boolean
}
export interface KeyAnalysisPage {
  appId: number
  runningTaskId?: number
  assistRedisEndpoint?: string
  instances: KeyAnalysisInstance[]
  audits: KeyAnalysisAuditItem[]
}
export interface KeyAnalysisProgress {
  taskId: number
  progress: number
  progressText: string
  done: number
  total: number
  status: number
  finished: boolean
  currentStep?: string
}
export interface KeyAnalysisStartResult {
  taskId: number
  auditId: number
}
export interface KeyAnalysisBigKey {
  instance: string
  keyName: string
  type: string
  sizeLabel: string
  length: number
}
export interface KeyPrefixStat {
  type: string
  prefix: string
  count: number
  bytes: number
  bytesLabel: string
}
export interface OfflineAnalysisRecord {
  id: number
  fileName: string
  fileSize: number
  fileSizeLabel: string
  fileType: string
  status: number
  statusDesc: string
  keyCount: number
  errorMsg?: string | null
  userName: string
  createTime: string
  updateTime: string
}

export interface OfflineAnalysisPage {
  items: OfflineAnalysisRecord[]
  pageNo: number
  pageSize: number
  totalCount: number
}

export type OfflineAnalysisPageResponseData = ApiResponseData<OfflineAnalysisPage>
export type OfflineAnalysisRecordResponseData = ApiResponseData<OfflineAnalysisRecord>

export interface KeyAnalysisResult {
  appId: number
  auditId: number
  hasDistributionData: boolean
  emptyDb: boolean
  statsMissing: boolean
  hasAnalysisRisk: boolean
  hasTypeMemoryData: boolean
  topKeyFromMemoryScan: boolean
  bigKeyCount: number
  bigKeyStringBytes: number
  bigKeyCollectionElements: number
  assistRedisEndpoint?: string
  totalKeyCount: number
  analysisRisks: string[]
  analysisNodes: string[]
  idleKeyDistri: ParamCountItem[]
  keyTypeDistri: ParamCountItem[]
  keyTtlDistri: ParamCountItem[]
  keyValueSizeDistri: ParamCountItem[]
  /** 键值大小极值（字节）。sampleCount 为 0 表示本次分析未采集，前端不画极值图 */
  valueSizeMaxBytes: number
  valueSizeMinBytes: number
  valueSizeSumBytes: number
  valueSizeSampleCount: number
  keyTypeMemoryDistri: ParamCountItem[]
  topKeys: KeyAnalysisBigKey[]
  bigKeys: KeyAnalysisBigKey[]
  keyPrefixTop: KeyPrefixStat[]
}
export type KeyAnalysisPageResponseData = ApiResponseData<KeyAnalysisPage>
export type KeyAnalysisProgressResponseData = ApiResponseData<KeyAnalysisProgress>
export type KeyAnalysisStartResultResponseData = ApiResponseData<KeyAnalysisStartResult>
export type KeyAnalysisResultResponseData = ApiResponseData<KeyAnalysisResult>

export type DiagnosticAppListResponseData = ApiResponseData<DiagnosticAppOption[]>
export type DiagnosticInstanceListResponseData = ApiResponseData<DiagnosticInstance[]>
export type DiagnosticTaskListResponseData = ApiResponseData<DiagnosticTask[]>
export type DiagnosticResultResponseData = ApiResponseData<DiagnosticResult>

/** 在线健康检查 */
export interface OnlineHealthCheckItem {
  name: string
  level: "Info" | "Error" | string
  expect: string
  current: string
  description: string
}
export interface OnlineHealthTarget {
  host: string
  mode: "standalone" | "cluster" | "sentinel" | "unknown" | string
  modeLabel: string
  allOk: boolean
  connectError?: string
  managed?: boolean
  appId?: number
  appName?: string
  checks: OnlineHealthCheckItem[]
}
export interface OnlineHealthCheckResult {
  result: string
  targets: OnlineHealthTarget[]
}
export type OnlineHealthCheckResponseData = ApiResponseData<OnlineHealthCheckResult>

/** 节点详情 */
export interface InstanceDetailTab {
  key: string
  label: string
}
export interface InstanceDetail {
  instanceId: number
  appId: number
  appName: string
  hostPort: string
  type: number
  typeDesc: string
  status: number
  statusDesc: string
  nodeManageContext: boolean
  defaultTab: string
  tabs: InstanceDetailTab[]
}
export interface InstanceStat {
  instanceId: number
  appId: number
  appName: string
  hostPort: string
  instanceType: number
  typeDesc: string
  statusDesc: string
  roleDesc?: string
  memUsePercent: number
  memUsedGb: number
  memTotalGb: number
  hitPercent?: string
  currItems: number
  currConnections: number
  memFragmentationRatio: number
  uptimeSeconds?: number
  osArch?: string
  osInfo?: string
  realtimeSupported?: boolean
  startDate: string
  endDate: string
  infoSections?: Record<string, Record<string, string>>
  topCommands: ChartPoint[]
  top5Climax: AppCommandClimax[]
}
export interface InstanceSlowLogItem {
  id: number
  timeStamp: string
  executionTime: number
  command: string
  clientIp?: string
  clientName?: string
}
export interface InstanceSlowLogPage {
  items: InstanceSlowLogItem[]
  pageNo: number
  pageSize: number
  totalCount: number
  totalPages: number
}
export interface InstanceFaultPage {
  items: InstanceFaultItem[]
  pageNo: number
  pageSize: number
  totalCount: number
  totalPages: number
}
export interface InstanceFaultItem {
  id: number
  appId: number
  instId: number
  ip: string
  port: number
  status: number
  statusDesc: string
  type: number
  typeDesc: string
  createTime?: string
  reason?: string
}
export interface InstanceClientConnection {
  flags: string
  cmd: string
  detail: string
}
export interface InstanceClientItem {
  addr: string
  count: number
  clientTypes: string[]
  connections: string[]
  connectionDetails: InstanceClientConnection[]
  detailTotal?: number
  detailsTruncated?: boolean
}
export interface InstanceClientsPage {
  items: InstanceClientItem[]
  rawConnections: string[]
  totalConnections?: number
}
export interface InstanceLog {
  lines: string[]
}
export interface InstanceCommandResult {
  result: string
}
export interface InstanceCommandChartSeries {
  name: string
  data: number[]
}
export interface InstanceCommandChart {
  commandName: string
  startDate: string
  endDate: string
  categories: string[]
  series: InstanceCommandChartSeries[]
}
export interface InstanceCommandAnalysis {
  instanceId: number
  startDate: string
  endDate: string
  commands: string[]
}
export type InstanceDetailResponseData = ApiResponseData<InstanceDetail>
export type InstanceStatResponseData = ApiResponseData<InstanceStat>
export type InstanceSlowLogListResponseData = ApiResponseData<InstanceSlowLogPage>
export type InstanceFaultListResponseData = ApiResponseData<InstanceFaultPage>
export type InstanceClientListResponseData = ApiResponseData<InstanceClientsPage>
export type InstanceLogResponseData = ApiResponseData<InstanceLog>
export type InstanceConfigResponseData = ApiResponseData<Record<string, string>>
export interface InstanceConfigUpdateResult {
  instanceId: number
  configName: string
  previousValue: string
  currentValue: string
  source: string
  rewriteSuccess: boolean
  /** CONFIG REWRITE 失败原因，成功时为 null */
  rewriteError?: string | null
  message: string
}
export type InstanceConfigUpdateResponseData = ApiResponseData<InstanceConfigUpdateResult>
export type InstanceCommandResultResponseData = ApiResponseData<InstanceCommandResult>
export type InstanceCommandAnalysisResponseData = ApiResponseData<InstanceCommandAnalysis>
export type InstanceCommandChartResponseData = ApiResponseData<InstanceCommandChart>

/** 运维主面板聚合数据 */
export interface DashboardUnmonitoredApp {
  appId: number
  appName: string
}
export interface DashboardPosture {
  severeCount: number
  warningCount: number
  normalCount: number
  unmonitoredApps: DashboardUnmonitoredApp[]
}
export interface DashboardActiveAlert {
  appId: number
  appName: string
  instanceId: number
  hostPort: string
  metric: string
  importantLevel: number
  importantLevelDesc: string
  active: boolean
  firstTime: string
  lastTime: string
  duration: string
  count: number
  latestContent?: string
}
export interface DashboardSentinelCounter {
  key: string
  label: string
  value: number
  hotNodes: string[]
}
export interface DashboardTopBoardRow {
  appId: number
  appName: string
  instanceId: number
  hostPort: string
  value: number
  display: string
  exceeded: boolean
}
export interface DashboardTopBoard {
  key: string
  label: string
  unit: string
  threshold?: number | null
  /** 榜下小字说明（统计口径限制） */
  hint?: string | null
  /** instance | instanceClients | appLatency */
  linkType?: string | null
  rows: DashboardTopBoardRow[]
}
export interface DashboardKpi {
  instanceTotal: number
  instanceOnline: number
  instanceOffline: number
  clusterTotal: number
  masterCount: number
  slaveCount: number
  memUsed: number
  memTotal: number
  memRatio: number
  keyTotal: number
  keyExpires: number
  keyExpiresRatio: number
  qps: number
  qpsPeak: number
  hitRate: number
  hitRateDelta?: number | null
}
export interface DashboardClusterHealthRow {
  appId: number
  appName: string
  status: string
  statusDesc: string
  masterCount: number
  slaveCount: number
  memRatio: number
  memDisplay: string
}
export interface DashboardClusterHealth {
  healthy: number
  warning: number
  abnormal: number
  offline: number
  rows: DashboardClusterHealthRow[]
}
export interface DashboardTrendSeries {
  appId: number
  name: string
  values: (number | null)[]
}
export interface DashboardTrend {
  times: string[]
  series: DashboardTrendSeries[]
}
export interface DashboardResourceGauge {
  key: string
  label: string
  percent?: number | null
  display: string
  sub: string
  spark: number[]
}
export interface DashboardSlowCommand {
  command: string
  avgCostMs: number
  calls: number
  costRatio: number
}
export interface DashboardRecentOp {
  time: string
  userName: string
  action: string
  target: string
  success: boolean
}
export interface DashboardOps {
  dataTime: string
  windowMinutes: number
  kpi: DashboardKpi
  clusterHealth: DashboardClusterHealth
  trend: DashboardTrend
  resources: DashboardResourceGauge[]
  slowCommands: DashboardSlowCommand[]
  recentOps: DashboardRecentOp[]
  posture: DashboardPosture
  activeAlerts: DashboardActiveAlert[]
  sentinels: DashboardSentinelCounter[]
  boards: DashboardTopBoard[]
}
export type DashboardOpsResponseData = ApiResponseData<DashboardOps>
