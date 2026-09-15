<script lang="ts" setup>
import type { DiagnosticAppOption, DiagnosticInstance, DiagnosticTask } from "@/api/cachecloud"
import {
  confirmDiagnosticDeleteApi,
  deleteDiagnosticTaskApi,
  getDiagnosticInstancesApi,
  getDiagnosticResultApi,
  getDiagnosticTasksApi,
  submitDiagnosticTaskApi
} from "@/api/cachecloud"
import { formatClusterNo } from "@/common/utils/cluster-no"
import "@/common/assets/styles/diagnostics.scss"

export interface TaskTabConfig {
  submitTitle: string
  listTitle: string
  submitLabel: string
  fields: "scan" | "deleteKey" | "slotAnalysis"
}

const props = defineProps<{
  tabTag: string
  apps: DiagnosticAppOption[]
  config: TaskTabConfig
  active: boolean
}>()

const SCAN_SIZE_OPTIONS = [
  { value: "20", label: "top 20" },
  { value: "50", label: "top 50" },
  { value: "100", label: "top 100" },
  { value: "200", label: "top 200" },
  { value: "500", label: "top 500" }
]

const REDIS_CLUSTER_TYPE = 2

const selectedAppId = ref<number | undefined>()
const instances = ref<DiagnosticInstance[]>([])
const tasks = ref<DiagnosticTask[]>([])
const loading = ref(false)
const submitting = ref(false)
const resultVisible = ref(false)
const resultTitle = ref("诊断结果")
const resultText = ref("")
let taskPollTimer: ReturnType<typeof setInterval> | null = null
let taskRequestId = 0

const selectedNodes = ref<string[]>([])
const parentTaskId = ref("")
const diagnosticStatus = ref<number | "">("")
const filterAppId = ref("")

const pattern = ref("")
const topSize = ref("20")

const selectableApps = computed(() => {
  if (props.config.fields !== "slotAnalysis") {
    return props.apps
  }
  return props.apps.filter(app => app.type === REDIS_CLUSTER_TYPE || app.typeDesc === "redis-cluster")
})

function buildParams() {
  switch (props.config.fields) {
    case "scan":
      return `${pattern.value},${topSize.value}`
    case "deleteKey":
      return pattern.value
    default:
      return ""
  }
}

async function onAppChange(appId: number) {
  selectedAppId.value = appId
  selectedNodes.value = []
  const { data } = await getDiagnosticInstancesApi(appId)
  instances.value = data ?? []
}

async function fetchTasks(silent = false) {
  const requestId = ++taskRequestId
  if (!silent) loading.value = true
  try {
    const params: Record<string, unknown> = { tabTag: props.tabTag }
    if (filterAppId.value) params.appId = Number(filterAppId.value)
    if (parentTaskId.value) params.parentTaskId = Number(parentTaskId.value)
    if (diagnosticStatus.value !== "") params.status = diagnosticStatus.value
    const { data } = await getDiagnosticTasksApi(params)
    if (requestId === taskRequestId) tasks.value = data ?? []
  } catch {
    // 请求层已统一提示；轮询失败时保留上一轮数据。
  } finally {
    if (requestId === taskRequestId) loading.value = false
  }
}

function stopTaskPolling() {
  if (taskPollTimer) {
    clearInterval(taskPollTimer)
    taskPollTimer = null
  }
}

function startTaskPolling() {
  stopTaskPolling()
  taskPollTimer = setInterval(() => {
    if (props.active) void fetchTasks(true)
  }, 2000)
}

async function handleSubmit() {
  if (!selectedAppId.value) {
    ElMessage.warning("请选择集群")
    return
  }
  if (props.config.fields === "slotAnalysis" && !selectableApps.value.some(app => app.appId === selectedAppId.value)) {
    ElMessage.warning("slot 分析仅支持 Redis Cluster 集群")
    return
  }
  if (props.config.fields === "deleteKey" && !pattern.value) {
    ElMessage.warning("请输入匹配 pattern")
    return
  }
  submitting.value = true
  try {
    const { data } = await submitDiagnosticTaskApi({
      tabTag: props.tabTag,
      appId: selectedAppId.value,
      nodes: selectedNodes.value.join(","),
      params: buildParams()
    })
    const taskId = Number(data?.taskId)
    if (Number.isSafeInteger(taskId) && taskId > 0) {
      filterAppId.value = ""
      parentTaskId.value = ""
      diagnosticStatus.value = ""
      ElMessage.success(`诊断任务已提交，任务 ID：${taskId}`)
      // 服务端会把仍在排队/运行的父任务作为"诊断中"返回，刷新页面也不会丢
      await fetchTasks(true)
    } else {
      ElMessage.success("诊断任务已提交")
      await fetchTasks()
    }
  } finally {
    submitting.value = false
  }
}

async function viewResult(row: DiagnosticTask, err = false) {
  if (!row.redisKey) return
  const { data } = await getDiagnosticResultApi(row.redisKey, row.type, err)
  const count = Number(data?.count ?? 0)
  resultTitle.value = `${err ? "异常结果" : "诊断结果"} - ${row.node || "-"}（共 ${count} 条）`
  if (data?.listResult?.length) {
    resultText.value = data.listResult.join("\n")
  } else if (data?.mapResult) {
    const entries = Object.entries(data.mapResult)
    if (props.config.fields === "slotAnalysis") {
      entries.sort((a, b) => Number(a[0]) - Number(b[0]))
    }
    resultText.value = entries.map(([k, v]) => `${k}: ${v}`).join("\n")
  } else {
    resultText.value = "无结果"
  }
  resultVisible.value = true
}

async function deleteTask(row: DiagnosticTask) {
  await ElMessageBox.confirm(
    `确认删除节点 ${row.node || "-"} 的查询记录${row.redisKey ? "及对应结果" : ""}？`,
    "删除查询",
    { type: "warning", confirmButtonText: "删除", cancelButtonText: "取消" }
  )
  await deleteDiagnosticTaskApi(row.id)
  ElMessage.success("查询记录已删除")
  await fetchTasks()
}

async function confirmDelete(row: DiagnosticTask) {
  await ElMessageBox.confirm(
    `将对 ${row.node || "-"} 非阻塞批量删除预览中的 ${row.resultCount || 0} 个 key，确认继续？`,
    "提交删除",
    { type: "warning", confirmButtonText: "提交删除", cancelButtonText: "取消" }
  )
  const { data } = await confirmDiagnosticDeleteApi(row.id)
  ElMessage.success(`删除任务已提交，任务 ID：${data?.taskId ?? "-"}`)
  await fetchTasks()
}

const taskEmptyText = computed(() => "暂无数据")

function deleteStatusLabel(status: number) {
  if (status === 0) return "无匹配key"
  if (status === 1) return "已删除"
  if (status === 2) return "未删除"
  return "-"
}

function statusLabel(status: number) {
  if (status === 0) return "诊断中"
  if (status === 1) return "诊断完成"
  if (status === 2) return "诊断异常"
  return String(status)
}
watch(selectableApps, (apps) => {
  if (selectedAppId.value && !apps.some(app => app.appId === selectedAppId.value)) {
    selectedAppId.value = undefined
    selectedNodes.value = []
    instances.value = []
  }
})
watch(() => props.active, (active) => {
  if (active) {
    void fetchTasks()
    startTaskPolling()
  } else {
    stopTaskPolling()
    taskRequestId++
    loading.value = false
  }
}, { immediate: true })
onBeforeUnmount(stopTaskPolling)
</script>

<template>
  <div class="manage-diagnostic-tab-page">
    <h4 class="manage-diagnostic-panel__title">
      {{ config.submitTitle }}
    </h4>
    <el-form inline class="diag-task-form">
      <el-form-item label="集群">
        <el-select
          :model-value="selectedAppId"
          placeholder="选择集群"
          filterable
          style="width: 280px"
          @change="onAppChange"
        >
          <el-option
            v-for="app in selectableApps"
            :key="app.appId"
            :label="`【${formatClusterNo(app.clusterNo, app.appId)}】${app.appName}`"
            :value="app.appId"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="节点">
        <el-select v-model="selectedNodes" multiple collapse-tags placeholder="所有主节点" style="width: 260px">
          <el-option v-for="n in instances" :key="n.hostPort" :label="n.hostPort" :value="n.hostPort" />
        </el-select>
      </el-form-item>

      <template v-if="config.fields === 'scan'">
        <el-form-item label="数量">
          <el-select v-model="topSize" style="width: 120px">
            <el-option v-for="opt in SCAN_SIZE_OPTIONS" :key="opt.value" :label="opt.label" :value="opt.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="pattern">
          <el-input v-model="pattern" placeholder="匹配模式 pattern" style="width: 180px" />
        </el-form-item>
      </template>

      <template v-else-if="config.fields === 'deleteKey'">
        <el-form-item label="pattern">
          <el-input v-model="pattern" placeholder="匹配 pattern" style="width: 200px" />
        </el-form-item>
      </template>

      <el-form-item>
        <el-button
          :type="config.fields === 'deleteKey' ? 'danger' : 'primary'"
          :loading="submitting"
          @click="handleSubmit"
        >
          {{ config.submitLabel }}
        </el-button>
        <el-button :loading="loading" @click="fetchTasks()">
          <el-icon><Refresh /></el-icon>
          <span>刷新</span>
        </el-button>
      </el-form-item>
    </el-form>

    <h4 class="manage-diagnostic-panel__title">
      {{ config.listTitle }}
    </h4>
    <el-form inline class="diag-task-filter">
      <el-form-item label="集群编码">
        <el-input v-model="filterAppId" placeholder="集群编码" style="width: 120px" clearable />
      </el-form-item>
      <el-form-item label="任务id">
        <el-input v-model="parentTaskId" placeholder="任务id" style="width: 120px" clearable />
      </el-form-item>
      <el-form-item label="诊断状态">
        <el-select v-model="diagnosticStatus" placeholder="全部" clearable style="width: 120px">
          <el-option label="诊断中" :value="0" />
          <el-option label="诊断完成" :value="1" />
          <el-option label="诊断异常" :value="2" />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-button type="success" @click="fetchTasks()">
          查询
        </el-button>
      </el-form-item>
    </el-form>

    <el-table v-loading="loading" :data="tasks" :empty-text="taskEmptyText" stripe border size="small">
      <el-table-column type="index" label="序号" width="60" />
      <el-table-column prop="appName" label="集群" min-width="160" show-overflow-tooltip>
        <template #default="{ row }">
          {{ row.appName || "-" }}
        </template>
      </el-table-column>
      <el-table-column prop="parentTaskId" label="任务id" width="90" />
      <el-table-column prop="taskId" label="子任务id" width="90" />
      <el-table-column prop="node" label="节点" width="160" />
      <el-table-column prop="diagnosticCondition" label="诊断条件" min-width="160" show-overflow-tooltip />
      <el-table-column v-if="config.fields === 'deleteKey'" label="删除状态" width="100">
        <template #default="{ row }">
          {{ deleteStatusLabel(row.deleteStatus) }}
        </template>
      </el-table-column>
      <el-table-column prop="createTime" label="创建时间" width="170" />
      <el-table-column label="诊断状态" width="90">
        <template #default="{ row }">
          {{ statusLabel(row.status) }}
        </template>
      </el-table-column>
      <el-table-column prop="formatCostTime" label="诊断耗时" width="100" />
      <el-table-column label="诊断结果" width="100" :fixed="config.fields === 'deleteKey' ? undefined : 'right'">
        <template #default="{ row }">
          <span v-if="config.fields === 'deleteKey' && row.status === 1">
            {{ row.resultCount || 0 }} 个
          </span>
          <template v-else-if="config.fields === 'slotAnalysis' && row.redisKey">
            <el-button link type="primary" size="small" @click="viewResult(row)">
              分布
            </el-button>
            <el-button link type="danger" size="small" @click="viewResult(row, true)">
              异常
            </el-button>
          </template>
          <el-button v-else-if="row.redisKey" type="primary" size="small" @click="viewResult(row)">
            查看
          </el-button>
        </template>
      </el-table-column>
      <el-table-column v-if="config.fields === 'deleteKey'" label="操作" width="270" fixed="right">
        <template #default="{ row }">
          <div class="diag-task-actions">
            <el-button
              type="primary"
              size="small"
              :disabled="!row.redisKey || row.resultCount <= 0"
              @click="viewResult(row)"
            >
              查看预览
            </el-button>
            <el-button
              type="danger"
              size="small"
              :disabled="row.status !== 1 || row.deleteStatus !== 2"
              @click="confirmDelete(row)"
            >
              提交删除
            </el-button>
            <el-button
              plain
              type="danger"
              size="small"
              :disabled="row.status === 0"
              @click="deleteTask(row)"
            >
              删除清理记录
            </el-button>
          </div>
        </template>
      </el-table-column>
      <el-table-column v-else label="删除查询" width="100" fixed="right">
        <template #default="{ row }">
          <el-button
            plain
            type="danger"
            size="small"
            :disabled="row.status === 0"
            @click="deleteTask(row)"
          >
            删除查询
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="resultVisible" :title="resultTitle" width="60%">
      <pre class="manage-diagnostic-online-verify__output">{{ resultText }}</pre>
    </el-dialog>
  </div>
</template>

<style scoped>
.diag-task-actions {
  display: flex;
  align-items: center;
  gap: 6px;
  white-space: nowrap;
}

.diag-task-actions :deep(.el-button + .el-button) {
  margin-left: 0;
}
</style>
