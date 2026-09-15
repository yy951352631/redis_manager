<script lang="ts" setup>
import type { FaultDiagnosticCheck, FaultDiagnosticReport } from "@/api/cachecloud"
import { useAiAssistant } from "@@/composables/useAiAssistant"
import { renderMarkdown } from "@@/utils/markdown"
import {
  Box,
  ChatDotRound,
  CircleCheck,
  CircleClose,
  Clock,
  Coin,
  FirstAidKit,
  FolderOpened,
  List,
  Loading,
  MagicStick,
  Minus,
  Operation,
  PriceTag,
  Warning
} from "@element-plus/icons-vue"
import { getFaultDiagnosticHistoryApi, runFaultDiagnosticApi } from "@/api/cachecloud"
import { aiDiagnoseReportApi, getFaultDiagnosticReportApi } from "@/api/cachecloud/ai"
import { useHostCapability } from "@/common/composables/useHostCapability"
import { formatClusterNo } from "@/common/utils/cluster-no"
import "@/common/assets/styles/fault-diagnostic.scss"

const props = defineProps<{
  appId: number
  clusterNo?: number
  appName?: string
  appTypeDesc?: string
  autoRun?: boolean
}>()

const router = useRouter()
const { openWithContext } = useAiAssistant()

type HeroState = "idle" | "running" | "ok" | "warn" | "fail"
type FilterLevel = "all" | "FAIL" | "WARN" | "PASS" | "SKIP"

const pipelineStep = ref(1)
const heroState = ref<HeroState>("idle")
const heroBadge = ref("待诊断")
const running = ref(false)
const aiLoading = ref(false)
const currentReport = ref<FaultDiagnosticReport | null>(null)
const activeFilter = ref<FilterLevel>("all")
const rawOpen = ref<Record<string, boolean>>({})
const historyVisible = ref(false)
const historyLoading = ref(false)
const historyList = ref<FaultDiagnosticReport[]>([])

const hostCap = useHostCapability(
  () => props.appId,
  () => ({ diagnosticReport: currentReport.value })
)

const diagnosticHint = hostCap.diagnosticHint
const unavailableHint = hostCap.unavailableHint

const HERO_TITLE: Record<HeroState, string> = {
  idle: "智能故障诊断",
  running: "诊断进行中…",
  ok: "检查通过",
  warn: "存在需关注项",
  fail: "发现异常"
}

const PIPELINE_STEPS = [
  { step: 1, num: "①", label: "规则检查" },
  { step: 2, num: "②", label: "AI 根因" },
  { step: 3, num: "③", label: "继续追问" }
]

const LEVEL_ICON = {
  PASS: CircleCheck,
  WARN: Warning,
  FAIL: CircleClose,
  SKIP: Minus
} as const

const FILTER_OPTIONS: { value: FilterLevel, label: string }[] = [
  { value: "all", label: "全部" },
  { value: "FAIL", label: "FAIL" },
  { value: "WARN", label: "WARN" },
  { value: "PASS", label: "PASS" },
  { value: "SKIP", label: "SKIP" }
]

function getLevelIcon(level?: string) {
  const lv = (level || "SKIP").toUpperCase() as keyof typeof LEVEL_ICON
  return LEVEL_ICON[lv] || Minus
}

const visibleStatItems = computed(() => {
  const checks = hostCap.filterDiagnosticChecksForDisplay(currentReport.value?.checks)
  const counts = { pass: 0, warn: 0, fail: 0, skip: 0 }
  for (const c of checks) {
    const lv = (c.level || "SKIP").toUpperCase()
    if (lv === "PASS") counts.pass++
    else if (lv === "WARN") counts.warn++
    else if (lv === "FAIL") counts.fail++
    else counts.skip++
  }
  return [
    { label: "PASS", count: counts.pass, cls: "pass" },
    { label: "WARN", count: counts.warn, cls: "warn" },
    { label: "FAIL", count: counts.fail, cls: "fail" },
    { label: "SKIP", count: counts.skip, cls: "skip" }
  ]
})

const statItems = computed(() => {
  if (!currentReport.value) return []
  return visibleStatItems.value
})

const groupedChecks = computed(() => {
  const checks = hostCap.filterDiagnosticChecksForDisplay(currentReport.value?.checks)
  const map = new Map<string, FaultDiagnosticCheck[]>()
  const order: string[] = []
  for (const c of checks) {
    const g = c.group || "其他"
    if (!map.has(g)) {
      map.set(g, [])
      order.push(g)
    }
    map.get(g)!.push(c)
  }
  return order.map(name => ({ name, items: map.get(name)! }))
})

function levelClass(level?: string) {
  const lv = (level || "SKIP").toUpperCase()
  return `app-fault-check--${lv.toLowerCase()}`
}

function isCheckVisible(level?: string) {
  if (activeFilter.value === "all") return true
  return (level || "SKIP").toUpperCase() === activeFilter.value
}

function setHero(state: HeroState, badge?: string) {
  heroState.value = state
  heroBadge.value = badge ?? ""
}

function setPipeline(step: number) {
  pipelineStep.value = step
}

function renderReport(report: FaultDiagnosticReport) {
  currentReport.value = report
  hostCap.syncFromSource()
  const fail = report.failCount || 0
  const warn = report.warnCount || 0
  if (fail > 0) setHero("fail", `${fail} 项 FAIL`)
  else if (warn > 0) setHero("warn", `${warn} 项 WARN`)
  else setHero("ok", "全部通过")
  setPipeline(report.aiSummary ? 3 : 2)
}

async function runDiagnostic() {
  if (running.value) return
  running.value = true
  setHero("running", "执行中")
  setPipeline(1)
  currentReport.value = null
  activeFilter.value = "all"
  try {
    const { data } = await runFaultDiagnosticApi(props.appId)
    if (data) {
      renderReport(data)
      ElMessage.success("诊断完成")
    }
  } catch (e: unknown) {
    setHero("fail", "失败")
    ElMessage.error(e instanceof Error ? e.message : "诊断失败")
  } finally {
    running.value = false
  }
}

async function generateAiReport() {
  if (!currentReport.value?.reportId) {
    ElMessage.warning("请先完成 Layer① 诊断")
    return
  }
  aiLoading.value = true
  try {
    const reportJson = JSON.stringify(currentReport.value)
    const { summary } = await aiDiagnoseReportApi(currentReport.value.reportId, reportJson)
    currentReport.value = { ...currentReport.value, aiSummary: summary }
    setPipeline(3)
    ElMessage.success("AI 根因报告已生成")
  } catch (e: unknown) {
    ElMessage.error(e instanceof Error ? e.message : "AI 报告生成失败")
  } finally {
    aiLoading.value = false
  }
}

function openAiWithReport(question?: string, autoSend = false) {
  if (!currentReport.value?.reportId) {
    ElMessage.warning("请先完成 Layer① 诊断")
    return
  }
  setPipeline(3)
  openWithContext({
    appId: props.appId,
    diagnosticReportId: currentReport.value.reportId,
    diagnosticReportJson: JSON.stringify(currentReport.value),
    prefill: question,
    autoSend,
    pageContext: `集群运维 / 故障诊断 | 集群编码=${formatClusterNo(props.clusterNo, props.appId)}`
  })
  ElMessage.success(autoSend ? "已向 AI 助手发送问题" : "已打开 AI 助手并带入诊断报告")
}

async function loadHistory() {
  historyVisible.value = true
  historyLoading.value = true
  try {
    const { data } = await getFaultDiagnosticHistoryApi(props.appId)
    historyList.value = data ?? []
  } catch {
    historyList.value = []
    ElMessage.error("历史记录加载失败")
  } finally {
    historyLoading.value = false
  }
}

async function loadReportById(reportId: string) {
  try {
    const report = await getFaultDiagnosticReportApi(reportId)
    renderReport(report)
    historyVisible.value = false
  } catch (e: unknown) {
    ElMessage.error(e instanceof Error ? e.message : "报告加载失败")
  }
}

function formatHistoryTime(ts?: string | number) {
  if (!ts) return "未知时间"
  if (typeof ts === "number") return new Date(ts).toLocaleString()
  return String(ts)
}

function goTopologyTab() {
  router.replace({ path: `/app/detail/${props.appId}`, query: { tab: "app_ops_topology" } })
}

onMounted(async () => {
  await hostCap.fetchCapability()
  if (props.autoRun) await runDiagnostic()
})

defineExpose({ runDiagnostic })
</script>

<template>
  <div class="app-fault-diagnostic-page">
    <!-- 三层流水线 -->
    <div class="app-fault-diagnostic-pipeline">
      <template v-for="(item, idx) in PIPELINE_STEPS" :key="item.step">
        <div
          class="app-fault-diagnostic-pipeline__step"
          :class="{ 'is-active': pipelineStep >= item.step, 'is-current': pipelineStep === item.step }"
          :data-step="item.step"
        >
          <span class="app-fault-diagnostic-pipeline__dot">{{ item.num }}</span>
          <span class="app-fault-diagnostic-pipeline__label">{{ item.label }}</span>
        </div>
        <div v-if="idx < PIPELINE_STEPS.length - 1" class="app-fault-diagnostic-pipeline__line" aria-hidden="true" />
      </template>
    </div>

    <!-- Hero -->
    <div class="app-fault-diagnostic-hero" :class="`app-fault-diagnostic-hero--${heroState}`">
      <div class="app-fault-diagnostic-hero__icon-wrap">
        <el-icon v-if="heroState === 'running'" class="is-loading">
          <Loading />
        </el-icon>
        <el-icon v-else-if="heroState === 'ok'">
          <CircleCheck />
        </el-icon>
        <el-icon v-else-if="heroState === 'warn'">
          <Warning />
        </el-icon>
        <el-icon v-else-if="heroState === 'fail'">
          <CircleClose />
        </el-icon>
        <el-icon v-else>
          <FirstAidKit />
        </el-icon>
      </div>
      <div class="app-fault-diagnostic-hero__body">
        <div class="app-fault-diagnostic-hero__title-row">
          <h4 class="app-fault-diagnostic-hero__title">
            {{ HERO_TITLE[heroState] }}
          </h4>
          <span
            v-if="heroBadge"
            class="app-fault-diagnostic-hero__badge"
            :class="heroState !== 'idle' ? `app-fault-diagnostic-hero__badge--${heroState}` : ''"
          >
            {{ heroBadge }}
          </span>
        </div>
        <div class="app-fault-diagnostic-meta app-fault-diagnostic-meta--hero">
          <span class="app-fault-diagnostic-meta__item">
            <el-icon><PriceTag /></el-icon><em>集群</em>{{ appName || currentReport?.appName || "-" }}
          </span>
          <span class="app-fault-diagnostic-meta__item">
            <el-icon><Box /></el-icon><em>集群编码</em>{{ formatClusterNo(clusterNo, appId) }}
          </span>
          <span class="app-fault-diagnostic-meta__item">
            <el-icon><Coin /></el-icon><em>类型</em>{{ appTypeDesc || currentReport?.appTypeDesc || "-" }}
          </span>
          <span class="app-fault-diagnostic-meta__item">
            <el-icon><Operation /></el-icon><em>场景</em>{{ currentReport?.scenarioLabel || "主从连接中断" }}
          </span>
          <span v-if="currentReport?.durationMs" class="app-fault-diagnostic-meta__item">
            <el-icon><Clock /></el-icon><em>耗时</em>{{ currentReport.durationMs }} ms
          </span>
        </div>
        <div v-if="currentReport" class="app-fault-diagnostic-stat-row">
          <span
            v-for="item in statItems"
            :key="item.label"
            class="app-fault-diagnostic-stat"
            :class="`app-fault-diagnostic-stat--${item.cls}`"
          >
            <span class="app-fault-diagnostic-stat__num">{{ item.count }}</span>
            <span class="app-fault-diagnostic-stat__label">{{ item.label }}</span>
          </span>
        </div>
        <p class="app-fault-diagnostic-hero__hint">
          规则引擎自动采集并判定 PASS / WARN / FAIL；AI 基于报告解读根因，不替代探测。
          <a href="javascript:;" @click.prevent="goTopologyTab">拓扑诊断</a> 看结构，此处看运行时。
          <span v-if="unavailableHint" class="app-fault-diagnostic-hero__hint-note">
            {{ unavailableHint }}
          </span>
        </p>
      </div>
      <div class="app-fault-diagnostic-hero__actions">
        <button type="button" class="btn btn-primary btn-sm" :disabled="running" @click="runDiagnostic">
          立即诊断
        </button>
        <button type="button" class="btn btn-default btn-sm" @click="loadHistory">
          历史记录
        </button>
      </div>
    </div>

    <!-- Layer ① -->
    <section class="app-fault-diagnostic-section">
      <div class="app-fault-diagnostic-section__head">
        <h5 class="app-fault-diagnostic-section__title">
          <span class="app-fault-diagnostic-section__bar" aria-hidden="true" />
          <el-icon><List /></el-icon>
          自动检查
          <span class="app-fault-diagnostic-section__sub">Layer ① · 规则引擎</span>
        </h5>
        <div v-if="currentReport" class="app-fault-diagnostic-toolbar">
          <button
            v-for="f in FILTER_OPTIONS"
            :key="f.value"
            type="button"
            class="app-fault-diagnostic-filter"
            :class="{ 'is-active': activeFilter === f.value }"
            :data-filter="f.value"
            @click="activeFilter = f.value"
          >
            {{ f.label }}
          </button>
        </div>
      </div>
      <div class="app-fault-diagnostic-section__body">
        <div v-if="running" class="app-fault-diagnostic-empty">
          <div class="app-fault-diagnostic-empty__icon">
            <el-icon class="is-loading">
              <Loading />
            </el-icon>
          </div>
          <p>正在执行检查项…</p>
        </div>
        <div v-else-if="!currentReport" class="app-fault-diagnostic-empty">
          <div class="app-fault-diagnostic-empty__icon">
            <el-icon><FirstAidKit /></el-icon>
          </div>
          <p>尚未开始诊断</p>
          <span class="text-muted">{{ diagnosticHint }}</span>
          <div style="margin-top: 12px">
            <button type="button" class="btn btn-primary btn-sm app-fault-diagnostic-empty__cta" @click="runDiagnostic">
              开始诊断
            </button>
          </div>
        </div>
        <template v-else>
          <div v-for="group in groupedChecks" :key="group.name" class="app-fault-diagnostic-group">
            <div class="app-fault-diagnostic-group__title">
              <el-icon><FolderOpened /></el-icon>
              {{ group.name }}
              <span class="app-fault-diagnostic-group__count">{{ group.items.length }} 项</span>
            </div>
            <ul class="app-fault-diagnostic-check-list">
              <li
                v-for="check in group.items"
                v-show="isCheckVisible(check.level)"
                :key="check.code || check.name"
                class="app-fault-diagnostic-check"
                :class="levelClass(check.level)"
              >
                <div class="app-fault-diagnostic-check__icon">
                  <el-icon>
                    <component :is="getLevelIcon(check.level)" />
                  </el-icon>
                </div>
                <div class="app-fault-diagnostic-check__main">
                  <div class="app-fault-diagnostic-check__head">
                    <span
                      class="app-fault-diagnostic-check__level-tag"
                      :class="`app-fault-diagnostic-check__level-tag--${(check.level || 'skip').toLowerCase()}`"
                    >
                      {{ (check.level || "SKIP").toUpperCase() }}
                    </span>
                    <strong class="app-fault-diagnostic-check__name">{{ check.name }}</strong>
                    <code v-if="check.code" class="app-fault-diagnostic-check__code">{{ check.code }}</code>
                  </div>
                  <div class="app-fault-diagnostic-check__summary">
                    {{ check.summary }}
                  </div>
                  <div v-if="check.detail" class="app-fault-diagnostic-check__detail">
                    {{ check.detail }}
                  </div>
                  <div v-if="check.skipped && check.skipReason" class="app-fault-diagnostic-check__skip">
                    {{ check.skipReason }}
                  </div>
                  <template v-if="check.raw">
                    <button
                      type="button"
                      class="app-fault-diagnostic-check__raw-toggle"
                      :class="{ 'is-open': rawOpen[check.code || check.name || ''] }"
                      @click="rawOpen[check.code || check.name || ''] = !rawOpen[check.code || check.name || '']"
                    >
                      原始输出
                    </button>
                    <pre
                      v-show="rawOpen[check.code || check.name || '']"
                      class="app-fault-diagnostic-check__raw"
                    >{{ check.raw }}</pre>
                  </template>
                </div>
              </li>
            </ul>
          </div>
        </template>
      </div>
    </section>

    <!-- Layer ② -->
    <section class="app-fault-diagnostic-section app-fault-diagnostic-section--ai">
      <div class="app-fault-diagnostic-section__head">
        <h5 class="app-fault-diagnostic-section__title">
          <span class="app-fault-diagnostic-section__bar app-fault-diagnostic-section__bar--ai" aria-hidden="true" />
          <el-icon><MagicStick /></el-icon>
          AI 根因分析
          <span class="app-fault-diagnostic-section__sub">Layer ② · DeepSeek</span>
        </h5>
        <button
          type="button"
          class="btn btn-info btn-sm"
          :disabled="!currentReport || aiLoading"
          @click="generateAiReport"
        >
          {{ aiLoading ? "AI 分析中…" : "生成 AI 报告" }}
        </button>
      </div>
      <div class="app-fault-diagnostic-section__body">
        <div v-if="aiLoading" class="app-fault-diagnostic-empty app-fault-diagnostic-empty--compact">
          <el-icon class="is-loading">
            <Loading />
          </el-icon> AI 分析中，请稍候…
        </div>
        <div v-else-if="!currentReport?.aiSummary" class="app-fault-diagnostic-empty app-fault-diagnostic-empty--compact">
          完成 Layer ① 后，AI 将基于结构化检查结果生成根因分析与修复建议（需启用 cachecloud.ai）。
        </div>
        <div v-else class="app-fault-diagnostic-ai-report" v-html="renderMarkdown(currentReport.aiSummary)" />
      </div>
    </section>

    <!-- Layer ③ -->
    <section class="app-fault-diagnostic-section app-fault-diagnostic-section--chat">
      <div class="app-fault-diagnostic-section__head">
        <h5 class="app-fault-diagnostic-section__title">
          <span class="app-fault-diagnostic-section__bar app-fault-diagnostic-section__bar--chat" aria-hidden="true" />
          <el-icon><ChatDotRound /></el-icon>
          继续问 AI
          <span class="app-fault-diagnostic-section__sub">Layer ③ · 对话</span>
        </h5>
      </div>
      <div class="app-fault-diagnostic-section__body">
        <p class="app-fault-diagnostic-chat-hint">
          自动带入诊断报告与平台快照，在 <strong>Redis 运维助手</strong> 中继续追问、生成工单摘要。
        </p>
        <div v-if="currentReport" class="app-fault-diagnostic-chat-chips">
          <button type="button" class="app-fault-diagnostic-chip" @click="openAiWithReport('为什么排除网络问题？', true)">
            为什么排除网络问题？
          </button>
          <button type="button" class="app-fault-diagnostic-chip" @click="openAiWithReport('不停机怎么处理？', true)">
            不停机怎么处理？
          </button>
          <button type="button" class="app-fault-diagnostic-chip" @click="openAiWithReport('请给出优先级最高的修复步骤', true)">
            优先级最高的修复步骤
          </button>
        </div>
        <button type="button" class="btn btn-default btn-sm" :disabled="!currentReport" @click="openAiWithReport()">
          打开 AI 助手并带入报告
        </button>
      </div>
    </section>

    <!-- 历史记录弹层 -->
    <div v-if="historyVisible" class="app-fault-diagnostic-history-backdrop" @click="historyVisible = false" />
    <div
      v-if="historyVisible"
      class="app-fault-diagnostic-history-panel app-fault-diagnostic-history-panel--modal"
      role="dialog"
    >
      <div class="app-fault-diagnostic-history-panel__head">
        <strong>最近诊断记录</strong>
        <button type="button" class="close" aria-label="关闭" @click="historyVisible = false">
          &times;
        </button>
      </div>
      <ul v-loading="historyLoading" class="app-fault-diagnostic-history-list">
        <li v-if="!historyLoading && !historyList.length" class="app-fault-diagnostic-history-empty">
          暂无历史记录，先执行一次诊断
        </li>
        <li v-for="item in historyList" :key="item.reportId" class="app-fault-diagnostic-history-item">
          <button type="button" @click="loadReportById(item.reportId)">
            <span class="app-fault-diagnostic-history-item__time">{{ formatHistoryTime(item.createTime) }}</span>
            <span class="app-fault-diagnostic-history-item__badges">
              <span class="app-fault-diagnostic-stat app-fault-diagnostic-stat--fail app-fault-diagnostic-stat--mini">
                <span class="app-fault-diagnostic-stat__num">{{ item.failCount }} F</span>
              </span>
              <span class="app-fault-diagnostic-stat app-fault-diagnostic-stat--warn app-fault-diagnostic-stat--mini">
                <span class="app-fault-diagnostic-stat__num">{{ item.warnCount }} W</span>
              </span>
            </span>
          </button>
        </li>
      </ul>
    </div>
  </div>
</template>
