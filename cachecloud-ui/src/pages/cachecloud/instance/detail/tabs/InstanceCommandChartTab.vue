<script lang="ts" setup>
import type { ECharts } from "echarts"
import { Search } from "@element-plus/icons-vue"
import { initGrafanaChart } from "@/common/utils/chart-theme"
import type { ChartPoint } from "@/api/cachecloud"
import { getInstanceCommandChartsBatchApi, getInstanceCommandNamesApi } from "@/api/cachecloud"
import "@/common/assets/styles/app-tab.scss"
import { useAutoQuery } from "@@/composables/useAutoQuery"

const props = defineProps<{ instanceId: number }>()
const COLORS = ["#2f7ed8", "#E3170D", "#0d233a", "#8bbc21", "#1aadce", "#492970", "#804000", "#f28f43"]
const loading = ref(false)
const chartLoading = ref(false)
const showEmptyHint = ref(false)
const allCommands = ref<string[]>([])
const selectedCommands = ref<string[]>([])
const selectAll = ref(false)
const isIndeterminate = ref(false)
const selectAllRef = ref<HTMLInputElement>()
const chartRef = useTemplateRef<HTMLDivElement>("chartRef")
let chart: ECharts | null = null
let requestId = 0
let skipWatch = false

function shiftRange(ms: number): [Date, Date] { const end = new Date(); return [new Date(end.getTime() - ms), end] }
const rangeShortcuts = [
  { text: "最近 5 分钟", value: () => shiftRange(5 * 60 * 1000) },
  { text: "最近 15 分钟", value: () => shiftRange(15 * 60 * 1000) },
  { text: "最近 30 分钟", value: () => shiftRange(30 * 60 * 1000) },
  { text: "最近 1 小时", value: () => shiftRange(60 * 60 * 1000) },
  { text: "最近 6 小时", value: () => shiftRange(6 * 60 * 60 * 1000) },
  { text: "最近 24 小时", value: () => shiftRange(24 * 60 * 60 * 1000) },
  { text: "最近 7 天", value: () => shiftRange(7 * 24 * 60 * 60 * 1000) }
]
function fmt(d: Date) { const p = (n: number) => String(n).padStart(2, "0"); return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}` }
function defaultRange(): [string, string] { const [start, end] = shiftRange(6 * 60 * 60 * 1000); return [fmt(start), fmt(end)] }
const dateRange = ref<[string, string]>(defaultRange())

// 选完时间区间即自动查询
useAutoQuery(dateRange, () => handleQuery())
function queryParams() { return { startDate: dateRange.value[0], endDate: dateRange.value[1] } }

function syncSelectAll() {
  const checked = selectedCommands.value.length
  selectAll.value = allCommands.value.length > 0 && checked === allCommands.value.length
  isIndeterminate.value = checked > 0 && checked < allCommands.value.length
  if (selectAllRef.value) selectAllRef.value.indeterminate = isIndeterminate.value
}
function onSelectAll(event: Event) { selectedCommands.value = (event.target as HTMLInputElement).checked ? [...allCommands.value] : [] }

async function loadCommands() {
  const { data } = await getInstanceCommandNamesApi(props.instanceId, queryParams())
  allCommands.value = data ?? []
  skipWatch = true
  selectedCommands.value = selectedCommands.value.length
    ? selectedCommands.value.filter(name => allCommands.value.includes(name))
    : [...allCommands.value]
  skipWatch = false
  syncSelectAll()
}

async function renderChart(seriesMap: Record<string, ChartPoint[]>) {
  const echarts = await import("echarts")
  if (!chartRef.value) return
  chart?.dispose()
  chart = initGrafanaChart(echarts, chartRef.value)
  const names = Object.keys(seriesMap).filter(name => (seriesMap[name] ?? []).length > 0)
  showEmptyHint.value = names.length === 0
  chart.setOption({
    animation: false, color: COLORS,
    tooltip: { trigger: "axis" },
    legend: { type: "scroll", bottom: 0, data: names },
    grid: { left: 52, right: 24, top: 32, bottom: 48 },
    xAxis: { type: "time" },
    yAxis: { type: "value", name: "次数" },
    series: names.map(name => ({ name, type: "line", smooth: false, showSymbol: false, data: (seriesMap[name] ?? []).filter(p => p.x != null).map(p => [p.x, p.yDouble ?? p.y ?? 0]) }))
  })
}

async function fetchCharts() {
  const currentId = ++requestId
  chartLoading.value = true
  try {
    if (!selectedCommands.value.length) { await renderChart({}); return }
    const { data } = await getInstanceCommandChartsBatchApi(props.instanceId, { commands: selectedCommands.value.join(","), ...queryParams() })
    if (currentId === requestId) await renderChart(data ?? {})
  } finally { if (currentId === requestId) chartLoading.value = false }
}
async function handleQuery() { loading.value = true; try { await loadCommands(); await fetchCharts() } finally { loading.value = false } }
watch(selectedCommands, () => { if (!skipWatch) { syncSelectAll(); void fetchCharts() } }, { deep: true })
watch(() => props.instanceId, () => { dateRange.value = defaultRange(); selectedCommands.value = []; void handleQuery() }, { immediate: true })
function resizeChart() { chart?.resize() }
onMounted(() => window.addEventListener("resize", resizeChart))
onActivated(resizeChart)
onBeforeUnmount(() => { window.removeEventListener("resize", resizeChart); chart?.dispose() })
</script>

<template>
  <div v-loading="loading" class="app-command-analysis-page">
    <div class="app-command-toolbar">
      <el-date-picker v-model="dateRange" type="datetimerange" format="YYYY-MM-DD HH:mm:ss" value-format="YYYY-MM-DD HH:mm:ss" :shortcuts="rangeShortcuts" range-separator="至" start-placeholder="开始时间" end-placeholder="结束时间" class="app-command-toolbar__range" />
      <el-button type="primary" :icon="Search" @click="handleQuery">查询</el-button>
    </div>
    <p class="app-stat-range-hint">时间范围最长 7 天</p>
    <div class="app-command-filter app-command-filter--multi">
      <span class="app-command-filter__title">命令筛选</span>
      <div class="app-command-filter__options app-command-filter__options--multi">
        <span v-if="!allCommands.length" class="app-command-filter__empty">暂无命令数据</span>
        <template v-else>
          <label class="app-command-filter__option app-command-filter__option--all"><input ref="selectAllRef" type="checkbox" :checked="selectAll" @change="onSelectAll"><span>全选</span></label>
          <label v-for="cmd in allCommands" :key="cmd" class="app-command-filter__option" :class="{ 'is-active': selectedCommands.includes(cmd) }"><input v-model="selectedCommands" type="checkbox" :value="cmd"><span>{{ cmd }}</span></label>
        </template>
      </div>
    </div>
    <div v-loading="chartLoading" class="app-command-chart-wrap">
      <div ref="chartRef" class="app-command-chart" />
      <div v-if="showEmptyHint" class="app-command-empty-hint">暂无数据</div>
    </div>
  </div>
</template>
