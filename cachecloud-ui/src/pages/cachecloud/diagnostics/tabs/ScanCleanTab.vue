<script lang="ts" setup>
import type { DiagnosticAppOption, DiagnosticInstance, DiagnosticTask } from "@/api/cachecloud"
import {
  deleteDiagnosticTaskApi,
  getDiagnosticInstancesApi,
  getDiagnosticResultApi,
  getDiagnosticTasksApi,
  submitDiagnosticTaskApi
} from "@/api/cachecloud"
import { formatClusterNo } from "@/common/utils/cluster-no"
import "@/common/assets/styles/diagnostics.scss"

const props = defineProps<{ apps: DiagnosticAppOption[], active: boolean }>()

const selectedAppId = ref<number | undefined>()
const instances = ref<DiagnosticInstance[]>([])
const selectedNodes = ref<string[]>([])
const tasks = ref<DiagnosticTask[]>([])
const loading = ref(false)
const submitting = ref(false)
const resultVisible = ref(false)
const resultTitle = ref("执行结果")
const resultText = ref("")
let taskPollTimer: ReturnType<typeof setInterval> | null = null
let taskRequestId = 0

const operateType = ref("1")
const pattern = ref("")
const ttlLess = ref("")
const ttlMore = ref("")
const ttlResetLess = ref("")
const ttlResetMore = ref("")
const perCount = ref("100")
const parentTaskId = ref("")
const diagnosticStatus = ref<number | "">("")
const filterAppId = ref("")

const OPERATE_OPTIONS = [
  { value: "1", label: "分析" },
  { value: "2", label: "重置TTL" },
  { value: "3", label: "删除" }
]

async function onAppChange(appId: number) {
  selectedAppId.value = appId
  selectedNodes.value = []
  const { data } = await getDiagnosticInstancesApi(appId)
  instances.value = data ?? []
}

function buildParams() {
  return JSON.stringify({
    nodes: selectedNodes.value.join(","),
    operateType: operateType.value,
    pattern: pattern.value,
    ttlLess: ttlLess.value,
    ttlMore: ttlMore.value,
    ttlResetLess: ttlResetLess.value,
    ttlResetMore: ttlResetMore.value,
    perCount: perCount.value || "100"
  })
}

async function fetchTasks(silent = false) {
  const requestId = ++taskRequestId
  if (!silent) loading.value = true
  try {
    const params: Record<string, unknown> = { tabTag: "scanClean" }
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
  }, 5000)
}

async function handleSubmit() {
  if (!selectedAppId.value) {
    ElMessage.warning("请选择集群")
    return
  }
  if (!selectedNodes.value.length) {
    ElMessage.warning("请选择节点")
    return
  }
  if (!pattern.value) {
    ElMessage.warning("请填写键匹配字符串")
    return
  }
  if (operateType.value === "2") {
    if (!ttlResetLess.value || !ttlResetMore.value || Number(ttlResetLess.value) <= Number(ttlResetMore.value)) {
      ElMessage.warning("请填写 TTL 重置时间范围，且结束时间须大于开始时间")
      return
    }
  }
  const count = Number(perCount.value || "100")
  if (!Number.isInteger(count) || count < 50 || count > 1000) {
    ElMessage.warning("每次扫描数量请输入 50-1000 之间的整数")
    return
  }
  submitting.value = true
  try {
    const { data } = await submitDiagnosticTaskApi({
      tabTag: "scanClean",
      appId: selectedAppId.value,
      nodes: selectedNodes.value.join(","),
      params: buildParams()
    })
    const taskId = Number(data?.taskId)
    if (Number.isSafeInteger(taskId) && taskId > 0) {
      filterAppId.value = ""
      parentTaskId.value = ""
      diagnosticStatus.value = ""
      ElMessage.success(`任务已提交，任务 ID：${taskId}`)
      // 服务端会把仍在排队/运行的父任务作为"诊断中"返回，无需等子任务落库
      await fetchTasks()
    } else {
      ElMessage.success("任务已提交")
      await fetchTasks()
    }
  } finally {
    submitting.value = false
  }
}

async function viewResult(row: DiagnosticTask) {
  if (!row.redisKey) return
  const { data } = await getDiagnosticResultApi(row.redisKey, row.type)
  resultTitle.value = `执行结果 - ${row.node || "-"}（共 ${Number(data?.count ?? 0)} 条）`
  resultText.value = data?.listResult?.join("\n") || "无结果"
  resultVisible.value = true
}

async function deleteTask(row: DiagnosticTask) {
  await ElMessageBox.confirm(
    `确认删除节点 ${row.node || "-"} 的清理查询记录及对应结果？`,
    "删除查询",
    { type: "warning", confirmButtonText: "删除", cancelButtonText: "取消" }
  )
  await deleteDiagnosticTaskApi(row.id)
  ElMessage.success("查询记录已删除")
  await fetchTasks()
}

const taskEmptyText = computed(() => "暂无数据")

function statusLabel(status: number) {
  if (status === 0) return "诊断中"
  if (status === 1) return "诊断完成"
  if (status === 2) return "诊断异常"
  return String(status)
}

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
    <div class="manage-diagnostic-panel">
      <h4 class="manage-diagnostic-panel__title">数据分析清理任务</h4>
      <el-form label-position="top">
        <div class="diag-scan-clean-grid">
          <el-form-item label="集群">
            <el-select
              :model-value="selectedAppId"
              placeholder="选择集群"
              filterable
              @change="onAppChange"
            >
              <el-option v-for="app in apps" :key="app.appId" :label="`【${formatClusterNo(app.clusterNo, app.appId)}】${app.appName}`" :value="app.appId" />
            </el-select>
          </el-form-item>
          <el-form-item label="节点">
            <el-select v-model="selectedNodes" multiple collapse-tags placeholder="选择节点">
              <el-option v-for="n in instances" :key="n.hostPort" :label="n.hostPort" :value="n.hostPort" />
            </el-select>
          </el-form-item>
          <el-form-item label="操作类型">
            <el-select v-model="operateType">
              <el-option v-for="opt in OPERATE_OPTIONS" :key="opt.value" :label="opt.label" :value="opt.value" />
            </el-select>
          </el-form-item>
          <el-form-item label="键匹配 pattern">
            <el-input v-model="pattern" placeholder="例如 user:*" />
          </el-form-item>
          <el-form-item label="TTL 小于(秒)">
            <el-input v-model="ttlLess" />
          </el-form-item>
          <el-form-item label="TTL 大于(秒)">
            <el-input v-model="ttlMore" />
          </el-form-item>
          <el-form-item v-if="operateType === '2'" label="TTL 重置起(秒)">
            <el-input v-model="ttlResetLess" />
          </el-form-item>
          <el-form-item v-if="operateType === '2'" label="TTL 重置止(秒)">
            <el-input v-model="ttlResetMore" />
          </el-form-item>
          <el-form-item label="每次扫描数量">
            <el-input v-model="perCount" placeholder="50-1000，默认 100" />
          </el-form-item>
        </div>
        <el-button type="primary" :loading="submitting" @click="handleSubmit">执行任务</el-button>
        <el-button :loading="loading" @click="fetchTasks()">
          <el-icon><Refresh /></el-icon>
          <span>刷新</span>
        </el-button>
      </el-form>
    </div>

    <h4 class="manage-diagnostic-panel__title">数据清理任务列表</h4>
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
        <el-button type="success" @click="fetchTasks()">查询</el-button>
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
      <el-table-column prop="node" label="节点" width="160" />
      <el-table-column prop="diagnosticCondition" label="诊断条件" min-width="160" show-overflow-tooltip />
      <el-table-column prop="createTime" label="创建时间" width="170" />
      <el-table-column label="状态" width="90">
        <template #default="{ row }">{{ statusLabel(row.status) }}</template>
      </el-table-column>
      <el-table-column prop="formatCostTime" label="耗时" width="100" />
      <el-table-column label="结果" width="90" fixed="right">
        <template #default="{ row }">
          <el-button v-if="row.redisKey" type="primary" size="small" @click="viewResult(row)">查看</el-button>
        </template>
      </el-table-column>
      <el-table-column label="删除查询" width="100" fixed="right">
        <template #default="{ row }">
          <el-button
            plain
            type="danger"
            size="small"
            :disabled="row.status === 0"
            @click="deleteTask(row)"
          >删除查询</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="resultVisible" :title="resultTitle" width="60%">
      <pre class="manage-diagnostic-online-verify__output">{{ resultText }}</pre>
    </el-dialog>
  </div>
</template>
