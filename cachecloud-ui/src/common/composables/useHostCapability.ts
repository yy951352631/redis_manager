import type { AppOpsInstancePage, FaultDiagnosticReport } from "@/api/cachecloud"
import type { HostCapability, HostFeature } from "@/common/utils/host-capability"
import { getAppOpsInstancesApi } from "@/api/cachecloud"
import {
  buildHostCapabilityFromDiagnosticReport,
  buildHostCapabilityFromOpsPage,
  canUseHostFeature,
  defaultHostCapability,
  diagnosticScopeHint,
  filterDiagnosticChecksForDisplay,

  hostCapabilityUnavailableHint,

  isHostDependentDiagnosticCheck,
  mergeHostCapability
} from "@/common/utils/host-capability"

interface HostCapabilitySource {
  opsPage?: AppOpsInstancePage | null
  diagnosticReport?: FaultDiagnosticReport | null
}

/**
 * 统一判断「管控主机」相关能力：SSH 运维、主机诊断、机器 Tab 等。
 * 优先使用 ops 实例页数据；诊断报告可补充 sshCapable / externalManaged。
 */
export function useHostCapability(
  appId: MaybeRefOrGetter<number>,
  source?: MaybeRefOrGetter<HostCapabilitySource | undefined>
) {
  const capability = ref<HostCapability>(defaultHostCapability())
  const loading = ref(false)

  function applySources(opsPage?: AppOpsInstancePage | null, report?: FaultDiagnosticReport | null) {
    const fromOps = buildHostCapabilityFromOpsPage(opsPage)
    const fromReport = buildHostCapabilityFromDiagnosticReport(report)
    capability.value = mergeHostCapability(fromOps, {
      sshCapable: fromOps.sshCapable || fromReport.sshCapable,
      externalManaged: fromOps.externalManaged || fromReport.externalManaged
    })
  }

  function syncFromSource() {
    const src = toValue(source)
    applySources(src?.opsPage, src?.diagnosticReport)
  }

  async function fetchCapability() {
    const id = toValue(appId)
    if (!id) {
      capability.value = defaultHostCapability()
      return capability.value
    }
    loading.value = true
    try {
      const { data } = await getAppOpsInstancesApi(id)
      const src = toValue(source)
      applySources(data, src?.diagnosticReport)
      return capability.value
    } finally {
      loading.value = false
    }
  }

  const hostOpsEnabled = computed(() => capability.value.hostOpsEnabled)
  const sshCapable = computed(() => capability.value.sshCapable)
  const managedMachineCount = computed(() => capability.value.managedMachineCount)
  const showMachineTab = computed(() => canUseHostFeature("machine.tab", capability.value))
  const diagnosticHint = computed(() => diagnosticScopeHint(capability.value))
  const unavailableHint = computed(() => hostCapabilityUnavailableHint(capability.value))

  function canUse(feature: HostFeature) {
    return canUseHostFeature(feature, capability.value)
  }

  function filterDiagnosticChecks<T extends { code?: string, group?: string }>(checks?: T[]) {
    if (capability.value.sshCapable) return checks ?? []
    return (checks ?? []).filter(c => !isHostDependentDiagnosticCheck(c))
  }

  watch(
    () => toValue(source),
    () => syncFromSource(),
    { deep: true, immediate: true }
  )

  return {
    capability,
    loading,
    hostOpsEnabled,
    sshCapable,
    managedMachineCount,
    showMachineTab,
    diagnosticHint,
    unavailableHint,
    canUse,
    filterDiagnosticChecks,
    filterDiagnosticChecksForDisplay: (checks?: Parameters<typeof filterDiagnosticChecksForDisplay>[0]) =>
      filterDiagnosticChecksForDisplay(checks, capability.value),
    fetchCapability,
    syncFromSource
  }
}
