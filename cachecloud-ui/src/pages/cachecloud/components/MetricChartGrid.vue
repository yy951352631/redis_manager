<script lang="ts" setup>
import type { ECharts } from "echarts"
import type { ComponentPublicInstance } from "vue"
import type { MetricChartSlot, MetricSeriesConfig } from "./metric-chart-slots"
import draggable from "vuedraggable"
import { initGrafanaChart } from "@/common/utils/chart-theme"
import {
  getMetricChartOrder,
  removeMetricChartOrder,
  setMetricChartOrder
} from "@/common/utils/local-storage"
import { formatMetricTime, formatMetricValue } from "@/common/utils/metric-format"
import { mergeChartOrder, METRIC_CHART_COLORS } from "./metric-chart-slots"

const props = defineProps<{
  /** localStorage 中的分组名，决定这份排序偏好和谁共享 */
  storageKey: string
  slots: MetricChartSlot[]
  /** 由页面把原始数据整理成序列——两个页面的取数口径不同，这部分不下沉到组件 */
  resolve: (slot: MetricChartSlot) => MetricSeriesConfig | null
  loading?: boolean
}>()

/** 放大视图底部明细表展示的采样点数量 */
const RECENT_ROW_COUNT = 20

const orderedSlots = ref<MetricChartSlot[]>([])
const expandedSlot = ref<MetricChartSlot | null>(null)
const expandedVisible = ref(false)

const chartEls = new Map<string, HTMLDivElement>()
const chartInstances = new Map<string, ECharts>()
const expandedChartEl = useTemplateRef<HTMLDivElement>("expandedChartRef")
let expandedChart: ECharts | null = null
let echartsModule: typeof import("echarts") | null = null

async function getEcharts() {
  if (!echartsModule) echartsModule = await import("echarts")
  return echartsModule
}

// #region 排序
function applyStoredOrder() {
  orderedSlots.value = mergeChartOrder(props.slots, getMetricChartOrder(props.storageKey))
}

function handleDragEnd() {
  setMetricChartOrder(props.storageKey, orderedSlots.value.map(slot => slot.key))
  // 卡片本身尺寸不变，但拖动过程中 DOM 被搬动过，统一 resize 一次更稳
  resize()
}

function resetOrder() {
  removeMetricChartOrder(props.storageKey)
  applyStoredOrder()
  nextTick(() => resize())
}
// #endregion

// #region 渲染
function setChartRef(key: string, el: Element | ComponentPublicInstance | null) {
  if (el) chartEls.set(key, el as HTMLDivElement)
  else chartEls.delete(key)
}

function buildOption(config: MetricSeriesConfig, zoomable: boolean) {
  const { seriesList, yAxisName, dualHitRate, dualPersistence, cpuPercent, integerOnly } = config
  const hasLegend = seriesList.length > 1
  const dualAxis = Boolean(dualHitRate || dualPersistence)
  const valueFormatter = (value: number) => {
    // 连接数这类天然是整数，图上出现 12.5 个连接只会让人以为读错了
    if (integerOnly) return Math.round(value).toLocaleString()
    return cpuPercent ? `${formatMetricValue(value)}%` : formatMetricValue(value)
  }

  const series = seriesList.map((item, index) => ({
    name: item.name,
    type: "line" as const,
    smooth: false,
    showSymbol: false,
    symbol: "circle",
    symbolSize: 4,
    lineStyle: { width: 2 },
    // 双轴图里走右轴的永远是最后一条序列（命中率% / 耗时 ms）。
    // 原来写死 index===1，命中统计合入第三条序列后就会挂错轴。
    yAxisIndex: dualAxis && index === seriesList.length - 1 ? 1 : 0,
    data: item.data,
    ...(item.name.endsWith("总内存")
      ? { lineStyle: { width: 2, type: "dashed" as const, color: "#0d233a" } }
      : {})
  }))

  const legendBottom = zoomable ? 44 : 4
  const gridBottom = zoomable ? (hasLegend ? 88 : 64) : (hasLegend ? 48 : 32)

  const axisLabelStyle = { fontSize: 12, color: "#6b7a90" }
  const axisNameStyle = { color: "#6b7a90", fontSize: 12 }
  const valueAxisLabel = { ...axisLabelStyle, formatter: (value: number) => formatMetricValue(value) }

  return {
    animation: false,
    color: METRIC_CHART_COLORS,
    tooltip: { trigger: "axis", textStyle: { fontSize: 13 }, valueFormatter },
    legend: hasLegend
      ? { bottom: legendBottom, type: "scroll", textStyle: { fontSize: 12, color: "#6b7a90" } }
      : undefined,
    grid: { left: 68, right: dualAxis ? 62 : 24, top: 30, bottom: gridBottom },
    dataZoom: zoomable
      ? [
          { type: "inside", filterMode: "none" },
          { type: "slider", height: 24, bottom: 12, borderColor: "#d1d5db", fillerColor: "rgba(50, 116, 217, 0.12)" }
        ]
      : undefined,
    xAxis: { type: "time", axisLabel: axisLabelStyle },
    yAxis: dualHitRate
      ? [
          { type: "value", scale: true, name: "次数", nameTextStyle: axisNameStyle, axisLabel: valueAxisLabel },
          { type: "value", min: 0, max: 100, name: "命中率(%)", position: "right", nameTextStyle: axisNameStyle, axisLabel: valueAxisLabel }
        ]
      : dualPersistence
        ? [
            { type: "value", min: 0, name: "次数", nameTextStyle: axisNameStyle, axisLabel: valueAxisLabel },
            { type: "value", min: 0, name: "ms", position: "right", nameTextStyle: axisNameStyle, axisLabel: valueAxisLabel }
          ]
        : {
            type: "value",
            scale: true,
            // 整数指标不要小数刻度，否则轴上会出现 0.5 个连接这种刻度
            minInterval: integerOnly ? 1 : undefined,
            name: yAxisName,
            nameTextStyle: axisNameStyle,
            axisLabel: valueAxisLabel
          },
    series
  }
}

function disposeSlot(key: string) {
  chartInstances.get(key)?.dispose()
  chartInstances.delete(key)
}

async function renderSlot(slot: MetricChartSlot) {
  const el = chartEls.get(slot.key)
  const config = props.resolve(slot)
  if (!el || !config) return
  const echarts = await getEcharts()
  disposeSlot(slot.key)
  const chart = initGrafanaChart(echarts, el)
  chart.setOption(buildOption(config, false))
  chartInstances.set(slot.key, chart)
}

/** 分批渲染：一次性 setOption 16 张图会让主线程卡住接近一秒 */
async function renderSlots(keys?: string[]) {
  await nextTick()
  const targets = keys
    ? orderedSlots.value.filter(slot => keys.includes(slot.key))
    : orderedSlots.value
  for (let i = 0; i < targets.length; i += 2) {
    await Promise.all(targets.slice(i, i + 2).map(renderSlot))
    await new Promise<void>(resolve => requestAnimationFrame(() => resolve()))
  }
}

function resize() {
  chartInstances.forEach(chart => chart.resize())
  expandedChart?.resize()
}

function dispose() {
  chartInstances.forEach(chart => chart.dispose())
  chartInstances.clear()
  expandedChart?.dispose()
  expandedChart = null
}

function renderedCount() {
  return chartInstances.size
}
// #endregion

// #region 放大
function openExpanded(slot: MetricChartSlot) {
  expandedSlot.value = slot
  expandedVisible.value = true
}

const expandedConfig = computed(() =>
  expandedSlot.value ? props.resolve(expandedSlot.value) : null
)

/** 序列点数可达上万，统计走单次遍历，不用 Math.max(...arr) 以免爆栈 */
const expandedSummary = computed(() => {
  const config = expandedConfig.value
  if (!config) return []
  return config.seriesList.map((item) => {
    let count = 0
    let sum = 0
    let max = Number.NEGATIVE_INFINITY
    let min = Number.POSITIVE_INFINITY
    let latest: number | null = null
    for (const [, value] of item.data) {
      if (!Number.isFinite(value)) continue
      count++
      sum += value
      if (value > max) max = value
      if (value < min) min = value
      latest = value
    }
    return count
      ? { name: item.name, latest, avg: sum / count, max, min }
      : { name: item.name, latest: null, avg: null, max: null, min: null }
  })
})

const expandedColumns = computed(() => expandedConfig.value?.seriesList.map(item => item.name) ?? [])

const expandedRows = computed(() => {
  const config = expandedConfig.value
  if (!config?.seriesList.length) return []
  // 以点数最多的序列作为时间基准，其余序列按时间戳对齐取值
  const base = config.seriesList.reduce((a, b) => (b.data.length > a.data.length ? b : a))
  const stamps = base.data.slice(-RECENT_ROW_COUNT).map(([x]) => x).reverse()
  const lookups = config.seriesList.map(item => new Map(item.data))
  return stamps.map(stamp => ({
    stamp,
    time: formatMetricTime(stamp),
    values: lookups.map(lookup => lookup.get(stamp) ?? null)
  }))
})

const expandedUnit = computed(() => (expandedConfig.value?.cpuPercent ? "%" : ""))

function formatExpandedValue(value: number | null) {
  if (value === null) return "-"
  return `${formatMetricValue(value)}${expandedUnit.value}`
}

async function renderExpandedChart() {
  const config = expandedConfig.value
  if (!config || !expandedChartEl.value) return
  const echarts = await getEcharts()
  expandedChart?.dispose()
  expandedChart = initGrafanaChart(echarts, expandedChartEl.value)
  expandedChart.setOption(buildOption(config, true))
}

function handleExpandedClosed() {
  expandedChart?.dispose()
  expandedChart = null
  expandedSlot.value = null
}
// #endregion

watch(() => props.slots, applyStoredOrder, { immediate: true })
watch(() => props.storageKey, applyStoredOrder)

defineExpose({ renderSlots, resize, dispose, resetOrder, renderedCount })
</script>

<template>
  <div class="metric-grid-wrap">
    <div v-loading="loading">
      <draggable
        v-model="orderedSlots"
        item-key="key"
        class="app-stat-chart-grid"
        handle=".metric-card__head"
        filter=".metric-card__action"
        :prevent-on-filter="false"
        :animation="160"
        ghost-class="metric-card--ghost"
        @end="handleDragEnd"
      >
        <template #item="{ element }">
          <div class="app-stat-chart-card manage-chart-card metric-card">
            <div class="metric-card__head" title="按住标题栏可拖动调整位置">
              <span class="metric-card__title">{{ element.title }}</span>
              <el-button
                class="metric-card__action"
                text
                size="small"
                title="放大查看"
                @mousedown.stop
                @click="openExpanded(element)"
              >
                <el-icon><FullScreen /></el-icon>
              </el-button>
            </div>
            <div :ref="el => setChartRef(element.key, el)" class="app-stat-chart-box" />
          </div>
        </template>
      </draggable>
    </div>

    <el-drawer
      v-model="expandedVisible"
      :title="expandedSlot?.title"
      direction="rtl"
      size="72%"
      class="metric-expand-drawer"
      append-to-body
      destroy-on-close
      @opened="renderExpandedChart"
      @closed="handleExpandedClosed"
    >
      <div class="metric-expand">
        <div ref="expandedChartRef" class="metric-expand__chart" />

        <div class="metric-expand__summary">
          <div v-for="item in expandedSummary" :key="item.name" class="metric-summary-card">
            <div class="metric-summary-card__name">
              {{ item.name }}
            </div>
            <div class="metric-summary-card__body">
              <div><span>最新</span><b>{{ formatExpandedValue(item.latest) }}</b></div>
              <div><span>均值</span><b>{{ formatExpandedValue(item.avg) }}</b></div>
              <div><span>最大</span><b>{{ formatExpandedValue(item.max) }}</b></div>
              <div><span>最小</span><b>{{ formatExpandedValue(item.min) }}</b></div>
            </div>
          </div>
        </div>

        <div class="metric-expand__table">
          <div class="metric-expand__table-title">
            近期数据（最近 {{ expandedRows.length }} 个采样点，按时间倒序）
          </div>
          <el-table :data="expandedRows" size="small" border stripe max-height="320">
            <el-table-column prop="time" label="时间" width="150" />
            <el-table-column
              v-for="(name, index) in expandedColumns"
              :key="name"
              :label="name"
              min-width="120"
              align="right"
            >
              <template #default="{ row }">
                {{ formatExpandedValue(row.values[index]) }}
              </template>
            </el-table-column>
            <template #empty>
              暂无数据
            </template>
          </el-table>
        </div>
      </div>
    </el-drawer>
  </div>
</template>
