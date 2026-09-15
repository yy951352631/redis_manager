<script lang="ts" setup>
import type { AppDetail, AppStatOverview, ChartPoint } from "@/api/cachecloud"
import type { MetricChartSlot } from "@/pages/cachecloud/components/metric-chart-slots"
import { useAutoQuery } from "@@/composables/useAutoQuery"
import { RefreshLeft, Search } from "@element-plus/icons-vue"
import {
  getAppOpsStatChartsApi,
  getAppStatChartsBatchApi,
  getAppStatClimaxApi,
  getAppStatOverviewApi
} from "@/api/cachecloud"
import { formatRedisVersion } from "@/common/utils/redis-version"
import { METRIC_CHART_SLOTS } from "@/pages/cachecloud/components/metric-chart-slots"
import MetricChartGrid from "@/pages/cachecloud/components/MetricChartGrid.vue"
import MetricPiePanel from "@/pages/cachecloud/components/MetricPiePanel.vue"
import "@/common/assets/styles/app-stat.scss"

const props = defineProps<{
  appId: number
  detail?: AppDetail
}>()

type ChartSlotDef = MetricChartSlot
type ChartSource = ChartSlotDef["source"]

const chartSlots = METRIC_CHART_SLOTS

/** 首屏优先渲染的折线图（其余延后加载） */
const PRIORITY_CHART_KEYS = new Set(["commands", "hits"])

const loading = ref(false)
const chartsLoading = ref(false)
const climaxLoading = ref(false)
const loadError = ref("")
const overview = ref<AppStatOverview | null>(null)
const chartBatches = ref<{ app: Record<string, ChartPoint[]>, ops: Record<string, ChartPoint[]> }>({
  app: {},
  ops: {}
})

const gridRef = useTemplateRef<InstanceType<typeof MetricChartGrid>>("gridRef")
const pieRef = useTemplateRef<InstanceType<typeof MetricPiePanel>>("pieRef")

const climaxList = computed(() => overview.value?.top5Climax ?? [])

const rangeShortcuts = [
  { text: "最近 5 分钟", value: () => shiftRange(5 * 60 * 1000) },
  { text: "最近 15 分钟", value: () => shiftRange(15 * 60 * 1000) },
  { text: "最近 30 分钟", value: () => shiftRange(30 * 60 * 1000) },
  { text: "最近 1 小时", value: () => shiftRange(60 * 60 * 1000) },
  { text: "最近 6 小时", value: () => shiftRange(6 * 60 * 60 * 1000) },
  { text: "最近 24 小时", value: () => shiftRange(24 * 60 * 60 * 1000) },
  { text: "最近 7 天", value: () => shiftRange(7 * 24 * 60 * 60 * 1000) }
]

function shiftRange(ms: number): [Date, Date] {
  const end = new Date()
  const start = new Date(end.getTime() - ms)
  return [start, end]
}

function fmtDateTime(d: Date) {
  const p = (n: number) => String(n).padStart(2, "0")
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
}

function parseLocalDateTime(text: string) {
  const normalized = text.trim().replace(/\+/g, " ").replace("T", " ")
  if (normalized.length <= 10) {
    const [y, m, d] = normalized.split("-").map(Number)
    return new Date(y, m - 1, d, 0, 0, 0)
  }
  const [datePart, timePart = "00:00:00"] = normalized.split(" ")
  const [y, m, d] = datePart.split("-").map(Number)
  const timeParts = timePart.split(":").map(Number)
  const hh = timeParts[0] ?? 0
  const mm = timeParts[1] ?? 0
  const ss = timeParts[2] ?? 0
  return new Date(y, m - 1, d, hh, mm, ss)
}

function defaultRange(): [string, string] {
  const end = new Date()
  const start = new Date(end.getTime() - 6 * 60 * 60 * 1000)
  return [fmtDateTime(start), fmtDateTime(end)]
}

const dateRange = ref<[string, string]>(defaultRange())

// 选完时间区间即自动查询；fetchData 里对 dateRange 的回写用 silent 包住，避免自触发
const { silent: silentRange } = useAutoQuery(dateRange, () => fetchData())

function queryParams() {
  const [start, end] = dateRange.value
  return { startDate: start, endDate: end }
}

function memProgressClass(ratio: number) {
  if (ratio >= 80) return "app-stat-mem-progress__bar--danger"
  if (ratio >= 60) return "app-stat-mem-progress__bar--warning"
  return "app-stat-mem-progress__bar--success"
}

function memUsedGb(mem: number, percent: number) {
  return (mem * percent / 100 / 1024).toFixed(2)
}

function memTotalGb(mem: number) {
  return (mem / 1024).toFixed(2)
}

function formatNumber(n: number) {
  return (n ?? 0).toLocaleString("zh-CN")
}

function normalizeOverview(data: AppStatOverview): AppStatOverview {
  return {
    ...data,
    top5Commands: data.top5Commands ?? [],
    top5Climax: data.top5Climax ?? [],
    conn: data.conn ?? 0,
    masterNum: data.masterNum ?? 0,
    slaveNum: data.slaveNum ?? 0,
    currentObjNum: data.currentObjNum ?? 0,
    machineNum: data.machineNum ?? 0
  }
}

function pointValue(p: ChartPoint) {
  if (p.yDouble != null) return p.yDouble
  if (p.ydouble != null) return p.ydouble
  return p.y ?? 0
}

function toLineData(points: ChartPoint[]) {
  const rows = points
    .filter(p => p.x != null && (p.y != null || p.yDouble != null || p.ydouble != null))
    .map(p => [p.x!, pointValue(p)] as [number, number])
  return rows.length > 1 ? rows.slice(0, -1) : rows
}

function detectNetUnit(points: ChartPoint[]) {
  let byteCount = 0
  let kbCount = 0
  let mbCount = 0
  for (const p of points) {
    const v = pointValue(p)
    if (v < 1024) byteCount++
    else if (v < 1024 * 1024) kbCount++
    else mbCount++
  }
  if (byteCount > kbCount) {
    return byteCount > mbCount ? { unit: 1, label: "byte" } : { unit: 1024 * 1024, label: "Mb" }
  }
  return kbCount > mbCount ? { unit: 1024, label: "Kb" } : { unit: 1024 * 1024, label: "Mb" }
}

function toNetLineData(points: ChartPoint[], unit: number) {
  return toLineData(points).map(([x, y]) => [x, Math.round(y / unit)] as [number, number])
}

function collectStatNames(source: ChartSource, slots = chartSlots) {
  const names = new Set<string>()
  for (const slot of slots) {
    if (slot.source === source) {
      slot.statNames.forEach(n => names.add(n))
    }
  }
  return Array.from(names)
}

async function fetchStatBatch(source: ChartSource, slots = chartSlots) {
  const params = queryParams()
  const names = collectStatNames(source, slots)
  const empty: Record<string, ChartPoint[]> = {}
  if (!names.length) return empty

  if (source === "app") {
    const { data } = await getAppStatChartsBatchApi(props.appId, { ...params, statName: names.join(",") })
    return data ?? empty
  }
  const { data } = await getAppOpsStatChartsApi(props.appId, { ...params, statName: names.join(",") })
  return data ?? empty
}

function mergeBatch(target: Record<string, ChartPoint[]>, incoming: Record<string, ChartPoint[]>) {
  Object.assign(target, incoming)
}

function deferTask(task: () => void | Promise<void>) {
  if (typeof requestIdleCallback !== "undefined") {
    requestIdleCallback(() => {
      void task()
    }, { timeout: 1200 })
  } else {
    setTimeout(() => {
      void task()
    }, 0)
  }
}

async function loadClimax() {
  climaxLoading.value = true
  try {
    const { data } = await getAppStatClimaxApi(props.appId, queryParams())
    if (overview.value && data) {
      overview.value = { ...overview.value, top5Climax: data }
    }
  } catch {
    // 峰值信息非关键路径，失败时保留空表
  } finally {
    climaxLoading.value = false
  }
}

function getSeriesData(
  slot: ChartSlotDef,
  batches: { app: Record<string, ChartPoint[]>, ops: Record<string, ChartPoint[]> },
  memTotalMb?: number
) {
  const store = slot.source === "app" ? batches.app : batches.ops
  const seriesList: { name: string, data: [number, number][] }[] = []
  let netUnit: { unit: number, label: string } | undefined
  let yAxisName = slot.yAxisName

  for (let i = 0; i < slot.statNames.length; i++) {
    const statName = slot.statNames[i]
    const points = store[statName] ?? []
    const name = slot.seriesNames?.[i] ?? statName
    if (slot.netUnit) {
      if (!netUnit) netUnit = detectNetUnit(points)
      yAxisName = netUnit.label
      seriesList.push({ name, data: toNetLineData(points, netUnit.unit) })
    } else {
      seriesList.push({ name, data: toLineData(points) })
    }
  }

  if (slot.memTotalLine && memTotalMb && seriesList[0]?.data.length) {
    seriesList.push({
      name: "集群总内存",
      data: seriesList[0].data.map(([x]) => [x, memTotalMb] as [number, number])
    })
  }

  if (slot.cpuPercent) {
    seriesList.forEach((series) => {
      series.data = series.data.map(([x, y]) => [x, Number(y.toFixed(2))])
    })
  }

  return {
    seriesList,
    yAxisName,
    dualHitRate: slot.dualHitRate,
    dualPersistence: slot.dualPersistence,
    cpuPercent: slot.cpuPercent,
    integerOnly: slot.integerOnly
  }
}

/** 交给 MetricChartGrid 渲染：它按序列配置画图，取数口径仍留在本页 */
function resolveSeries(slot: ChartSlotDef) {
  const memTotalMb = overview.value ? overview.value.mem / 1024 / 1024 : undefined
  return getSeriesData(slot, chartBatches.value, memTotalMb)
}

const piePoints = computed(() =>
  (overview.value?.top5Commands ?? []).map(point => ({
    name: point.commandName || String(point.y ?? ""),
    value: point.y ?? 0
  }))
)

async function renderChartSlots(slots: ChartSlotDef[]) {
  await gridRef.value?.renderSlots(slots.map(slot => slot.key))
}

async function loadPriorityCharts() {
  const prioritySlots = chartSlots.filter(slot => PRIORITY_CHART_KEYS.has(slot.key))
  const appBatch = await fetchStatBatch("app", prioritySlots)
  mergeBatch(chartBatches.value.app, appBatch)
  await renderChartSlots(prioritySlots)
}

async function loadDeferredCharts() {
  const deferredSlots = chartSlots.filter(slot => !PRIORITY_CHART_KEYS.has(slot.key))
  const appDeferredSlots = deferredSlots.filter(slot => slot.source === "app")
  const opsSlots = deferredSlots.filter(slot => slot.source === "ops")

  try {
    const [appBatch, opsBatch] = await Promise.all([
      fetchStatBatch("app", appDeferredSlots),
      fetchStatBatch("ops", opsSlots)
    ])
    mergeBatch(chartBatches.value.app, appBatch)
    mergeBatch(chartBatches.value.ops, opsBatch)
    await renderChartSlots(deferredSlots)
  } finally {
    chartsLoading.value = false
  }
}

async function renderAllCharts() {
  gridRef.value?.dispose()
  chartBatches.value = { app: {}, ops: {} }
  await nextTick()

  await pieRef.value?.render()
  chartsLoading.value = true
  try {
    await loadPriorityCharts()
  } finally {
    chartsLoading.value = false
  }
  deferTask(loadDeferredCharts)
}

async function fetchData() {
  loading.value = true
  loadError.value = ""
  chartsLoading.value = false
  climaxLoading.value = false
  try {
    const { data } = await getAppStatOverviewApi(props.appId, {
      ...queryParams(),
      includeClimax: false
    })
    overview.value = normalizeOverview(data)
    if (data.startDate && data.endDate) {
      silentRange(() => {
        dateRange.value = [
          fmtDateTime(parseLocalDateTime(data.startDate)),
          fmtDateTime(parseLocalDateTime(data.endDate))
        ]
      })
    }
  } catch (e: unknown) {
    overview.value = null
    loadError.value = e instanceof Error ? e.message : "加载集群统计失败"
    return
  } finally {
    loading.value = false
  }

  void loadClimax()
  await renderAllCharts()
}

function handleResize() {
  gridRef.value?.resize()
  pieRef.value?.resize()
}

function handleResetOrder() {
  gridRef.value?.resetOrder()
}

watch(() => props.appId, fetchData)

onMounted(() => {
  fetchData()
  window.addEventListener("resize", handleResize)
})

onActivated(async () => {
  if (overview.value && !gridRef.value?.renderedCount()) {
    await renderAllCharts()
  }
  handleResize()
})

onBeforeUnmount(() => {
  window.removeEventListener("resize", handleResize)
  gridRef.value?.dispose()
  pieRef.value?.dispose()
})
</script>

<template>
  <div v-loading="loading" class="app-stat-page">
    <div class="filter-bar">
      <el-date-picker
        v-model="dateRange"
        type="datetimerange"
        format="YYYY-MM-DD HH:mm:ss"
        value-format="YYYY-MM-DD HH:mm:ss"
        :shortcuts="rangeShortcuts"
        range-separator="至"
        start-placeholder="开始时间"
        end-placeholder="结束时间"
        class="filter-bar__range"
      />
      <el-button type="primary" :icon="Search" @click="fetchData">
        查询
      </el-button>
      <el-button :icon="RefreshLeft" title="把图表顺序恢复为默认排列" @click="handleResetOrder">
        恢复默认顺序
      </el-button>
    </div>
    <p v-if="overview?.statRangeClamped" class="range-hint">
      查询区间已限制为最近 7 天。
    </p>
    <el-alert v-if="loadError" type="error" :title="loadError" show-icon :closable="false" class="load-error" />

    <template v-if="overview">
      <div class="panels-row">
        <div class="panel-col">
          <h4 class="app-stat-section-title">
            全局信息
          </h4>
          <table class="app-stat-info-table">
            <tbody>
              <tr>
                <td>内存使用率</td>
                <td>
                  <div class="app-stat-mem-progress">
                    <div
                      class="app-stat-mem-progress__bar"
                      :class="memProgressClass(overview.memUsePercent)"
                      :style="{ width: `${Math.min(overview.memUsePercent, 100)}%` }"
                    />
                    <span class="app-stat-mem-progress__label">
                      {{ memUsedGb(overview.mem, overview.memUsePercent) }}G&nbsp;&nbsp;Used/{{ memTotalGb(overview.mem) }}G&nbsp;&nbsp;Total
                    </span>
                  </div>
                </td>
                <td>当前连接数</td>
                <td>{{ overview.conn }}</td>
              </tr>
              <tr>
                <td>集群版本</td>
                <td>{{ formatRedisVersion(overview.versionName || detail?.versionName) || "-" }}</td>
                <td>集群类型</td>
                <td>{{ overview.typeDesc || detail?.typeDesc || "-" }}</td>
              </tr>
              <tr>
                <td>集群主节点数</td>
                <td>{{ overview.masterNum }}</td>
                <td>集群从节点数</td>
                <td>{{ overview.slaveNum }}</td>
              </tr>
              <tr>
                <td>集群命中率</td>
                <td>{{ overview.hitPercent }}%</td>
                <td>当前对象数</td>
                <td>{{ formatNumber(overview.currentObjNum) }}</td>
              </tr>
              <tr>
                <td>集群当前状态</td>
                <td>{{ detail?.runtimeStatus === 5 ? "未知" : (overview.statusDesc || detail?.runtimeStatusLabel || "-") }}</td>
                <td>集群分布机器数量</td>
                <td>{{ overview.machineNum }}</td>
              </tr>
            </tbody>
          </table>

          <h4 class="app-stat-section-title">
            各命令峰值信息
          </h4>
          <table v-loading="climaxLoading" class="app-stat-info-table">
            <tbody>
              <tr>
                <td>命令</td>
                <td>峰值QPM</td>
                <td colspan="2">
                  峰值产生时间
                </td>
              </tr>
              <tr v-for="row in climaxList" :key="row.commandName">
                <td>{{ row.commandName }}</td>
                <td>{{ formatNumber(row.commandCount) }}</td>
                <td colspan="2">
                  {{ row.createTime || "—" }}
                </td>
              </tr>
              <tr v-if="!climaxList.length">
                <td colspan="4" class="empty-cell">
                  暂无数据
                </td>
              </tr>
            </tbody>
          </table>
        </div>

        <div class="panel-col">
          <h4 class="app-stat-section-title">
            命令统计
          </h4>
          <MetricPiePanel ref="pieRef" :points="piePoints" />
        </div>
      </div>

      <MetricChartGrid
        ref="gridRef"
        storage-key="app-stat"
        :slots="chartSlots"
        :resolve="resolveSeries"
        :loading="chartsLoading"
      />
    </template>
  </div>
</template>

<style scoped>
.load-error {
  margin-bottom: 12px;
}

.empty-cell {
  text-align: center;
  color: var(--rp-text-muted);
}
</style>
