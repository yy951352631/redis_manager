<script lang="ts" setup>
import type { AppOpsInstancePage, AppTopology, AppTopologyInstance } from "@/api/cachecloud"
import { StarFilled } from "@element-plus/icons-vue"
import {
  addExternalRedisInstancesApi,
  addSlaveApi,
  clusterSlaveFailoverApi,
  formatRedisConfigOptionLabel,
  getAppOpsInstancesApi,
  getAppTopologyApi,
  getInstanceConfigApi,
  getInstanceLogApi,
  removeExternalRedisInstanceApi,
  scrollRestartApi,
  sentinelFailoverApi,
  shutdownInstanceOpsApi,
  updateInstanceConfigApi
} from "@/api/cachecloud"
import { useHostCapability } from "@/common/composables/useHostCapability"
import { getRedisConfigValueOptions } from "@/common/utils/redis-config"
// 原「集群密码修改」tab 整体搬进抽屉，组件本身不改，避免密码逻辑出现两份实现
import OpsPasswordTab from "@/pages/cachecloud/app/detail/tabs/OpsPasswordTab.vue"
import "@/common/assets/styles/app-ops.scss"

const props = withDefaults(defineProps<{
  appId: number
  /** 仅节点详情内嵌拓扑传 false，集群详情节点列表默认直接展示运维按钮 */
  opsMode?: boolean
  /** 节点详情内嵌时传入，用于高亮当前节点 */
  currentInstanceId?: number
}>(), {
  opsMode: true,
  currentInstanceId: 0
})

const topologyTableRef = ref<HTMLTableElement | null>(null)

const router = useRouter()
const loading = ref(false)
const opsLoading = ref(false)
const instancePage = ref<AppOpsInstancePage | null>(null)
const topology = ref<AppTopology | null>(null)

const addSlaveVisible = ref(false)
const addSlaveForm = reactive({ masterInstanceId: 0, slaveHost: "" })
const restartVisible = ref(false)
const batchConfigOnly = ref(false)
const restartSubmitting = ref(false)
const restartForm = reactive({
  mode: "restart" as "restart" | "config",
  transferFlag: false,
  instanceIds: [] as number[],
  configName: "",
  configValues: [""] as string[]
})
const instanceConfigVisible = ref(false)
const instanceLogVisible = ref(false)
const instanceLogLoading = ref(false)
const instanceLogLines = ref<string[]>([])
const instanceLogTarget = ref<AppTopologyInstance | null>(null)
const instanceConfigLoading = ref(false)
const instanceConfigSaving = ref(false)
const instanceConfigTarget = ref<AppTopologyInstance | null>(null)
const instanceConfigMap = ref<Record<string, string>>({})
const instanceConfigKey = ref("")
const instanceConfigKeyCustom = ref("")
const instanceConfigValue = ref("")
const batchConfigMap = ref<Record<string, string>>({})
const batchConfigLoading = ref(false)

const instanceConfigOptions = computed(() =>
  Object.entries(instanceConfigMap.value).sort(([a], [b]) => a.localeCompare(b)).map(([key, value]) => ({
    key,
    value,
    label: formatRedisConfigOptionLabel(key, value ?? "")
  }))
)
const activeInstanceConfigKey = computed(() => instanceConfigKeyCustom.value.trim() || instanceConfigKey.value.trim())
const instanceConfigValueOptions = computed(() => getRedisConfigValueOptions(activeInstanceConfigKey.value))
const batchConfigOptions = computed(() =>
  Object.entries(batchConfigMap.value).sort(([a], [b]) => a.localeCompare(b)).map(([key, value]) => ({
    key,
    label: formatRedisConfigOptionLabel(key, value ?? "")
  }))
)
const batchConfigValueOptions = computed(() => getRedisConfigValueOptions(restartForm.configName))

const instances = computed(() =>
  props.opsMode ? (instancePage.value?.instances ?? []) : (topology.value?.instances ?? [])
)
const connectErrors = computed(() => topology.value?.connectErrors ?? [])
const isCluster = computed(() => instancePage.value?.appType === 2)
const passwordDrawerVisible = ref(false)

/** 新增节点：把已在运行的 Redis 登记到当前集群 */
const addNodeVisible = ref(false)
const addNodeSubmitting = ref(false)
const addNodeText = ref("")

async function handleAddNode() {
  const text = addNodeText.value.trim()
  if (!text) {
    ElMessage.warning("请填写要新增的节点")
    return
  }
  addNodeSubmitting.value = true
  try {
    const { data } = await addExternalRedisInstancesApi(props.appId, text)
    ElMessage.success(data?.message || "节点已新增")
    addNodeVisible.value = false
    addNodeText.value = ""
    await fetchData()
  } catch (e: unknown) {
    ElMessage.error(e instanceof Error ? e.message : "新增节点失败")
  } finally {
    addNodeSubmitting.value = false
  }
}
const isSentinel = computed(() => instancePage.value?.appType === 5 || instancePage.value?.sentinelApp)

const hostCap = useHostCapability(
  () => props.appId,
  () => ({ opsPage: instancePage.value })
)

const hostOpsEnabled = computed(() => hostCap.hostOpsEnabled.value)

function isSentinelNode(row: AppTopologyInstance) {
  return row.instanceType === 5 || row.roleDesc === "sentinel"
}

const runnableRedisInstances = computed(() =>
  runnableInstances.value.filter(i => !isSentinelNode(i))
)

const canBatchConfig = computed(() => runnableRedisInstances.value.length > 0)
const masterInstances = computed(() =>
  instances.value.filter(i => !isSentinelNode(i) && (i.roleDesc === "master" || i.masterInstanceId === 0))
)

const runnableInstances = computed(() =>
  instances.value.filter(i => i.statusDesc === "运行中" || i.status === 1)
)

const GB = 1024 * 1024 * 1024
const MB = 1024 * 1024
const FRAG_MEM_THRESHOLD = 100 * MB

function formatMem(bytes: number) {
  if (!bytes) return "0M"
  if (bytes >= GB) return `${(bytes / GB).toFixed(2)}G`
  return `${(bytes / MB).toFixed(1)}M`
}

function memBarWidth(pct: number, used: number) {
  if (used > 0 && pct < 0.5) return 0.5
  return Math.min(100, Math.max(0, pct))
}

function memBarClass(pct: number) {
  return pct >= 80 ? "is-danger" : "is-success"
}

function readMemBarWidth(row: AppTopologyInstance) {
  const pct = row.memUsePercent || 0
  if (row.usedMemory > 0 && pct < 0.5) return 0.5
  return pct
}

function readMemBarClass(row: AppTopologyInstance) {
  return row.memUsePercent >= 80 ? "is-danger" : "is-success"
}

function fragClass(row: AppTopologyInstance) {
  const ratio = row.memFragmentationRatio
  const used = row.usedMemory
  if (ratio > 5 && used > FRAG_MEM_THRESHOLD) return "is-danger"
  if (ratio >= 3 && ratio < 5 && used > FRAG_MEM_THRESHOLD) return "is-warning"
  return "is-success"
}

function isCurrentInstance(row: AppTopologyInstance) {
  return props.currentInstanceId > 0 && row.id === props.currentInstanceId
}

function topologyRowClass(row: AppTopologyInstance) {
  return isCurrentInstance(row) ? "is-current-row" : ""
}

function opsTableRowClass({ row }: { row: AppTopologyInstance }) {
  return isCurrentInstance(row) ? "is-current-instance-row" : ""
}

function scrollToCurrentRow() {
  if (!props.currentInstanceId || props.opsMode) return
  nextTick(() => {
    topologyTableRef.value?.querySelector("tr.is-current-row")?.scrollIntoView({ block: "center", behavior: "smooth" })
  })
}

function instanceDetailUrl(instanceId: number) {
  return `/external/redis/detail/${instanceId}?appId=${props.appId}`
}

function openInstance(instanceId: number, tab?: string, condition?: number) {
  const query: Record<string, string> = { appId: String(props.appId) }
  if (tab) query.tab = tab
  if (condition != null) query.condition = String(condition)
  router.push({ path: `/external/redis/detail/${instanceId}`, query })
}

function handleExternalIpClick(instanceId: number) {
  ElMessage.info("该节点已纳管且主机未纳入机器池，将打开节点详情")
  openInstance(instanceId)
}

async function refreshInstances() {
  if (props.opsMode) {
    const { data } = await getAppOpsInstancesApi(props.appId)
    instancePage.value = data
  } else {
    const { data } = await getAppTopologyApi(props.appId)
    topology.value = data
  }
}

async function fetchData() {
  loading.value = true
  try {
    if (props.opsMode) {
      const [{ data: ops }, { data: topo }] = await Promise.all([
        getAppOpsInstancesApi(props.appId),
        getAppTopologyApi(props.appId)
      ])
      instancePage.value = ops
      topology.value = topo
    } else {
      const { data } = await getAppTopologyApi(props.appId)
      topology.value = data
      instancePage.value = null
    }
    scrollToCurrentRow()
  } finally {
    loading.value = false
  }
}

async function runOpsAction(label: string, action: () => Promise<{ data?: { success?: boolean, message?: string } }>) {
  try {
    await ElMessageBox.confirm(`确认执行「${label}」？拓扑变更约 1 分钟后生效。`, "操作确认", { type: "warning" })
    opsLoading.value = true
    const { data } = await action()
    if (data?.success === false) {
      ElMessage.error(data.message || "操作失败")
      return
    }
    ElMessage.success(data?.message || "操作已提交")
    await refreshInstances()
  } catch (e: unknown) {
    if (e !== "cancel") {
      ElMessage.error(e instanceof Error ? e.message : "操作失败")
    }
  } finally {
    opsLoading.value = false
  }
}

async function runInstanceAction(label: string, action: () => Promise<{ data?: { success?: boolean, message?: string } }>) {
  try {
    await ElMessageBox.confirm(`确认${label}？`, "操作确认", { type: "warning" })
    opsLoading.value = true
    const { data } = await action()
    if (!data?.success) {
      ElMessage.error(data?.message || "操作失败")
      return
    }
    ElMessage.success(data?.message || "操作成功")
    await refreshInstances()
  } catch (e: unknown) {
    if (e !== "cancel") {
      ElMessage.error(e instanceof Error ? e.message : "操作失败")
    }
  } finally {
    opsLoading.value = false
  }
}

function openAddSlave(masterInstanceId?: number) {
  addSlaveForm.masterInstanceId = masterInstanceId ?? masterInstances.value[0]?.id ?? 0
  addSlaveForm.slaveHost = ""
  addSlaveVisible.value = true
}

async function handleAddSlave() {
  if (!addSlaveForm.masterInstanceId || !addSlaveForm.slaveHost.trim()) {
    ElMessage.warning("请填写主库和从库地址")
    return
  }
  addSlaveVisible.value = false
  await runOpsAction("添加从库", () => addSlaveApi(props.appId, addSlaveForm.masterInstanceId, addSlaveForm.slaveHost.trim()))
}

/**
 * 删除节点：只把节点从平台移除，不向 Redis 下发任何命令。
 *
 * 三种集群类型走同一个接口。此前 cluster 类型走的是 CLUSTER FORGET，
 * 会真的把节点踢出 Redis 集群——那是运维动作，不该由「从平台移除」触发。
 */
async function handleDelNode(row: AppTopologyInstance) {
  const ok = await ElMessageBox.confirm(
    `确认将节点 ${row.hostPort} 从平台移除？<br/>`
    + `平台将不再纳管与采集该节点，<b>真实 Redis 集群不会有任何变更</b>（不会 CLUSTER FORGET、不会关闭进程）。`,
    "从平台移除节点",
    { type: "warning", dangerouslyUseHTMLString: true }
  ).then(() => true).catch(() => false)
  if (!ok) return
  opsLoading.value = true
  try {
    const { data } = await removeExternalRedisInstanceApi(props.appId, row.id)
    ElMessage.success(data?.message || "节点已从平台移除")
    await fetchData()
  } catch (e: unknown) {
    ElMessage.error(e instanceof Error ? e.message : "移除节点失败")
  } finally {
    opsLoading.value = false
  }
}

async function handleClusterFailover(row: AppTopologyInstance, failoverParam: string) {
  const label = failoverParam === "force" ? "强制 Failover" : failoverParam === "takeover" ? "Takeover Failover" : "Manual Failover"
  await runOpsAction(`${label} ${row.hostPort}`, () => clusterSlaveFailoverApi(props.appId, row.id, failoverParam || undefined))
}

async function handleSentinelFailover() {
  await runOpsAction("Sentinel Failover", () => sentinelFailoverApi(props.appId))
}

async function handleShutdownInstance(row: AppTopologyInstance) {
  await runInstanceAction("关闭节点", () => shutdownInstanceOpsApi(props.appId, row.id))
}

async function openBatchConfigDialog() {
  batchConfigOnly.value = true
  restartForm.mode = "config"
  restartForm.transferFlag = false
  restartForm.instanceIds = runnableRedisInstances.value.map(i => i.id)
  restartForm.configName = ""
  restartForm.configValues = [""]
  restartVisible.value = true
  batchConfigMap.value = {}
  const sample = runnableRedisInstances.value[0]
  if (!sample) return
  batchConfigLoading.value = true
  try {
    const { data } = await getInstanceConfigApi(sample.id)
    batchConfigMap.value = data ?? {}
  } finally {
    batchConfigLoading.value = false
  }
}

async function openInstanceLog(row: AppTopologyInstance) {
  instanceLogTarget.value = row
  instanceLogLines.value = []
  instanceLogVisible.value = true
  instanceLogLoading.value = true
  try {
    const { data } = await getInstanceLogApi(row.id)
    instanceLogLines.value = data?.lines ?? []
  } catch (e: unknown) {
    ElMessage.error(e instanceof Error ? e.message : "日志获取失败")
  } finally {
    instanceLogLoading.value = false
  }
}

async function openInstanceConfigModal(row: AppTopologyInstance) {
  instanceConfigTarget.value = row
  instanceConfigKey.value = ""
  instanceConfigKeyCustom.value = ""
  instanceConfigValue.value = ""
  instanceConfigVisible.value = true
  instanceConfigLoading.value = true
  try {
    const { data } = await getInstanceConfigApi(row.id)
    instanceConfigMap.value = data ?? {}
    if (!Object.keys(instanceConfigMap.value).length) {
      ElMessage.warning("获取配置项失败，请检查节点连接与密码")
    }
  } finally {
    instanceConfigLoading.value = false
  }
}

function fillInstanceConfigValue() {
  if (!instanceConfigKey.value) return
  instanceConfigValue.value = instanceConfigMap.value[instanceConfigKey.value] ?? ""
}

function fillBatchConfigValue() {
  const currentValue = batchConfigMap.value[restartForm.configName]
  restartForm.configValues = [currentValue ?? ""]
}

async function submitInstanceConfigChange() {
  const row = instanceConfigTarget.value
  if (!row) return
  const key = instanceConfigKeyCustom.value.trim() || instanceConfigKey.value.trim()
  if (!key) {
    ElMessage.warning("配置项不能为空")
    return
  }
  try {
    await ElMessageBox.confirm(`确认更新节点 ${row.id} 的配置：${key} = ${instanceConfigValue.value}？`, "修改配置", { type: "warning" })
  } catch {
    return
  }
  instanceConfigSaving.value = true
  try {
    const { data } = await updateInstanceConfigApi(row.id, {
      configName: key,
      configValue: instanceConfigValue.value
    })
    if (data?.rewriteSuccess) {
      ElMessage.success(data.message || "配置更新成功")
    } else {
      // message 里已带 Redis 给出的失败原因，文案较长，延长展示并允许手动关闭
      ElMessage({
        type: "warning",
        duration: 8000,
        showClose: true,
        message: data?.message || "运行时配置已更新，但未写入配置文件"
      })
    }
    instanceConfigVisible.value = false
  } catch (e: unknown) {
    ElMessage.error(e instanceof Error ? e.message : "配置更新失败")
  } finally {
    instanceConfigSaving.value = false
  }
}

async function handleScrollRestart() {
  if (restartForm.mode === "config") {
    if (!restartForm.configName.trim()) {
      ElMessage.warning("请填写配置项名称")
      return
    }
    if (!restartForm.instanceIds.length) {
      ElMessage.warning("修改配置需选择节点")
      return
    }
  }
  try {
    await ElMessageBox.confirm(
      `确认修改 ${restartForm.instanceIds.length} 个节点的配置 ${restartForm.configName}？`,
      "集群配置修改",
      { type: "warning" }
    )
  } catch {
    return
  }
  restartSubmitting.value = true
  try {
    if (restartForm.mode === "config") {
      const failures: string[] = []
      let rewriteWarnings = 0
      // 各节点的失败原因往往完全相同（例如都没用配置文件启动），去重后只展示一次
      const rewriteReasons = new Set<string>()
      for (const instanceId of restartForm.instanceIds) {
        try {
          const { data } = await updateInstanceConfigApi(instanceId, {
            configName: restartForm.configName.trim(),
            configValue: restartForm.configValues.join(" ").trim()
          })
          if (!data?.rewriteSuccess) {
            rewriteWarnings++
            if (data?.rewriteError) rewriteReasons.add(data.rewriteError)
          }
        } catch (error) {
          failures.push(`${instanceId}: ${error instanceof Error ? error.message : "更新失败"}`)
        }
      }
      if (failures.length) {
        ElMessage.error(`部分节点修改失败：${failures.join("；")}`)
        return
      }
      if (rewriteWarnings) {
        const reason = rewriteReasons.size ? `：${[...rewriteReasons].join("；")}` : ""
        ElMessage({
          type: "warning",
          duration: 8000,
          showClose: true,
          message:
            `${restartForm.instanceIds.length} 个节点运行时配置已更新，`
            + `其中 ${rewriteWarnings} 个节点未能写回配置文件${reason}。这些节点重启后将恢复原值。`
        })
      } else {
        ElMessage.success(`${restartForm.instanceIds.length} 个节点配置已更新并持久化`)
      }
    } else {
      const { data } = await scrollRestartApi(props.appId, {
        configFlag: false,
        transferFlag: restartForm.transferFlag,
        instanceIds: restartForm.instanceIds
      })
      ElMessage.success(data?.message || "滚动重启任务已提交")
    }
    restartVisible.value = false
  } catch (e: unknown) {
    ElMessage.error(e instanceof Error ? e.message : "操作失败")
  } finally {
    restartSubmitting.value = false
  }
}

watch(restartVisible, (visible) => {
  if (!visible) {
    batchConfigOnly.value = false
  }
})

watch(() => [props.appId, props.opsMode], fetchData, { immediate: true })

defineExpose({ refresh: fetchData })
</script>

<template>
  <div v-loading="loading" class="app-instance-list-panel">
    <div v-if="opsMode" class="app-ops-instance-toolbar">
      <el-button size="small" type="success" @click="addNodeVisible = true">
        增加节点
      </el-button>
      <el-button v-if="canBatchConfig" size="small" type="primary" @click="openBatchConfigDialog">
        集群配置修改
      </el-button>
      <el-button size="small" @click="passwordDrawerVisible = true">
        集群密码修改
      </el-button>
    </div>

    <el-table
      v-if="opsMode"
      v-loading="opsLoading"
      :data="instances"
      :row-class-name="opsTableRowClass"
      stripe
      border
      size="small"
      class="app-ops-instance-table"
    >
      <el-table-column prop="id" label="ID" min-width="56">
        <template #default="{ row }">
          <router-link :to="instanceDetailUrl(row.id)">
            {{ row.id }}
          </router-link>
          <span v-if="isCurrentInstance(row)" class="app-topology-current-tag">当前节点</span>
        </template>
      </el-table-column>
      <el-table-column label="节点" min-width="130" show-overflow-tooltip>
        <template #default="{ row }">
          <router-link :to="instanceDetailUrl(row.id)">
            {{ row.hostPort }}
          </router-link>
        </template>
      </el-table-column>
      <el-table-column label="节点状态" min-width="88">
        <template #default="{ row }">
          {{ row.statusDesc }}
          <div v-if="row.updateTimeDesc && (row.status === 2 || row.status === 3)" class="subtitle">
            {{ row.updateTimeDesc }}
          </div>
        </template>
      </el-table-column>
      <el-table-column prop="roleDesc" label="角色" min-width="56" />
      <el-table-column label="主节点ID" min-width="76">
        <template #default="{ row }">
          <router-link v-if="row.masterInstanceId > 0" :to="instanceDetailUrl(row.masterInstanceId)">
            {{ row.masterInstanceId }}
          </router-link>
        </template>
      </el-table-column>
      <el-table-column label="内存使用" min-width="140">
        <template #default="{ row }">
          <span v-if="isSentinelNode(row)">不监控</span>
          <template v-else>
            <div class="app-ops-mem-cell__text">
              {{ formatMem(row.usedMemory) }} Used/{{ formatMem(row.effectiveMaxMemory || row.maxMemory) }} Total
            </div>
            <div class="app-ops-mem-bar">
              <div
                class="app-ops-mem-bar__inner"
                :class="memBarClass(row.memUsePercent)"
                :style="{ width: `${memBarWidth(row.memUsePercent, row.usedMemory)}%` }"
              />
            </div>
          </template>
        </template>
      </el-table-column>
      <el-table-column prop="currItems" label="对象数" min-width="68" />
      <el-table-column label="连接数" min-width="68">
        <template #default="{ row }">
          <router-link :to="{ path: `/external/redis/detail/${row.id}`, query: { appId: String(appId), tab: 'instance_clientList' } }">
            {{ row.currConnections }}
          </router-link>
        </template>
      </el-table-column>
      <el-table-column prop="hitPercent" label="命中率" min-width="68" />
      <el-table-column label="碎片率" min-width="68">
        <template #default="{ row }">
          <span class="app-ops-frag" :class="fragClass(row)">{{ row.memFragmentationRatio }}</span>
        </template>
      </el-table-column>
      <el-table-column label="日志" min-width="52">
        <template #default="{ row }">
          <el-link type="primary" :underline="false" @click="openInstanceLog(row)">
            查看
          </el-link>
        </template>
      </el-table-column>
      <el-table-column label="节点运维" class-name="app-ops-col-actions" min-width="120">
        <template #default="{ row }">
          <div class="app-ops-ops-cell">
            <template v-if="row.status === 2 || row.status === 0 || row.status === -1">
              <el-button size="small" type="danger" title="仅从平台移除，不改动真实 Redis 集群" @click="handleDelNode(row)">
                删除节点
              </el-button>
            </template>
            <template v-else-if="row.status === 1">
              <el-button v-if="hostOpsEnabled" size="small" type="warning" @click="handleShutdownInstance(row)">
                关闭节点
              </el-button>
              <el-button
                v-if="!isSentinelNode(row)"
                size="small"
                type="primary"
                @click="openInstanceConfigModal(row)"
              >
                修改配置
              </el-button>
              <el-button
                v-if="hostOpsEnabled && row.masterInstanceId === 0 && !isSentinelNode(row)"
                size="small"
                type="primary"
                @click="openAddSlave(row.id)"
              >
                添加Slave
              </el-button>
              <el-button size="small" type="danger" title="仅从平台移除，不改动真实 Redis 集群" @click="handleDelNode(row)">
                删除节点
              </el-button>
            </template>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="故障转移" class-name="app-ops-col-failover" min-width="220">
        <template #default="{ row }">
          <div v-if="row.status === 1" class="app-ops-ops-cell app-ops-ops-cell--nowrap">
            <template v-if="isCluster && row.masterInstanceId > 0 && row.instanceType === 2">
              <el-button size="small" @click="handleClusterFailover(row, '')">
                Manual
              </el-button>
              <el-button size="small" type="primary" @click="handleClusterFailover(row, 'force')">
                Force
              </el-button>
              <el-button size="small" type="danger" @click="handleClusterFailover(row, 'takeover')">
                TakeOver
              </el-button>
            </template>
            <el-button v-if="isSentinel && isSentinelNode(row)" size="small" type="warning" @click="handleSentinelFailover">
              Sentinel Failover
            </el-button>
          </div>
        </template>
      </el-table-column>
    </el-table>

    <table v-else ref="topologyTableRef" class="app-topology-table">
      <thead>
        <tr>
          <td>ID</td>
          <td>节点</td>
          <td>节点状态</td>
          <td>内存使用</td>
          <td>对象数</td>
          <td>连接数</td>
          <td>命中率</td>
          <td>碎片率</td>
          <td>模块</td>
          <td>角色</td>
          <td>主节点ID</td>
        </tr>
      </thead>
      <tbody>
        <tr v-for="row in instances" :key="row.id" :class="topologyRowClass(row)">
          <td>
            <el-link type="primary" :underline="false" @click="openInstance(row.id)">
              {{ row.id }}
            </el-link>
            <el-icon v-if="row.masterStar" class="app-topology-star">
              <StarFilled />
            </el-icon>
            <span v-if="isCurrentInstance(row)" class="app-topology-current-tag">当前节点</span>
          </td>
          <td>
            <template v-if="row.external">
              <el-link type="primary" :underline="false" @click="handleExternalIpClick(row.id)">
                {{ row.ip }}
              </el-link>:{{ row.port }}
            </template>
            <template v-else>
              <el-link type="primary" :underline="false" @click="openInstance(row.id)">
                {{ row.ip }}
              </el-link>:{{ row.port }}
            </template>
          </td>
          <td>{{ row.statusDesc }}</td>
          <td class="app-topology-mem">
            <span v-if="isSentinelNode(row)">不监控</span>
            <template v-else>
              <div class="app-topology-mem__text">
                {{ formatMem(row.usedMemory) }}&nbsp;&nbsp;Used/
                {{ formatMem(row.effectiveMaxMemory || row.maxMemory) }}&nbsp;&nbsp;Total
              </div>
              <div class="app-topology-mem__bar">
                <div
                  class="app-topology-mem__bar-inner"
                  :class="readMemBarClass(row)"
                  :style="{ width: `${readMemBarWidth(row)}%` }"
                />
              </div>
            </template>
          </td>
          <td>{{ row.currItems }}</td>
          <td>
            <el-link type="primary" :underline="false" @click="openInstance(row.id, 'instance_clientList', 3)">
              {{ row.currConnections }}
            </el-link>
          </td>
          <td>{{ row.hitPercent }}</td>
          <td>
            <span class="app-topology-frag" :class="fragClass(row)">{{ row.memFragmentationRatio }}</span>
          </td>
          <td>
            <span v-for="mod in row.modules" :key="mod" class="app-topology-tag app-topology-tag--module">{{ mod }}</span>
          </td>
          <td>
            <span :class="{ 'text-danger': row.roleDesc === '未知' }">{{ row.roleDesc }}</span>
          </td>
          <td>
            <el-link
              v-if="row.masterInstanceId > 0"
              type="primary"
              :underline="false"
              @click="openInstance(row.masterInstanceId)"
            >
              {{ row.masterInstanceId }}
            </el-link>
          </td>
        </tr>
      </tbody>
    </table>

    <el-alert
      v-for="(err, i) in connectErrors"
      :key="`err-${i}`"
      type="warning"
      :closable="false"
      show-icon
      class="app-instance-list-panel__alert"
    >
      <template #title>
        <strong>节点连接异常：</strong>{{ err }}
      </template>
      <div class="text-muted">
        角色与指标可能来自历史采集缓存，请核对集群密码后等待采集刷新。
      </div>
    </el-alert>

    <el-alert
      v-if="opsMode && instancePage?.lossSlotsSegmentMap && Object.keys(instancePage.lossSlotsSegmentMap).length"
      type="warning"
      :closable="false"
      title="集群存在 slot 丢失"
      class="app-instance-list-panel__alert"
    />

    <el-dialog v-model="addNodeVisible" title="增加节点" width="520px" append-to-body destroy-on-close>
      <el-form label-position="top">
        <el-form-item label="节点地址">
          <el-input
            v-model="addNodeText"
            type="textarea"
            :rows="5"
            placeholder="每行一个，格式 ip:port&#10;例如：&#10;172.30.0.17:6379&#10;哨兵节点为 ip:port:masterName"
          />
        </el-form-item>
      </el-form>
      <div class="app-add-node-tip">
        节点需已在运行；平台只做登记与纳管，不会启动进程，也不会改动 Redis 自身的集群拓扑。<br>
        登记后会自动读取真实拓扑，回填该节点的主从归属，无需手动指定。
      </div>
      <template #footer>
        <el-button @click="addNodeVisible = false">
          取消
        </el-button>
        <el-button type="primary" :loading="addNodeSubmitting" @click="handleAddNode">
          确认
        </el-button>
      </template>
    </el-dialog>

    <el-drawer v-model="passwordDrawerVisible" title="集群密码修改" size="460px" append-to-body>
      <!-- 每次打开重新挂载，保证读到的是最新密码而不是上次缓存 -->
      <OpsPasswordTab v-if="passwordDrawerVisible" :app-id="appId" />
    </el-drawer>

    <el-dialog v-model="addSlaveVisible" title="添加从库" width="480px" append-to-body>
      <el-form label-width="100px">
        <el-form-item label="主库节点">
          <el-select v-model="addSlaveForm.masterInstanceId" style="width: 100%">
            <el-option v-for="inst in masterInstances" :key="inst.id" :label="inst.hostPort" :value="inst.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="从库地址">
          <el-input v-model="addSlaveForm.slaveHost" placeholder="ip:port" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="addSlaveVisible = false">
          取消
        </el-button>
        <el-button type="primary" @click="handleAddSlave">
          确认
        </el-button>
      </template>
    </el-dialog>

    <el-dialog
      v-model="instanceConfigVisible"
      :title="instanceConfigTarget ? `更新配置 — ${instanceConfigTarget.hostPort}` : '更新配置'"
      width="560px"
      append-to-body
    >
      <div v-loading="instanceConfigLoading">
        <el-alert
          type="info"
          :closable="false"
          show-icon
          :title="`来源：Redis CONFIG GET *，当前获取 ${Object.keys(instanceConfigMap).length} 项（包含空值）`"
          class="mb-3"
        />
        <el-form label-width="80px">
          <el-form-item label="配置项">
            <el-select
              v-model="instanceConfigKey"
              filterable
              clearable
              placeholder="请选择"
              style="width: 100%"
              @change="fillInstanceConfigValue"
            >
              <el-option
                v-for="opt in instanceConfigOptions"
                :key="opt.key"
                :label="opt.label"
                :value="opt.key"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="新增配置项">
            <el-input v-model="instanceConfigKeyCustom" placeholder="新增配置项" />
          </el-form-item>
          <el-form-item label="配置值">
            <el-select v-if="instanceConfigValueOptions.length" v-model="instanceConfigValue" style="width: 100%">
              <el-option v-for="value in instanceConfigValueOptions" :key="value" :label="value" :value="value" />
            </el-select>
            <el-input v-else v-model="instanceConfigValue" placeholder="配置值（允许空值）" />
          </el-form-item>
        </el-form>
      </div>
      <template #footer>
        <el-button @click="instanceConfigVisible = false">
          取消
        </el-button>
        <el-button type="primary" :loading="instanceConfigSaving" @click="submitInstanceConfigChange">
          确认
        </el-button>
      </template>
    </el-dialog>

    <el-dialog
      v-model="restartVisible"
      :title="batchConfigOnly ? '修改配置' : '滚动重启 / 修改配置'"
      width="560px"
      append-to-body
    >
      <el-alert
        v-if="batchConfigOnly"
        type="info"
        :closable="false"
        show-icon
        :title="`配置项来源：目标集群数据节点的 Redis CONFIG GET *，当前获取 ${Object.keys(batchConfigMap).length} 项（包含空值）`"
        class="mb-3"
      />
      <el-form label-width="120px">
        <el-form-item v-if="!batchConfigOnly" label="操作类型">
          <el-radio-group v-model="restartForm.mode">
            <el-radio value="restart">
              滚动重启
            </el-radio>
            <el-radio value="config">
              修改配置并重启
            </el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item :label="restartForm.mode === 'config' || batchConfigOnly ? '目标节点' : '节点（可选）'">
          <el-select
            v-model="restartForm.instanceIds"
            multiple
            filterable
            style="width: 100%"
            :placeholder="restartForm.mode === 'config' || batchConfigOnly ? '请选择节点' : '不选则重启全部运行中节点'"
          >
            <el-option
              v-for="inst in (restartForm.mode === 'config' || batchConfigOnly ? runnableRedisInstances : runnableInstances)"
              :key="inst.id"
              :label="`${inst.id} - ${inst.hostPort} (${inst.roleDesc})`"
              :value="inst.id"
            />
          </el-select>
        </el-form-item>
        <template v-if="restartForm.mode === 'config' || batchConfigOnly">
          <el-form-item label="配置项">
            <el-select
              v-model="restartForm.configName"
              filterable
              allow-create
              clearable
              default-first-option
              :loading="batchConfigLoading"
              placeholder="选择或输入配置项"
              style="width: 100%"
              @change="fillBatchConfigValue"
            >
              <el-option v-for="item in batchConfigOptions" :key="item.key" :label="item.label" :value="item.key" />
            </el-select>
          </el-form-item>
          <el-form-item label="配置值">
            <el-select v-if="batchConfigValueOptions.length" v-model="restartForm.configValues[0]" style="width: 100%">
              <el-option v-for="value in batchConfigValueOptions" :key="value" :label="value" :value="value" />
            </el-select>
            <el-input v-else v-model="restartForm.configValues[0]" placeholder="配置值（允许空值）" />
          </el-form-item>
        </template>
      </el-form>
      <template #footer>
        <el-button @click="restartVisible = false">
          取消
        </el-button>
        <el-button type="primary" :loading="restartSubmitting" @click="handleScrollRestart">
          确认
        </el-button>
      </template>
    </el-dialog>

    <el-dialog
      v-model="instanceLogVisible"
      :title="`节点日志 - ${instanceLogTarget?.hostPort ?? ''}`"
      width="820px"
    >
      <div v-loading="instanceLogLoading" class="app-ops-instance-log">
        <pre v-if="instanceLogLines.length">{{ instanceLogLines.join("\n") }}</pre>
        <el-empty v-else-if="!instanceLogLoading" description="暂无日志" />
      </div>
      <template #footer>
        <el-button @click="instanceLogVisible = false">
          关闭
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style lang="scss" scoped>
.app-instance-list-panel__alert {
  margin-top: 12px;
}

.text-muted {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-top: 4px;
}

.text-danger {
  color: #d9534f;
}

.subtitle {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.app-ops-instance-log {
  min-height: 120px;
  max-height: 60vh;
  overflow: auto;

  pre {
    margin: 0;
    font-size: 12px;
    line-height: 1.6;
    white-space: pre-wrap;
    word-break: break-all;
  }
}

a {
  color: var(--el-color-primary);
  text-decoration: none;
}

a:hover {
  text-decoration: underline;
}
</style>
