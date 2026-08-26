<script lang="ts" setup>
import type {
  AppDetail,
  KeyAnalysisAuditItem,
  KeyAnalysisPage,
  KeyAnalysisProgress,
  KeyAnalysisResult,
  ParamCountItem,
  TaskFlowDetail
} from "@/api/cachecloud"
import { Delete, Key, Refresh, Warning } from "@element-plus/icons-vue"
import {
  deleteKeyAnalysisRecordApi,
  getKeyAnalysisPageApi,
  getKeyAnalysisProgressApi,
  getKeyAnalysisResultApi,
  getTaskFlowApi,
  startKeyAnalysisApi
} from "@/api/cachecloud"
import KeyAnalysisReport from "@/pages/cachecloud/components/KeyAnalysisReport.vue"
import { TAB_RECLICK } from "../tab-reclick"
import "@/common/assets/styles/app-tab.scss"

const props = defineProps<{ appId: number, detail?: AppDetail | null }>()

const loading = ref(false)
const starting = ref(false)
const refreshing = ref(false)
const page = ref<KeyAnalysisPage | null>(null)
const selectedNodes = ref<string[]>([])
const reason = ref("")
const bigKeyStringKb = ref(100)
const bigKeyCollectionElements = ref(50000)
const confirmEmpty = ref(false)
const viewMode = ref<"list" | "result">("list")
const resultLoading = ref(false)
const result = ref<KeyAnalysisResult | null>(null)
const activeAudit = ref<KeyAnalysisAuditItem | null>(null)
const flowVisible = ref(false)
const flowLoading = ref(false)
const flow = ref<TaskFlowDetail | null>(null)
const flowTaskId = ref(0)
const activeLogSteps = ref<string[]>([])
const progressMap = ref<Record<number, KeyAnalysisProgress>>({})
let pollTimer: ReturnType<typeof setInterval> | null = null
let flowPollTimer: ReturnType<typeof setInterval> | null = null

function canViewResult(row: KeyAnalysisAuditItem) {
  return row.passed || row.status === 1
}

function auditInfoText(info?: string) {
  if (!info) return ""
  return info.startsWith("申请原因: ") ? info.slice(6) : info
}

function statusLabel(audit: KeyAnalysisAuditItem) {
  if (audit.passed || audit.status === 1) {
    if (audit.riskInfo) return "已完成（有风险）"
    if (audit.totalKeyCount === 0) return "已完成（空库）"
    return "已完成"
  }
  if (audit.running) return "执行中"
  if (audit.rejected) return "失败"
  return audit.statusDesc || "未知"
}

function formatBytes(value: number) {
  if (!Number.isFinite(value) || value <= 0) return "0 B"
  const units = ["B", "KB", "MB", "GB"]
  let size = value
  let unit = 0
  while (size >= 1024 && unit < units.length - 1) {
    size /= 1024
    unit++
  }
  return `${size >= 10 || unit === 0 ? size.toFixed(0) : size.toFixed(1)} ${units[unit]}`
}

function bigKeyRuleText(data = result.value) {
  const stringBytes = data?.bigKeyStringBytes || bigKeyStringKb.value * 1024
  const elements = data?.bigKeyCollectionElements || bigKeyCollectionElements.value
  return `String 大小 >= ${formatBytes(stringBytes)}；Hash/List/Set/ZSet 元素数 >= ${elements.toLocaleString()}`
}

function stopFlowPolling() {
  if (flowPollTimer) {
    clearInterval(flowPollTimer)
    flowPollTimer = null
  }
}

async function refreshTaskFlow(silent = false) {
  if (!flowTaskId.value) return null
  if (!silent) flowLoading.value = true
  try {
    const { data } = await getTaskFlowApi(flowTaskId.value)
    flow.value = data
    if (data && [2, 4, 6].includes(data.status)) stopFlowPolling()
    return data
  } finally {
    if (!silent) flowLoading.value = false
  }
}

async function openTaskFlow(taskId: number) {
  flowTaskId.value = taskId
  flow.value = null
  activeLogSteps.value = []
  flowVisible.value = true
  stopFlowPolling()
  const data = await refreshTaskFlow()
  if (data && ![2, 4, 6].includes(data.status)) {
    flowPollTimer = setInterval(() => void refreshTaskFlow(true), 2500)
  }
}

async function deleteRecord(row: KeyAnalysisAuditItem) {
  try {
    await ElMessageBox.confirm(`确认删除分析记录 #${row.id}？删除后无法恢复。`, "删除分析记录", {
      type: "warning",
      confirmButtonText: "删除",
      cancelButtonText: "取消"
    })
  } catch {
    return
  }
  await deleteKeyAnalysisRecordApi(props.appId, row.id)
  ElMessage.success("分析记录已删除")
  await fetchPage()
}

function statusClass(audit: KeyAnalysisAuditItem) {
  if (audit.passed || audit.status === 1) {
    if (audit.riskInfo) return "app-key-analysis-status--risk"
    if (audit.totalKeyCount === 0) return "app-key-analysis-status--empty-done"
    return "app-key-analysis-status--done"
  }
  if (audit.running) return "app-key-analysis-status--running"
  if (audit.rejected) return "app-key-analysis-status--fail"
  return ""
}

const analysisScopeInstances = computed(() => {
  const instances = page.value?.instances ?? []
  if (selectedNodes.value.length) {
    const set = new Set(selectedNodes.value)
    return instances.filter(i => set.has(i.hostPort))
  }
  const slaves = instances.filter(i => i.slave)
  return slaves.length ? slaves : instances
})

const scopeAllZeroKeys = computed(() => {
  const scope = analysisScopeInstances.value
  if (!scope.length) return false
  return scope.every(i => i.currItems <= 0)
})

const allInstancesZeroKeys = computed(() => {
  const instances = page.value?.instances ?? []
  if (!instances.length) return false
  return instances.every(i => i.currItems <= 0)
})

const showEmptyConfirmRow = computed(() => scopeAllZeroKeys.value)
const startDisabled = computed(() => showEmptyConfirmRow.value && !confirmEmpty.value)

async function handleRefresh() {
  if (refreshing.value) return
  refreshing.value = true
  try {
    await fetchPage()
    ElMessage.success("已刷新")
  } finally {
    refreshing.value = false
  }
}

async function fetchPage() {
  loading.value = true
  try {
    const { data } = await getKeyAnalysisPageApi(props.appId)
    page.value = data
    setupPolling(data)
  } finally {
    loading.value = false
  }
}

function setupPolling(data: KeyAnalysisPage | null) {
  stopPolling()
  const running = data?.audits.filter(a => a.running && a.taskId > 0) ?? []
  if (!running.length) return
  pollTimer = setInterval(async () => {
    for (const audit of running) {
      try {
        const { data: progress } = await getKeyAnalysisProgressApi(props.appId, audit.taskId)
        if (progress) {
          progressMap.value[audit.taskId] = progress
          if (progress.finished) {
            await fetchPage()
          }
        }
      } catch {
        // ignore poll errors
      }
    }
  }, 3000)
}

function stopPolling() {
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

function selectAll(slavesOnly = false) {
  const instances = page.value?.instances ?? []
  selectedNodes.value = slavesOnly
    ? instances.filter(i => i.slave).map(i => i.hostPort)
    : instances.map(i => i.hostPort)
}

function clearSelection() {
  selectedNodes.value = []
  confirmEmpty.value = false
}

async function handleStart() {
  const nodeInfo = selectedNodes.value.join(",")
  if (scopeAllZeroKeys.value && !confirmEmpty.value) {
    ElMessage.warning("所选实例 key 数为 0。空库不会产生类型/TTL 分布，请勾选「确认分析空库」，或先写入测试数据。")
    return
  }
  const trimmedReason = reason.value.trim() || "管理后台发起"
  const totalCount = page.value?.instances.length ?? 0
  let nodeHint = nodeInfo ? `\n分析节点：${nodeInfo}` : "\n分析节点：全部从实例（slave，默认）"
  if (nodeInfo && totalCount > 0) {
    const nodeCount = nodeInfo.split(",").length
    if (nodeCount >= totalCount) {
      nodeHint += `\n（已选全部 ${totalCount} 个节点，将逐个扫描，耗时较长）`
    }
  }
  const emptyHint = scopeAllZeroKeys.value ? "\n（空库：仅记录扫描结论，无分布统计）" : ""
  const ruleHint = `\nBigKey 规则：${bigKeyRuleText(null)}`
  try {
    await ElMessageBox.confirm(
      `确认对该应用发起键值分析？${nodeHint}${ruleHint}${emptyHint}\n分析会扫描 Redis 键空间，数据量大时耗时较长。`,
      "发起键值分析",
      { type: "warning", confirmButtonText: "确认发起", cancelButtonText: "取消" }
    )
  } catch {
    return
  }
  starting.value = true
  try {
    await startKeyAnalysisApi(props.appId, {
      reason: trimmedReason,
      nodeInfo,
      confirmEmpty: confirmEmpty.value,
      bigKeyStringBytes: Math.round(bigKeyStringKb.value * 1024),
      bigKeyCollectionElements: Math.round(bigKeyCollectionElements.value)
    })
    ElMessage.success("键值分析任务已发起")
    await fetchPage()
  } catch (e: unknown) {
    const msg = e instanceof Error ? e.message : "发起失败"
    ElMessage.error(msg)
  } finally {
    starting.value = false
  }
}

async function openResult(audit: KeyAnalysisAuditItem) {
  activeAudit.value = audit
  viewMode.value = "result"
  resultLoading.value = true
  result.value = null
  try {
    const { data } = await getKeyAnalysisResultApi(props.appId, audit.id)
    result.value = data
    await nextTick()
  } catch (e: unknown) {
    viewMode.value = "list"
    const msg = e instanceof Error ? e.message : "加载分析结果失败"
    ElMessage.error(msg)
  } finally {
    resultLoading.value = false
  }
}

function backToList() {
  viewMode.value = "list"
  activeAudit.value = null
  result.value = null
}

function progressFor(audit: KeyAnalysisAuditItem) {
  if (!audit.running || !audit.taskId) return null
  return progressMap.value[audit.taskId]
}

watch([selectedNodes, confirmEmpty], () => {
  if (!scopeAllZeroKeys.value) {
    confirmEmpty.value = false
  }
})

// 顶部再次点击「键值分析」时退回列表。keep-alive 会把非当前 tab 的实例也留着，
// 用 onActivated/onDeactivated 标记可见性，避免重复点击别的 tab 时把这里一起重置了。
const tabVisible = ref(true)
onActivated(() => { tabVisible.value = true })
onDeactivated(() => { tabVisible.value = false })
const tabReclickSeq = inject(TAB_RECLICK, null)
if (tabReclickSeq) {
  watch(tabReclickSeq, () => {
    if (tabVisible.value && viewMode.value === "result") backToList()
  })
}

watch(() => props.appId, fetchPage, { immediate: true })
watch(flowVisible, (visible) => {
  if (!visible) stopFlowPolling()
})
onBeforeUnmount(() => {
  stopPolling()
  stopFlowPolling()
})
</script>

<template>
  <div v-loading="loading" class="app-key-analysis-page">
    <KeyAnalysisReport
      v-if="viewMode === 'result'"
      :result="result"
      :loading="resultLoading"
      :subtitle="`记录 #${activeAudit?.id} · ${detail?.appName || `集群编码 ${appId}`}`"
      :export-name="`Redis键值分析_${detail?.appName || appId}_${activeAudit?.id || ''}`"
      @back="backToList"
    />

    <template v-else>
      <div class="app-key-analysis-header">
        <div class="app-key-analysis-nodes">
          <div class="app-key-analysis-nodes__label">分析节点</div>
          <template v-if="page?.instances?.length">
            <el-checkbox-group v-model="selectedNodes" class="app-key-analysis-nodes__list">
              <el-checkbox
                v-for="inst in page.instances"
                :key="inst.hostPort"
                :value="inst.hostPort"
                class="app-key-analysis-node-item"
              >
                {{ inst.hostPort }}（{{ inst.roleDesc || "unknown" }}）
                <template v-if="inst.currItems >= 0">
                  · <strong :class="inst.currItems === 0 ? 'app-key-analysis-key-count--zero' : 'app-key-analysis-key-count--ok'">{{ inst.currItems }}</strong> keys
                </template>
                <template v-else> · 暂无统计</template>
              </el-checkbox>
            </el-checkbox-group>
            <div class="app-key-analysis-nodes__actions">
              <el-button size="small" class="app-key-analysis-nodes__btn" @click="selectAll(false)">全选</el-button>
              <el-button size="small" class="app-key-analysis-nodes__btn" @click="selectAll(true)">仅从节点</el-button>
              <el-button size="small" class="app-key-analysis-nodes__btn" @click="clearSelection">清空</el-button>
            </div>
            <div class="app-key-analysis-nodes__hint">不勾选时默认分析全部从节点；多个节点将并行扫描。</div>
          </template>
          <div v-else class="app-key-analysis-nodes__hint">未找到 Redis 实例，请先确认实例已纳入。</div>
        </div>

        <div class="app-key-analysis-config">
          <div class="app-key-analysis-header__text">
            <span class="app-key-analysis-header__badge"><el-icon><Key /></el-icon></span>
            <div>
              <div class="app-key-analysis-header__title">集群键值分析</div>
              <div class="app-key-analysis-header__desc">扫描 BigKey、键类型/TTL/内存分布、空闲键和 Key 前缀。</div>
            </div>
          </div>

          <div class="app-key-analysis-rules">
            <div class="app-key-analysis-rules__title">BigKey 定义</div>
            <div class="app-key-analysis-rules__fields">
              <label>
                <span>String 大小超过</span>
                <el-input-number v-model="bigKeyStringKb" :min="1" :max="1048576" :step="100" controls-position="right" />
                <span>KB</span>
              </label>
              <label>
                <span>复杂类型元素数超过</span>
                <el-input-number v-model="bigKeyCollectionElements" :min="1" :max="1000000000" :step="1000" controls-position="right" />
                <span>个</span>
              </label>
            </div>
          </div>

          <div class="app-key-analysis-header__row">
            <label v-if="showEmptyConfirmRow" class="app-key-analysis-empty-confirm">
              <el-checkbox v-model="confirmEmpty" />
              确认分析空库（当前 key 数为 0，仅记录扫描结论，无类型/TTL 分布）
            </label>
            <el-input
              v-model="reason"
              class="app-key-analysis-reason"
              maxlength="200"
              placeholder="分析说明（可选）"
            />
            <el-button
              type="primary"
              class="app-key-analysis-start-btn"
              :loading="starting"
              :disabled="startDisabled"
              @click="handleStart"
            >
              发起分析
            </el-button>
            <el-button
              class="app-key-analysis-start-btn"
              :icon="Refresh"
              :loading="refreshing"
              @click="handleRefresh"
            >
              刷新
            </el-button>
          </div>
        </div>
      </div>

      <div v-if="allInstancesZeroKeys" class="app-key-analysis-zero-keys-banner">
        <el-icon><Warning /></el-icon>
        当前所有 Redis 实例 key 数量均为 <strong>0</strong>，分析完成后结果会为空。请先在 Redis 写入数据后再发起分析。
      </div>

      <div class="app-key-analysis-section">
        <div class="app-key-analysis-section__title">分析记录</div>

        <div v-if="!page?.audits?.length" class="app-key-analysis-empty-hint is-static">
          暂无分析记录。点击「发起分析」后，后台任务会扫描 Redis 并生成报告。
        </div>

        <div v-else class="app-key-analysis-table-wrap">
          <el-table :data="page.audits" stripe border size="small" class="app-key-analysis-table">
            <el-table-column prop="id" label="记录 ID" width="80" />
            <el-table-column prop="createTime" label="分析时间" width="170" />
            <el-table-column prop="userName" label="操作人" width="100" />
            <el-table-column label="状态" width="160" class-name="app-key-analysis-table__status">
              <template #default="{ row }">
                <template v-if="row.running && progressFor(row)">
                  <div class="app-key-analysis-row-progress">
                    <div class="app-key-analysis-row-progress__head">
                      <span class="app-key-analysis-row-progress__label">执行中</span>
                      <span class="app-key-analysis-row-progress__pct">
                        {{ Math.round(progressFor(row)?.progress ?? 0) }}%
                      </span>
                    </div>
                    <div
                      class="app-key-analysis-row-progress__track"
                      :aria-valuenow="Math.round(progressFor(row)?.progress ?? 0)"
                      aria-valuemin="0"
                      aria-valuemax="100"
                    >
                      <div
                        class="app-key-analysis-row-progress__bar is-active"
                        :style="{ width: `${Math.round(progressFor(row)?.progress ?? 0)}%` }"
                      />
                    </div>
                  </div>
                </template>
                <template v-else-if="row.running">
                  <span class="app-key-analysis-status app-key-analysis-status--running">执行中</span>
                </template>
                <template v-else>
                  <span class="app-key-analysis-status" :class="statusClass(row)">{{ statusLabel(row) }}</span>
                  <div v-if="row.rejected && row.refuseReason" class="app-key-analysis-fail-reason">{{ row.refuseReason }}</div>
                </template>
              </template>
            </el-table-column>
            <el-table-column label="说明" min-width="220" class-name="app-key-analysis-table__info">
              <template #default="{ row }">
                <div v-if="auditInfoText(row.info)">{{ auditInfoText(row.info) }}</div>
                <div v-if="row.nodeInfo" class="app-key-analysis-table__nodes">节点：{{ row.nodeInfo }}</div>
                <div v-if="row.riskInfo" class="app-key-analysis-table__risk">{{ row.riskInfo }}</div>
                <div v-if="row.passed && row.totalKeyCount != null && row.totalKeyCount >= 0" class="app-key-analysis-table__nodes">
                  扫描 key 总数：{{ row.totalKeyCount }}
                </div>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="300" class-name="app-key-analysis-table__ops">
              <template #default="{ row }">
                <div class="table-actions">
                  <el-button
                    v-if="canViewResult(row)"
                    type="primary"
                    size="small"
                    native-type="button"
                    @click.stop="openResult(row)"
                  >
                    查看结果
                  </el-button>
                  <el-button
                    v-if="row.taskId > 0"
                    type="primary"
                    plain
                    size="small"
                    native-type="button"
                    @click.stop="openTaskFlow(row.taskId)"
                  >
                    查看过程
                  </el-button>
                  <el-button
                    v-if="!row.running"
                    type="danger"
                    plain
                    size="small"
                    :icon="Delete"
                    native-type="button"
                    @click.stop="deleteRecord(row)"
                  >
                    删除
                  </el-button>
                </div>
              </template>
            </el-table-column>
          </el-table>
        </div>
      </div>
    </template>

    <el-drawer v-model="flowVisible" title="分析过程" size="68%" destroy-on-close>
      <div v-loading="flowLoading" class="app-key-flow-drawer">
        <template v-if="flow">
          <div class="app-key-flow-summary">
            <div><span>任务 ID</span><strong>{{ flow.taskId }}</strong></div>
            <div><span>状态</span><strong>{{ flow.statusDesc }}</strong></div>
            <div><span>进度</span><strong>{{ flow.progress }}</strong></div>
            <div><span>当前步骤</span><strong>{{ flow.currentStep || "-" }}</strong></div>
          </div>
          <div class="app-key-flow-actions">
            <el-button :icon="Refresh" size="small" @click="refreshTaskFlow()">刷新</el-button>
          </div>
          <el-table :data="flow.steps" stripe border size="small">
            <el-table-column prop="orderNo" label="#" width="60" />
            <el-table-column prop="stepName" label="步骤" min-width="150" />
            <el-table-column prop="statusDesc" label="状态" width="100" />
            <el-table-column prop="startTime" label="开始时间" width="170" />
            <el-table-column prop="endTime" label="结束时间" width="170" />
          </el-table>
          <el-collapse v-model="activeLogSteps" class="app-key-flow-logs">
            <el-collapse-item v-for="step in flow.steps" :key="step.id" :name="step.stepName">
              <template #title>{{ step.orderNo }}. {{ step.stepName }} · {{ step.statusDesc }}</template>
              <pre v-if="step.logs?.length">{{ step.logs.join("\n") }}</pre>
              <div v-else class="app-key-analysis-muted">暂无日志</div>
            </el-collapse-item>
          </el-collapse>
        </template>
      </div>
    </el-drawer>
  </div>
</template>
