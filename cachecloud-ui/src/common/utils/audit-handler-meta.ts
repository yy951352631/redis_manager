/**
 * 审计日志「处理方法」的中文映射。
 *
 * 键是审计拦截器记录的 `控制器简名#方法名`（见 OperationAuditInterceptor.resolveHandler），
 * 值是这次操作做了什么的一句话简述。
 *
 * 只有会改数据的请求（POST/PUT/DELETE/PATCH）才会进审计表，所以这里只需要覆盖
 * 这些方法。表中保留了几个已下线接口的条目：历史记录还指着它们，缺了就只能显示
 * 原始方法名。
 */
const AUDIT_HANDLER_TEXT: Record<string, string> = {
  // ── 登录与个人信息 ────────────────────────────────
  "AuthApiController#login": "登录控制台",
  "AuthApiController#logout": "退出登录",
  "AuthApiController#updateProfile": "修改个人资料",

  // ── 集群管理 ─────────────────────────────────────
  "AppApiController#recoverApp": "恢复已下线集群",
  "AppApiController#offlineApp": "下线集群",
  "AppApiController#permanentlyDeleteOfflineApp": "彻底删除已下线集群",
  "AppApiController#updateAppDetail": "修改集群基本信息",
  "AppApiController#changeAppAlertConfig": "修改集群报警通知设置",
  "AppApiController#addAppUsers": "添加集群授权用户",
  "AppApiController#updateAppUser": "修改集群授权用户",
  "AppApiController#deleteAppUser": "移除集群授权用户",
  "AppApiController#executeCommand": "在集群上执行命令",
  "AppApiController#cleanSlowLogs": "清理集群慢查询记录",
  "AppApiController#startKeyAnalysis": "发起键值分析",
  "AppApiController#deleteKeyAnalysisRecord": "删除键值分析记录",

  // ── 集群运维 ─────────────────────────────────────
  "AppOpsApiController#updatePassword": "修改集群密码",
  "AppOpsApiController#runFaultDiagnostic": "执行故障诊断",
  "AppOpsApiController#clusterDelNode": "从集群摘除节点",
  "AppOpsApiController#clusterSlaveFailOver": "触发 Cluster 从节点切换",
  "AppOpsApiController#sentinelFailOver": "触发 Sentinel 主从切换",
  "AppOpsApiController#addSlave": "为节点添加从节点",
  "AppOpsApiController#scrollRestart": "滚动重启集群",
  "AppOpsApiController#updateConfigRestart": "改配置并重启集群",
  "AppOpsApiController#startInstance": "启动节点",
  "AppOpsApiController#shutdownInstance": "关闭节点",
  "AppOpsApiController#forgetInstance": "永久下线节点",
  "AppOpsApiController#addSentinel": "添加 Sentinel 节点（已下线）",

  // ── 集群统计 ─────────────────────────────────────
  "AppStatApiController#sendDailyEmail": "发送日报邮件",
  "AppStatApiController#updateTopology": "刷新集群拓扑",

  // ── 纳管 ─────────────────────────────────────────
  "ExternalRedisApiController#check": "纳管前连通性校验",
  "ExternalRedisApiController#save": "纳管新集群",
  "ExternalRedisApiController#addInstances": "为集群增加节点",
  "ExternalRedisApiController#removeInstance": "从平台移除节点",
  "ExternalRedisApiController#repairInstances": "修复集群节点关系",

  // ── 节点 ─────────────────────────────────────────
  "InstanceApiController#updateConfig": "修改节点配置",
  "InstanceApiController#command": "在节点上执行命令",
  "InstanceOpsApiController#runConfigCheck": "执行配置一致性校验",
  "InstanceOpsApiController#runCommandCheck": "执行命令兼容性检查",
  "InstanceOpsApiController#stopRestart": "中止滚动重启",

  // ── 报警配置 ─────────────────────────────────────
  "InstanceAlertApiController#add": "新增节点报警配置",
  "InstanceAlertApiController#addApp": "新增集群报警配置",
  "InstanceAlertApiController#update": "修改报警配置",
  "InstanceAlertApiController#remove": "删除报警配置",

  // ── 数据迁移 ─────────────────────────────────────
  "MigrateApiController#check": "迁移前连通性校验",
  "MigrateApiController#start": "启动数据迁移",
  "MigrateApiController#stop": "停止数据迁移",
  "MigrateApiController#resync": "重新同步迁移任务",
  "MigrateApiController#delete": "删除迁移任务",

  // ── 运维工具 ─────────────────────────────────────
  "OfflineAnalysisApiController#upload": "上传离线文件分析",
  "OfflineAnalysisApiController#delete": "删除离线分析记录",
  "DiagnosticsApiController#submit": "提交诊断任务",
  "DiagnosticsApiController#deleteTaskRecord": "删除诊断任务记录",
  "DiagnosticsApiController#confirmDelete": "确认删除诊断命中的 key",
  "DiagnosticsApiController#onlineVerify": "在线校验诊断结果",
  "DiagnosticsApiController#executeCommand": "执行诊断命令",

  // ── 压测工具 ─────────────────────────────────────
  "BenchmarkApiController#start": "启动压测",
  "BenchmarkApiController#stop": "停止压测",

  // ── 风险评估 ─────────────────────────────────────
  "RiskAssessApiController#assess": "执行风险评估",

  // ── 调度任务 ─────────────────────────────────────
  "QuartzApiController#pause": "暂停调度任务",
  "QuartzApiController#resume": "恢复调度任务",
  "QuartzApiController#remove": "删除调度任务",
  "TaskApiController#execute": "手工重跑任务（已下线）",

  // ── 用户管理 ─────────────────────────────────────
  "UserManageApiController#save": "新增或修改用户",
  "UserManageApiController#delete": "删除用户",
  "UserManageApiController#resetPassword": "重置用户密码",
  "UserManageApiController#updatePassword": "修改用户密码",

  // ── 其他 ─────────────────────────────────────────
  "BasicErrorController#error": "请求出错（框架兜底）"
}

/**
 * 取处理方法的中文简述。
 *
 * 映射不到时回退到原始值，而不是显示成「未知操作」—— 新增接口忘了登记时，
 * 留着方法名至少还能查，换成占位文案就把线索也抹掉了。
 */
export function formatAuditHandler(handler?: string | null): string {
  const raw = (handler ?? "").trim()
  if (!raw) return "-"
  return AUDIT_HANDLER_TEXT[raw] ?? raw
}

/** 是否已登记中文简述，未登记时界面上把原始方法名以次要样式弱化显示 */
export function hasAuditHandlerText(handler?: string | null): boolean {
  const raw = (handler ?? "").trim()
  return raw.length > 0 && raw in AUDIT_HANDLER_TEXT
}

export { AUDIT_HANDLER_TEXT }
