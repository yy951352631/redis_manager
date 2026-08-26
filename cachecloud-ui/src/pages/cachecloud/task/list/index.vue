<script lang="ts" setup>
import type { TaskFlowDetail, TaskFlowStep, TaskListItem } from "@/api/cachecloud"
import { executeTaskApi, getTaskFlowApi, getTaskListApi } from "@/api/cachecloud"
import { formatClusterNo } from "@/common/utils/cluster-no"
import { Search } from "@element-plus/icons-vue"
import "@/common/assets/styles/task.scss"

const route = useRoute()
const loading = ref(false)
const items = ref<TaskListItem[]>([])
const total = ref(0)
const flowVisible = ref(false)
const flowLoading = ref(false)
const flow = ref<TaskFlowDetail | null>(null)
const activeLogSteps = ref<string[]>([])
const flowPollTimer = ref<ReturnType<typeof setInterval> | null>(null)
const activeFlowTaskId = ref(0)

const FINISHED_TASK_STATUS = new Set([2, 4, 6])

const query = reactive({
  searchTaskId: "",
  appId: "",
  className: "",
  status: undefined as number | undefined,
  pageNo: 1,
  pageSize: 30
})

const STEP_STATUS_TAG: Record<number, "info" | "warning" | "success" | "danger"> = {
  0: "info",
  1: "warning",
  2: "danger",
  4: "success",
  5: "info"
}

const TASK_STATUS_TAG: Record<number, "info" | "warning" | "success" | "danger"> = {
  0: "info",
  1: "warning",
  2: "danger",
  4: "success",
  5: "info",
  6: "danger"
}

const doneStepCount = computed(() => {
  if (!flow.value?.steps?.length) return 0
  return flow.value.steps.filter(s => s.status === 4 || s.status === 5).length
})

const progressBarClass = computed(() => {
  if (!flow.value) return ""
  const status = flow.value.status
  if (status === 4) return "is-finished"
  if (status === 2 || status === 6) return "is-abort"
  if (status === 1) return "is-active"
  return ""
})

const progressWidth = computed(() => {
  if (!flow.value) return 0
  const value = flow.value.progressValue
  return Math.max(0, Math.min(100, value >= 0 ? value : 0))
})

function stepStatusTag(status: number) {
  return STEP_STATUS_TAG[status] ?? "info"
}

function taskStatusTag(status: number) {
  return TASK_STATUS_TAG[status] ?? "info"
}

function getLogLevel(line: string) {
  if (/\sERROR\s/.test(line)) return "error"
  if (/\sWARN\s/.test(line)) return "warn"
  if (/\sDEBUG\s/.test(line)) return "debug"
  if (/\sINFO\s/.test(line)) return "info"
  return "default"
}

function logLevelClass(line: string) {
  const level = getLogLevel(line)
  return level === "default" ? "" : `is-${level}`
}

function syncActiveLogSteps(detail: TaskFlowDetail | null) {
  if (!detail?.steps?.length) {
    activeLogSteps.value = []
    return
  }
  const running = detail.steps.find(s => s.status === 1)
  const failed = [...detail.steps].reverse().find(s => s.status === 2)
  const current = detail.currentStep
    ? detail.steps.find(s => s.stepName === detail.currentStep)
    : undefined
  const target = running ?? current ?? failed ?? detail.steps[detail.steps.length - 1]
  activeLogSteps.value = target ? [target.stepName] : []
}

async function fetchList() {
  loading.value = true
  try {
    const params: Record<string, unknown> = {
      pageNo: query.pageNo,
      pageSize: query.pageSize
    }
    if (query.searchTaskId) params.searchTaskId = Number(query.searchTaskId)
    if (query.appId) params.appId = Number(query.appId)
    if (query.className) params.className = query.className
    if (query.status !== undefined) params.status = query.status
    const { data } = await getTaskListApi(params)
    items.value = data?.items ?? []
    total.value = data?.totalCount ?? 0
  } finally {
    loading.value = false
  }
}

function stopFlowPolling() {
  if (flowPollTimer.value) {
    clearInterval(flowPollTimer.value)
    flowPollTimer.value = null
  }
}

function isFlowFinished(detail: TaskFlowDetail | null) {
  return !!detail && FINISHED_TASK_STATUS.has(detail.status)
}

async function refreshFlow(taskId: number, options?: { silent?: boolean }) {
  if (!options?.silent) {
    flowLoading.value = true
  }
  try {
    const { data } = await getTaskFlowApi(taskId)
    flow.value = data
    syncActiveLogSteps(data)
    if (isFlowFinished(data)) {
      stopFlowPolling()
    }
    return data
  } finally {
    if (!options?.silent) {
      flowLoading.value = false
    }
  }
}

function startFlowPolling(taskId: number) {
  stopFlowPolling()
  activeFlowTaskId.value = taskId
  flowPollTimer.value = setInterval(() => {
    if (!flowVisible.value || activeFlowTaskId.value !== taskId) {
      stopFlowPolling()
      return
    }
    void refreshFlow(taskId, { silent: true })
  }, 2000)
}

async function openFlow(taskId: number) {
  flowVisible.value = true
  flow.value = null
  activeLogSteps.value = []
  stopFlowPolling()
  const data = await refreshFlow(taskId)
  if (data && !isFlowFinished(data)) {
    startFlowPolling(taskId)
  }
}

watch(flowVisible, (visible) => {
  if (!visible) {
    stopFlowPolling()
    activeFlowTaskId.value = 0
  }
})

onBeforeUnmount(stopFlowPolling)

async function handleExecute(row: TaskListItem) {
  await ElMessageBox.confirm("确认执行任务？", "提示", { type: "warning" })
  await executeTaskApi(row.id)
  ElMessage.success("已触发执行")
  openFlow(row.id)
}

function stepDuration(step: TaskFlowStep) {
  if (!step.startTime || !step.endTime) return "-"
  const start = new Date(step.startTime).getTime()
  const end = new Date(step.endTime).getTime()
  if (Number.isNaN(start) || Number.isNaN(end) || end < start) return "-"
  const seconds = Math.round((end - start) / 1000)
  return `${seconds}s`
}

onMounted(async () => {
  const status = route.query.status
  if (status !== undefined && status !== "") {
    const parsed = Number(status)
    if (!Number.isNaN(parsed)) {
      query.status = parsed
    }
  }
  await fetchList()
  const taskId = Number(route.query.taskId)
  if (!Number.isNaN(taskId) && taskId > 0) {
    openFlow(taskId)
  }
})
</script>

<template>
  <div class="task-page">
    <div class="page-header">
      <div class="page-actions">
        <el-input v-model="query.searchTaskId" placeholder="任务ID" clearable style="width: 120px" />
        <el-input v-model="query.appId" placeholder="集群编码" clearable style="width: 120px" />
        <el-input v-model="query.className" placeholder="className" clearable style="width: 180px" />
        <el-button type="primary" :icon="Search" @click="fetchList">查询</el-button>
      </div>
    </div>
    <el-card shadow="never">
      <el-table v-loading="loading" :data="items" stripe border>
        <el-table-column prop="id" label="ID" width="90" />
        <el-table-column label="集群编码" width="90">
          <template #default="{ row }">
            {{ formatClusterNo(row.clusterNo, row.appId) }}
          </template>
        </el-table-column>
        <el-table-column prop="className" label="className" min-width="200" show-overflow-tooltip />
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="taskStatusTag(row.status)" size="small">{{ row.statusDesc }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="progress" label="进度" width="90" />
        <el-table-column prop="createTime" label="创建时间" width="170" />
        <el-table-column label="操作" width="180" fixed="right">
          <template #default="{ row }">
            <div class="table-actions">
              <el-button type="primary" size="small" @click="openFlow(row.id)">详情</el-button>
              <el-button v-if="!row.finished" type="success" size="small" @click="handleExecute(row)">执行</el-button>
            </div>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination
        v-model:current-page="query.pageNo"
        :page-size="query.pageSize"
        :total="total"
        layout="total, prev, pager, next"
        class="pager"
        @current-change="fetchList"
      />
    </el-card>

    <el-drawer
      v-model="flowVisible"
      title="任务流详情"
      size="68%"
      class="task-flow-drawer"
      destroy-on-close
    >
      <div v-loading="flowLoading" class="task-flow-detail">
        <template v-if="flow">
          <section class="task-flow-summary">
            <h4 class="task-flow-summary__title">基本信息</h4>
            <div class="task-flow-kv">
              <div class="task-flow-kv__item">
                <span class="task-flow-kv__label">任务 ID</span>
                <span class="task-flow-kv__value">{{ flow.taskId }}</span>
              </div>
              <div class="task-flow-kv__item">
                <span class="task-flow-kv__label">集群</span>
                <span class="task-flow-kv__value">{{ flow.appName }} ({{ flow.appId }})</span>
              </div>
              <div class="task-flow-kv__item">
                <span class="task-flow-kv__label">任务名</span>
                <span class="task-flow-kv__value">{{ flow.className }}</span>
              </div>
              <div class="task-flow-kv__item">
                <span class="task-flow-kv__label">状态</span>
                <span class="task-flow-kv__value">
                  <el-tag :type="taskStatusTag(flow.status)" size="small">{{ flow.statusDesc }}</el-tag>
                </span>
              </div>
            </div>
            <div v-if="flow.prettyParam" class="task-flow-param">
              <div class="task-flow-kv__label" style="margin-bottom: 6px">任务参数</div>
              <pre>{{ flow.prettyParam }}</pre>
            </div>
          </section>

          <section class="task-flow-progress-panel">
            <div class="task-flow-progress-panel__head">
              <div class="task-flow-progress-panel__title">执行进度</div>
              <div class="task-flow-progress-panel__meta">
                <span>{{ doneStepCount }} / {{ flow.steps.length }}</span> 步
                &nbsp;·&nbsp;
                <strong>{{ flow.progress }}</strong>
              </div>
            </div>
            <div class="task-flow-progress-track">
              <div
                class="task-flow-progress-bar"
                :class="progressBarClass"
                :style="{ width: `${progressWidth}%` }"
              >
                <span v-if="progressWidth >= 12">{{ flow.progress }}</span>
              </div>
            </div>
            <div v-if="!isFlowFinished(flow) && flow.currentStep" class="task-flow-current-step">
              当前步骤：{{ flow.currentStep }}
            </div>
            <div v-else-if="isFlowFinished(flow)" class="task-flow-current-step task-flow-current-step--done">
              任务已结束：{{ flow.statusDesc }}
            </div>
          </section>

          <section class="task-flow-steps-card">
            <h4 class="task-flow-section-title">执行步骤</h4>
            <el-table :data="flow.steps" stripe border size="small" class="task-flow-step-table">
              <el-table-column prop="orderNo" label="序号" width="60" />
              <el-table-column prop="stepName" label="步骤名称" min-width="140" />
              <el-table-column label="状态" width="90">
                <template #default="{ row }">
                  <el-tag :type="stepStatusTag(row.status)" size="small">{{ row.statusDesc }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column label="耗时" width="80">
                <template #default="{ row }">{{ stepDuration(row) }}</template>
              </el-table-column>
              <el-table-column prop="startTime" label="开始时间" width="170" />
              <el-table-column prop="endTime" label="结束时间" width="170" />
              <el-table-column label="日志" width="70">
                <template #default="{ row }">
                  <el-button
                    link
                    type="primary"
                    @click="activeLogSteps = [row.stepName]"
                  >
                    查看
                  </el-button>
                </template>
              </el-table-column>
            </el-table>
          </section>

          <section class="task-flow-logs-card">
            <h4 class="task-flow-section-title">详细日志</h4>
            <el-collapse v-model="activeLogSteps" class="task-flow-log-collapse">
              <el-collapse-item
                v-for="step in flow.steps"
                :key="step.id"
                :name="step.stepName"
              >
                <template #title>
                  <span class="task-flow-log-step-title">
                    <span class="task-flow-log-step-order">{{ step.orderNo }}.</span>
                    <span class="task-flow-log-step-name">{{ step.stepName }}</span>
                    <el-tag :type="stepStatusTag(step.status)" size="small">{{ step.statusDesc }}</el-tag>
                    <span v-if="step.logs?.length" class="task-flow-log-count">{{ step.logs.length }} 行</span>
                  </span>
                </template>
                <div v-if="step.logs?.length" class="task-flow-log-panel">
                  <div
                    v-for="(line, index) in step.logs"
                    :key="`${step.id}-${index}`"
                    class="task-flow-log-line"
                    :class="logLevelClass(line)"
                  >
                    {{ line }}
                  </div>
                </div>
                <div v-else class="task-flow-log-empty">暂无日志</div>
              </el-collapse-item>
            </el-collapse>
          </section>
        </template>
      </div>
    </el-drawer>
  </div>
</template>

<style lang="scss" scoped>
.task-page {
  padding: 16px;
}

.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
}

.page-actions {
  display: flex;
  gap: 8px;
}

.pager {
  margin-top: 16px;
  justify-content: flex-end;
}

.task-flow-log-step-title {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  font-size: 13px;
}

.task-flow-log-step-order {
  color: var(--rp-text-muted, #64748b);
  font-weight: 600;
}

.task-flow-log-step-name {
  font-weight: 600;
  color: var(--rp-text, #1e293b);
}

.task-flow-log-count {
  font-size: 12px;
  color: var(--rp-text-muted, #64748b);
}
</style>
