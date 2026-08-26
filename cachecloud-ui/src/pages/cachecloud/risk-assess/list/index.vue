<script lang="ts" setup>
import type { RiskAssessDimensionItem, RiskAssessOverviewItem, RiskAssessReport, RiskRuleDimension } from "@/api/cachecloud"
import { Document, Download, Refresh, Search } from "@element-plus/icons-vue"
import {
  getRiskAssessOverviewApi,
  getRiskAssessReportApi,
  getRiskAssessRulesApi,
  runRiskAssessApi
} from "@/api/cachecloud"
import { formatClusterType } from "@/common/utils/redis-type"
import { formatRedisVersion } from "@/common/utils/redis-version"

const loading = ref(false)
const items = ref<RiskAssessOverviewItem[]>([])
const total = ref(0)
const levelCount = ref<Record<string, number>>({})
const assessingId = ref(0)

const rulesVisible = ref(false)
const rulesLoading = ref(false)
const rules = ref<RiskRuleDimension[]>([])

async function openRules() {
  rulesVisible.value = true
  // 规则很少变动，打开过一次就不再重复拉取
  if (rules.value.length) return
  rulesLoading.value = true
  try {
    const { data } = await getRiskAssessRulesApi()
    rules.value = data ?? []
  } finally {
    rulesLoading.value = false
  }
}

/** 把「大于 85」这类阈值拼成一句人话 */
function ruleCondition(item: RiskRuleDimension["items"][number]) {
  // 安全/可用性等分类型维度没有阈值，后端直接给判定条件原文
  if (item.conditionText) return item.conditionText
  const threshold = item.threshold ?? 0
  const base = `${item.operatorName} ${threshold}`
  return item.minAbsolute ? `${base}（绝对值低于 ${item.minAbsolute} 时不判定）` : base
}

function ruleTagType(level: string) {
  if (level === "SEVERE") return "danger"
  if (level === "RISK") return "warning"
  return "info"
}

// ---- PDF 导出 ----
const rulesRef = useTemplateRef<HTMLDivElement>("rulesRef")
const reportRef = useTemplateRef<HTMLDivElement>("reportRef")
const batchRef = useTemplateRef<HTMLDivElement>("batchRef")
const exporting = ref(false)

function stamp() {
  const d = new Date()
  const p = (n: number) => String(n).padStart(2, "0")
  return `${d.getFullYear()}${p(d.getMonth() + 1)}${p(d.getDate())}_${p(d.getHours())}${p(d.getMinutes())}`
}

async function exportPdf(el: HTMLElement | null, fileName: string) {
  if (!el || exporting.value) return
  exporting.value = true
  const tip = ElMessage({ message: "正在生成 PDF...", type: "info", duration: 0 })
  try {
    await nextTick()
    const { exportElementToPdf } = await import("@/common/utils/pdf-export")
    await exportElementToPdf(el, { fileName })
    ElMessage.success("PDF 已下载")
  } catch (e: unknown) {
    ElMessage.error(e instanceof Error ? e.message : "PDF 生成失败")
  } finally {
    tip.close()
    exporting.value = false
  }
}

function exportRulesPdf() {
  return exportPdf(rulesRef.value, `Redis风险评估策略_${stamp()}`)
}

function exportReportPdf() {
  return exportPdf(reportRef.value, `Redis风险评估报告_${report.value?.appName ?? ""}_${stamp()}`)
}

// ---- 批量评估 / 批量导出 ----
const selectedRows = ref<RiskAssessOverviewItem[]>([])
const batchAssessing = ref(false)
const batchProgress = ref("")
/** 汇总报告的数据源；仅在导出期间挂载，导出完即清空 */
const batchReports = ref<RiskAssessReport[]>([])

function handleSelectionChange(rows: RiskAssessOverviewItem[]) {
  selectedRows.value = rows
}

async function handleBatchAssess() {
  if (!selectedRows.value.length) {
    ElMessage.warning("请先勾选要评估的集群")
    return
  }
  const targets = [...selectedRows.value]
  try {
    await ElMessageBox.confirm(
      `将对 ${targets.length} 个集群依次执行评估，窗口 ${windowHours.value} 小时。`,
      "批量评估",
      { type: "warning" }
    )
  } catch {
    return
  }
  batchAssessing.value = true
  const failed: string[] = []
  try {
    // 串行执行：评估会实时连每个节点采集 INFO，并发会同时压住所有实例
    for (let i = 0; i < targets.length; i++) {
      const row = targets[i]
      batchProgress.value = `${i + 1}/${targets.length} ${row.appName}`
      try {
        await runRiskAssessApi(row.appId, windowHours.value)
      } catch {
        failed.push(row.appName)
      }
    }
    await fetchList()
    if (failed.length) {
      ElMessage.warning(`完成 ${targets.length - failed.length}/${targets.length}，失败：${failed.join("、")}`)
    } else {
      ElMessage.success(`${targets.length} 个集群评估完成`)
    }
  } finally {
    batchAssessing.value = false
    batchProgress.value = ""
  }
}

async function handleBatchExport() {
  if (!selectedRows.value.length) {
    ElMessage.warning("请先勾选要导出的集群")
    return
  }
  const targets = selectedRows.value.filter(r => r.reportId)
  const skipped = selectedRows.value.filter(r => !r.reportId).map(r => r.appName)
  if (!targets.length) {
    ElMessage.warning("所选集群都还没有评估报告，请先执行评估")
    return
  }
  exporting.value = true
  const tip = ElMessage({ message: "正在汇总报告...", type: "info", duration: 0 })
  try {
    const loaded: RiskAssessReport[] = []
    for (const row of targets) {
      try {
        const { data } = await getRiskAssessReportApi(row.reportId as number)
        if (data) loaded.push(data)
      } catch {
        // 单个报告拉取失败不应中断整批，最后在提示里说明
      }
    }
    if (!loaded.length) throw new Error("没有成功加载任何报告")
    batchReports.value = loaded
    // 等汇总 DOM 真正渲染出来再截图，否则会截到空白
    await nextTick()
    await new Promise<void>(resolve =>
      requestAnimationFrame(() => requestAnimationFrame(() => resolve()))
    )
    const stage = batchRef.value
    // 高度异常说明 DOM 没真正渲染出来，此时导出的会是空白页
    if (!stage || stage.scrollHeight < 20) {
      throw new Error("汇总内容未渲染完成，请重试")
    }
    const { exportElementToPdf } = await import("@/common/utils/pdf-export")
    await exportElementToPdf(stage, {
      fileName: `Redis风险评估汇总_${loaded.length}个集群_${stamp()}`
    })
    const missed = skipped.length ? `，${skipped.length} 个集群无报告已跳过` : ""
    ElMessage.success(`已导出 ${loaded.length} 个集群的汇总报告${missed}`)
  } catch (e: unknown) {
    ElMessage.error(e instanceof Error ? e.message : "汇总报告导出失败")
  } finally {
    batchReports.value = []
    tip.close()
    exporting.value = false
  }
}

const reportVisible = ref(false)
const reportLoading = ref(false)
const report = ref<RiskAssessReport | null>(null)

const query = reactive({
  keyword: "",
  level: "",
  pageNo: 1,
  pageSize: 20
})

/** 窗口固定两档：30 天需要指标窄表保留 30 天，代价过大 */
const windowHours = ref(168)
const WINDOW_OPTIONS = [
  { label: "最近 24 小时", value: 24 },
  // 72h 是内存增长率、连接增长率、命令执行耗时三个趋势维度的最低窗口要求
  { label: "最近 3 天", value: 72 },
  { label: "最近 7 天", value: 168 }
]

const LEVEL_TAG: Record<string, "success" | "warning" | "danger" | "info"> = {
  NORMAL: "success",
  ATTENTION: "warning",
  RISK: "danger",
  SEVERE: "danger",
  UNASSESSED: "info",
  INSUFFICIENT_DATA: "info",
  WINDOW_TOO_SHORT: "info",
  NOT_APPLICABLE: "info"
}

/** 未得出结论的三种状态不参与总评，视觉上要与「正常」明确区分 */
function isConclusive(level: string) {
  return ["NORMAL", "ATTENTION", "RISK", "SEVERE"].includes(level)
}

const summaryCards = computed(() => {
  const c = levelCount.value
  return [
    { key: "SEVERE", label: "严重", value: c.SEVERE ?? 0, cls: "is-severe" },
    { key: "RISK", label: "风险", value: c.RISK ?? 0, cls: "is-risk" },
    { key: "ATTENTION", label: "关注", value: c.ATTENTION ?? 0, cls: "is-attention" },
    { key: "NORMAL", label: "正常", value: c.NORMAL ?? 0, cls: "is-normal" },
    { key: "UNASSESSED", label: "未评估", value: c.UNASSESSED ?? 0, cls: "is-unassessed" }
  ]
})

async function fetchList() {
  loading.value = true
  try {
    const { data } = await getRiskAssessOverviewApi({
      keyword: query.keyword || undefined,
      level: query.level || undefined,
      pageNo: query.pageNo,
      pageSize: query.pageSize
    })
    items.value = data?.items ?? []
    total.value = data?.totalCount ?? 0
    levelCount.value = data?.levelCount ?? {}
  } finally {
    loading.value = false
  }
}

function handleSearch() {
  query.pageNo = 1
  fetchList()
}

function filterByLevel(level: string) {
  query.level = query.level === level ? "" : level
  handleSearch()
}

async function handleAssess(row: RiskAssessOverviewItem) {
  assessingId.value = row.appId
  try {
    const { data } = await runRiskAssessApi(row.appId, windowHours.value)
    report.value = data
    reportVisible.value = true
    fetchList()
  } catch (e: unknown) {
    ElMessage.error(e instanceof Error ? e.message : "评估失败")
  } finally {
    assessingId.value = 0
  }
}

async function openReport(row: RiskAssessOverviewItem) {
  if (!row.reportId) return
  reportLoading.value = true
  reportVisible.value = true
  try {
    const { data } = await getRiskAssessReportApi(row.reportId)
    report.value = data
  } finally {
    reportLoading.value = false
  }
}

/** 证据是后端序列化的 JSON 字符串，展示时格式化 */
function prettyEvidence(evidence?: string | null) {
  if (!evidence) return ""
  try {
    return JSON.stringify(JSON.parse(evidence), null, 2)
  } catch {
    return evidence
  }
}

/** 有结论的排前面，其中越严重越靠前 */
const sortedDimensions = computed<RiskAssessDimensionItem[]>(() => {
  const order: Record<string, number> = { SEVERE: 0, RISK: 1, ATTENTION: 2, NORMAL: 3 }
  return [...(report.value?.dimensions ?? [])].sort(
    (a, b) => (order[a.level] ?? 9) - (order[b.level] ?? 9)
  )
})

onMounted(fetchList)
</script>

<template>
  <div class="risk-page">
    <el-card shadow="never" class="risk-page__cards-card">
      <div class="risk-page__cards">
        <div
          v-for="card in summaryCards"
          :key="card.key"
          class="risk-card"
          :class="[card.cls, { 'is-active': query.level === card.key }]"
          role="button"
          tabindex="0"
          @click="filterByLevel(card.key)"
          @keydown.enter="filterByLevel(card.key)"
        >
          <div class="risk-card__value">
            {{ card.value }}
          </div>
          <div class="risk-card__label">
            {{ card.label }}
          </div>
        </div>
        <el-button class="risk-page__rules-btn" :icon="Document" @click="openRules">
          评估策略
        </el-button>
      </div>
    </el-card>

    <el-card shadow="never" class="risk-page__filter">
      <div class="risk-page__filter-row">
        <el-input v-model="query.keyword" placeholder="集群名称" clearable style="width: 200px" />
        <el-button type="primary" :icon="Search" @click="handleSearch">
          查询
        </el-button>
        <span class="risk-page__label">评估窗口</span>
        <el-select v-model="windowHours" style="width: 150px">
          <el-option v-for="o in WINDOW_OPTIONS" :key="o.value" :label="o.label" :value="o.value" />
        </el-select>
        <el-button
          type="warning"
          :icon="Document"
          :loading="batchAssessing"
          @click="handleBatchAssess"
        >
          {{ batchAssessing ? `批量评估中 ${batchProgress}` : `批量评估${selectedRows.length ? ` (${selectedRows.length})` : ""}` }}
        </el-button>
        <el-button
          :icon="Download"
          :loading="exporting"
          @click="handleBatchExport"
        >
          批量导出报告{{ selectedRows.length ? ` (${selectedRows.length})` : "" }}
        </el-button>
        <el-button :icon="Refresh" @click="fetchList">
          刷新
        </el-button>
        <span class="risk-page__hint">评估窗口影响趋势类维度；窗口短于维度要求时该维度会报「窗口不足」</span>
      </div>
    </el-card>

    <el-drawer
      v-model="rulesVisible"
      title="评估策略"
      direction="rtl"
      size="56%"
      class="risk-rules-drawer"
      append-to-body
    >
      <div class="risk-drawer__actions">
        <el-button size="small" :icon="Download" :loading="exporting" @click="exportRulesPdf">
          导出 PDF
        </el-button>
      </div>
      <div ref="rulesRef" v-loading="rulesLoading" class="risk-rules">
        <el-alert type="info" :closable="false" show-icon class="risk-rules__intro">
          <template #title>
            每个维度独立定级，集群总评取所有维度中<strong>最差</strong>的一档。
          </template>
          <div class="risk-rules__intro-body">
            阈值存在 <code>risk_assess_rule</code> 表，人工调整过的行不会被启动时的默认值覆盖。
            标注「窗口不足」的维度需要更长的评估窗口才会出结论。
          </div>
        </el-alert>

        <div v-for="dim in rules" :key="dim.dimension" class="risk-rules__dim">
          <div class="risk-rules__dim-head">
            <span class="risk-rules__dim-name">{{ dim.dimensionName }}</span>
            <el-tag v-if="dim.clusterOnly" size="small" type="info">
              仅 cluster
            </el-tag>
            <el-tag v-if="dim.minWindowHours > 0" size="small" type="info">
              需窗口 ≥ {{ dim.minWindowHours }}h
            </el-tag>
            <el-tag v-if="dim.notImplemented" size="small" type="warning">
              尚未实现
            </el-tag>
          </div>
          <div v-if="dim.metricDesc" class="risk-rules__dim-desc">
            {{ dim.metricDesc }}
          </div>

          <el-table v-if="dim.items.length" :data="dim.items" size="small" border class="risk-rules__table">
            <el-table-column label="等级" width="80">
              <template #default="{ row }">
                <el-tag :type="ruleTagType(row.level)" size="small">
                  {{ row.levelName }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="子项" width="120">
              <template #default="{ row }">
                {{ row.subItem || "—" }}
              </template>
            </el-table-column>
            <el-table-column label="判定条件" min-width="180">
              <template #default="{ row }">
                {{ ruleCondition(row) }}
                <el-tag v-if="row.customized" size="small" type="success" class="risk-rules__customized">
                  已自定义
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="description" label="说明" min-width="220" show-overflow-tooltip />
            <el-table-column prop="suggestion" label="处置建议" min-width="240" show-overflow-tooltip />
          </el-table>
          <div v-else class="risk-rules__empty">
            该维度暂无阈值规则
          </div>
        </div>
      </div>
    </el-drawer>

    <el-card shadow="never">
      <el-table
        v-loading="loading"
        :data="items"
        stripe
        border
        size="small"
        :row-key="(row: RiskAssessOverviewItem) => String(row.appId)"
        @selection-change="handleSelectionChange"
      >
        <!-- reserve-selection 让翻页后已勾选的集群不丢失 -->
        <el-table-column type="selection" width="46" reserve-selection />
        <el-table-column label="集群名称" min-width="200" show-overflow-tooltip>
          <template #default="{ row }">
            <router-link :to="`/app/detail/${row.appId}`">
              {{ row.appName }}
            </router-link>
          </template>
        </el-table-column>
        <el-table-column label="类型" min-width="110">
          <template #default="{ row }">
            {{ formatClusterType(row.typeDesc) || "-" }}
          </template>
        </el-table-column>
        <el-table-column label="版本" min-width="92">
          <template #default="{ row }">
            {{ formatRedisVersion(row.versionName) || "-" }}
          </template>
        </el-table-column>
        <el-table-column prop="instanceCount" label="节点数" min-width="80" align="center" />
        <el-table-column label="风险等级" min-width="100" align="center">
          <template #default="{ row }">
            <el-tag :type="LEVEL_TAG[row.level] || 'info'" size="small" :effect="row.level === 'SEVERE' ? 'dark' : 'light'">
              {{ row.levelLabel }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="分数" min-width="80" align="center">
          <template #default="{ row }">
            {{ row.score ?? "-" }}
          </template>
        </el-table-column>
        <el-table-column label="维度覆盖" min-width="100" align="center">
          <template #default="{ row }">
            <span v-if="row.totalDimensions">{{ row.evaluatedDimensions }}/{{ row.totalDimensions }}</span>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column label="评估窗口" min-width="100" align="center">
          <template #default="{ row }">
            {{ row.windowHours ? (row.windowHours === 24 ? "24 小时" : `${row.windowHours / 24} 天`) : "-" }}
          </template>
        </el-table-column>
        <el-table-column prop="lastAssessTime" label="最近评估" min-width="160">
          <template #default="{ row }">
            {{ row.lastAssessTime || "从未评估" }}
          </template>
        </el-table-column>
        <el-table-column label="操作" width="170" fixed="right">
          <template #default="{ row }">
            <div class="risk-page__actions">
              <el-button
                type="primary"
                size="small"
                :loading="assessingId === row.appId"
                @click="handleAssess(row)"
              >
                评估
              </el-button>
              <el-button v-if="row.reportId" size="small" @click="openReport(row)">
                查看
              </el-button>
            </div>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination
        v-model:current-page="query.pageNo"
        :page-size="query.pageSize"
        :total="total"
        layout="total, prev, pager, next"
        class="risk-page__pager"
        @current-change="fetchList"
      />
    </el-card>

    <!-- 批量汇总报告：仅导出期间挂载，导出完立即卸载。
         定位在视口内 + opacity 0.011（不是 0）——与健康检查报告同一套做法：
         display:none / visibility:hidden / opacity:0 / 移出视口都会让
         html2canvas 截出空白页。 -->
    <div v-if="batchReports.length" class="risk-batch-stage">
      <div ref="batchRef" class="risk-batch">
        <div class="risk-batch__cover">
          <h2 class="risk-batch__title">
            Redis 集群风险评估汇总报告
          </h2>
          <div class="risk-batch__meta">
            共 {{ batchReports.length }} 个集群 · 生成时间 {{ new Date().toLocaleString() }}
          </div>
          <table class="risk-batch__summary">
            <thead>
              <tr>
                <th>集群</th><th>总评</th><th>分数</th><th>维度覆盖</th><th>评估时间</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="r in batchReports" :key="`s-${r.reportId}`">
                <td>{{ r.appName }}</td>
                <td>{{ r.levelLabel }}</td>
                <td>{{ r.score }}</td>
                <td>{{ r.evaluatedDimensions }}/{{ r.totalDimensions }}</td>
                <td>{{ r.createTime }}</td>
              </tr>
            </tbody>
          </table>
        </div>

        <div v-for="r in batchReports" :key="r.reportId" class="risk-batch__item">
          <h3 class="risk-batch__item-title">
            {{ r.appName }} · 总评 {{ r.levelLabel }}（{{ r.score }} 分）
          </h3>
          <div class="risk-batch__item-meta">
            取样窗口 {{ r.windowStart }} ~ {{ r.windowEnd }} ·
            节点采集 {{ r.collectedInstanceCount }}/{{ r.instanceCount }}
          </div>
          <table class="risk-batch__dims">
            <thead>
              <tr>
                <th style="width:150px">维度</th>
                <th style="width:70px">等级</th>
                <th>结论</th>
                <th style="width:230px">处置建议</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="d in r.dimensions" :key="`${r.reportId}-${d.dimension}`">
                <td>{{ d.dimensionName }}</td>
                <td>{{ d.levelLabel }}</td>
                <td>{{ d.summary || "-" }}</td>
                <td>{{ d.suggestion || "-" }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
    </div>

    <el-drawer v-model="reportVisible" size="880px" :title="`风险评估报告 - ${report?.appName ?? ''}`">
      <div class="risk-drawer__actions">
        <el-button
          size="small"
          :icon="Download"
          :loading="exporting"
          :disabled="!report"
          @click="exportReportPdf"
        >
          导出 PDF
        </el-button>
      </div>
      <div ref="reportRef" v-loading="reportLoading">
        <template v-if="report">
          <el-descriptions :column="3" border size="small" class="risk-report__meta">
            <el-descriptions-item label="总评">
              <el-tag :type="LEVEL_TAG[report.level] || 'info'" size="small" :effect="report.level === 'SEVERE' ? 'dark' : 'light'">
                {{ report.levelLabel }}
              </el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="分数">
              {{ report.score }}
            </el-descriptions-item>
            <el-descriptions-item label="维度覆盖">
              {{ report.evaluatedDimensions }}/{{ report.totalDimensions }}
            </el-descriptions-item>
            <el-descriptions-item label="节点采集">
              {{ report.collectedInstanceCount }}/{{ report.instanceCount }}
            </el-descriptions-item>
            <el-descriptions-item label="耗时">
              {{ report.costMs }} ms
            </el-descriptions-item>
            <el-descriptions-item label="评估时间">
              {{ report.createTime }}
            </el-descriptions-item>
            <el-descriptions-item label="取样窗口" :span="3">
              {{ report.windowStart }} ~ {{ report.windowEnd }}
            </el-descriptions-item>
          </el-descriptions>

          <el-alert
            v-if="report.collectedInstanceCount < report.instanceCount"
            type="warning"
            :closable="false"
            show-icon
            class="risk-report__alert"
            title="有节点未采集到指标，倾斜类结论基于不完整样本，请谨慎解读"
          />

          <div class="risk-report__dimensions">
            <div
              v-for="d in sortedDimensions"
              :key="d.dimension"
              class="risk-dim"
              :class="`is-${d.level.toLowerCase()}`"
            >
              <div class="risk-dim__head">
                <span class="risk-dim__name">{{ d.dimensionName }}</span>
                <el-tag :type="LEVEL_TAG[d.level] || 'info'" size="small" :effect="d.level === 'SEVERE' ? 'dark' : 'light'">
                  {{ d.levelLabel }}
                </el-tag>
              </div>
              <div class="risk-dim__summary">
                {{ d.summary }}
              </div>
              <div v-if="isConclusive(d.level)" class="risk-dim__meta">
                <span v-if="d.actualValue !== null && d.actualValue !== undefined">实测 {{ d.actualValue }}</span>
                <span v-if="d.threshold !== null && d.threshold !== undefined">阈值 {{ d.threshold }}</span>
                <span v-if="d.instanceHostPort">节点 {{ d.instanceHostPort }}</span>
                <span v-if="d.sampleCount">样本 {{ d.sampleCount }}</span>
                <span v-if="d.sampleWindow">{{ d.sampleWindow }}</span>
              </div>
              <div v-if="d.suggestion" class="risk-dim__suggestion">
                建议：{{ d.suggestion }}
              </div>
              <el-collapse v-if="d.evidence" class="risk-dim__evidence">
                <el-collapse-item title="查看证据">
                  <pre>{{ prettyEvidence(d.evidence) }}</pre>
                </el-collapse-item>
              </el-collapse>
            </div>
          </div>
        </template>
      </div>
    </el-drawer>
  </div>
</template>

<style lang="scss" scoped>
/* 汇总报告舞台：html2canvas 只能截"渲染中"的元素，
   因此不能隐藏，只能做到几乎不可见并且不挡交互。 */
.risk-batch-stage {
  position: fixed;
  top: 0;
  left: 0;
  z-index: 2147483646;
  width: 1000px;
  overflow: visible;
  pointer-events: none;
  background: #fff;
  opacity: 0.011;
}

.risk-batch {
  width: 1000px;
  padding: 24px;
  font-size: 12px;
  color: #1a2332;
  background: #fff;
}

.risk-batch__title {
  margin: 0 0 6px;
  font-size: 20px;
}

.risk-batch__meta {
  margin-bottom: 14px;
  font-size: 12px;
  color: #6b7a90;
}

.risk-batch__cover {
  padding-bottom: 18px;
  margin-bottom: 18px;
  border-bottom: 2px solid #e8edf3;
}

.risk-batch__summary,
.risk-batch__dims {
  width: 100%;
  border-collapse: collapse;
}

.risk-batch__summary th,
.risk-batch__summary td,
.risk-batch__dims th,
.risk-batch__dims td {
  padding: 6px 8px;
  text-align: left;
  vertical-align: top;
  border: 1px solid #e8edf3;
}

.risk-batch__summary th,
.risk-batch__dims th {
  font-weight: 600;
  background: #f8fafc;
}

.risk-batch__item {
  margin-top: 20px;
  /* 尽量让每个集群从新的一页开始，避免标题与表格被切开 */
  break-inside: avoid;
}

.risk-batch__item-title {
  margin: 0 0 4px;
  font-size: 15px;
}

.risk-batch__item-meta {
  margin-bottom: 8px;
  font-size: 11px;
  color: #6b7a90;
}

.risk-drawer__actions {
  display: flex;
  justify-content: flex-end;
  margin-bottom: 10px;
}

/* 与下方筛选卡片同宽同边框，视觉上成为一组 */
.risk-page__cards-card {
  margin-bottom: 6px;

  /* 上下留白减半（20px -> 10px）；左右收窄把按钮横向拉长 */
  :deep(.el-card__body) {
    padding: 10px 12px;
  }
}

.risk-page__cards {
  display: flex;
  gap: 6px;
  align-items: stretch;
}

.risk-page__rules-btn {
  height: auto;
  align-self: stretch;
  margin-left: 4px;
}

.risk-card {
  flex: 1;
  padding: 3px 10px;
  cursor: pointer;
  border: 1px solid var(--el-border-color-light);
  border-radius: 4px;
  background: var(--el-bg-color);
  transition: border-color 0.2s;

  &.is-active,
  &:hover {
    border-color: var(--el-color-primary);
  }

  &__value {
    font-size: 18px;
    font-weight: 600;
    line-height: 1.2;
  }

  &__label {
    margin-top: 2px;
    font-size: 12px;
    color: var(--el-text-color-secondary);
  }

  &.is-severe .risk-card__value {
    color: var(--el-color-danger);
  }
  &.is-risk .risk-card__value {
    color: #e6733b;
  }
  &.is-attention .risk-card__value {
    color: var(--el-color-warning);
  }
  &.is-normal .risk-card__value {
    color: var(--el-color-success);
  }
  &.is-unassessed .risk-card__value {
    color: var(--el-text-color-secondary);
  }
}

.risk-page__filter {
  margin-bottom: 12px;
}

.risk-page__filter-row {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
}

.risk-page__label {
  font-size: 13px;
  color: var(--el-text-color-regular);
}

.risk-page__hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.risk-page__actions {
  display: flex;
  gap: 6px;
}

.risk-page__pager {
  margin-top: 12px;
  justify-content: flex-end;
}

.risk-report__meta {
  margin-bottom: 12px;
}

.risk-report__alert {
  margin-bottom: 12px;
}

.risk-dim {
  padding: 12px 14px;
  margin-bottom: 10px;
  border: 1px solid var(--el-border-color-light);
  border-left-width: 3px;
  border-radius: 4px;

  &.is-severe {
    border-left-color: var(--el-color-danger);
  }
  &.is-risk {
    border-left-color: #e6733b;
  }
  &.is-attention {
    border-left-color: var(--el-color-warning);
  }
  &.is-normal {
    border-left-color: var(--el-color-success);
  }

  &__head {
    display: flex;
    gap: 8px;
    align-items: center;
    margin-bottom: 6px;
  }

  &__name {
    font-weight: 600;
  }

  &__summary {
    font-size: 13px;
    line-height: 1.6;
  }

  &__meta {
    display: flex;
    flex-wrap: wrap;
    gap: 12px;
    margin-top: 6px;
    font-size: 12px;
    color: var(--el-text-color-secondary);
  }

  &__suggestion {
    margin-top: 6px;
    font-size: 12px;
    color: var(--el-color-primary);
  }

  &__evidence {
    margin-top: 6px;

    pre {
      margin: 0;
      font-size: 12px;
      line-height: 1.5;
      white-space: pre-wrap;
      word-break: break-all;
    }
  }
}
/* 评估策略抽屉 */
.risk-rules__intro {
  margin-bottom: 16px;
}

.risk-rules__intro-body {
  margin-top: 6px;
  font-size: 13px;
  line-height: 1.7;
}

.risk-rules__dim {
  margin-bottom: 22px;
}

.risk-rules__dim-head {
  display: flex;
  gap: 8px;
  align-items: center;
  margin-bottom: 6px;
}

.risk-rules__dim-name {
  font-size: 15px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}

.risk-rules__dim-desc {
  margin-bottom: 8px;
  font-size: 12px;
  line-height: 1.7;
  color: var(--el-text-color-secondary);
}

.risk-rules__customized {
  margin-left: 6px;
}

.risk-rules__empty {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
</style>
