import type { AppOpsInstancePage, FaultDiagnosticCheck, FaultDiagnosticReport } from "@/api/cachecloud"

/** 平台主机相关能力快照 */
export interface HostCapability {
  /** 全局配置：是否启用 SSH 主机运维（启动/关闭节点等） */
  hostOpsEnabled: boolean
  /** 集群节点 IP 是否均已关联可 SSH 的 machine_info */
  sshCapable: boolean
  /** 集群关联的管控机器数量 */
  managedMachineCount: number
  /** 是否全部为外部纳管节点 */
  externalManaged: boolean
}

/** 依赖管控主机 / SSH 的前端功能点 */
export type HostFeature =
  | "ops.startStop"
  | "ops.failover"
  | "ops.scrollRestart"
  | "diagnostic.host"
  | "diagnostic.network"
  | "machine.tab"
  | "machine.link"

/** 故障诊断中依赖 SSH 的检查分组 */
export const HOST_DEPENDENT_DIAGNOSTIC_GROUPS = ["主机", "网络层"] as const

/** 故障诊断中依赖 SSH 的检查项 code */
export const HOST_DEPENDENT_DIAGNOSTIC_CODES = [
  "host_ssh",
  "network_tcp",
  "network_ping",
  "host_process",
  "host_oom",
  "host_disk",
  "host_system"
] as const

export function defaultHostCapability(): HostCapability {
  return {
    hostOpsEnabled: false,
    sshCapable: false,
    managedMachineCount: 0,
    externalManaged: false
  }
}

export function buildHostCapabilityFromOpsPage(page?: AppOpsInstancePage | null): HostCapability {
  if (!page) return defaultHostCapability()
  return {
    hostOpsEnabled: page.hostOpsEnabled ?? false,
    sshCapable: page.sshCapable ?? false,
    managedMachineCount: page.managedMachineCount ?? 0,
    externalManaged: page.externalManaged ?? false
  }
}

export function buildHostCapabilityFromDiagnosticReport(report?: FaultDiagnosticReport | null): HostCapability {
  if (!report) return defaultHostCapability()
  return {
    hostOpsEnabled: false,
    sshCapable: report.sshCapable ?? false,
    managedMachineCount: 0,
    externalManaged: report.externalManaged ?? false
  }
}

export function mergeHostCapability(
  base: HostCapability,
  patch?: Partial<HostCapability> | null
): HostCapability {
  if (!patch) return base
  return { ...base, ...patch }
}

/** 是否可展示/使用某主机相关功能 */
export function canUseHostFeature(feature: HostFeature, cap: HostCapability): boolean {
  switch (feature) {
    case "ops.startStop":
    case "ops.failover":
    case "ops.scrollRestart":
      return cap.hostOpsEnabled
    case "diagnostic.host":
    case "diagnostic.network":
      return cap.sshCapable
    case "machine.tab":
      return cap.managedMachineCount > 0
    case "machine.link":
      return cap.sshCapable || cap.managedMachineCount > 0
    default:
      return false
  }
}

export function isHostDependentDiagnosticCheck(check?: FaultDiagnosticCheck | null): boolean {
  if (!check) return false
  const code = (check.code || "").toLowerCase()
  if (HOST_DEPENDENT_DIAGNOSTIC_CODES.includes(code as typeof HOST_DEPENDENT_DIAGNOSTIC_CODES[number])) {
    return true
  }
  const group = check.group || ""
  return HOST_DEPENDENT_DIAGNOSTIC_GROUPS.includes(group as typeof HOST_DEPENDENT_DIAGNOSTIC_GROUPS[number])
}

/** 无 SSH 能力时隐藏主机/网络层检查项 */
export function filterDiagnosticChecksForDisplay(
  checks: FaultDiagnosticCheck[] | undefined,
  cap: HostCapability
): FaultDiagnosticCheck[] {
  const list = checks ?? []
  if (cap.sshCapable) return list
  return list.filter(c => !isHostDependentDiagnosticCheck(c))
}

export function diagnosticScopeHint(cap: HostCapability): string {
  if (cap.sshCapable) {
    return "17 项检查覆盖复制链路、认证、网络、主机与 Cluster"
  }
  return "10 项检查覆盖复制链路、认证与 Cluster（无管控主机，已隐藏主机/网络层检查）"
}

export function hostCapabilityUnavailableHint(cap: HostCapability): string {
  if (cap.sshCapable) return ""
  if (cap.externalManaged) {
    return "当前为外部纳管集群，节点 IP 未纳入机器池或未配置 SSH，主机层检查不可用。"
  }
  return "当前集群无可用管控主机（machine_info + SSH），主机层检查不可用。"
}
