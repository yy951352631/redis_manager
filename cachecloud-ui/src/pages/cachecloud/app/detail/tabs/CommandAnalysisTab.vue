<script lang="ts" setup>
import type { ECharts } from "echarts"
import type { ChartPoint } from "@/api/cachecloud"
import { useAutoQuery } from "@@/composables/useAutoQuery"
import { Search } from "@element-plus/icons-vue"
import { getAppCommandChartsBatchApi, getAppCommandNamesApi } from "@/api/cachecloud"
import { initGrafanaChart } from "@/common/utils/chart-theme"
import "@/common/assets/styles/app-tab.scss"

const props = defineProps<{ appId: number }>()

const HC_COLORS = ["#2f7ed8", "#E3170D", "#0d233a", "#8bbc21", "#1aadce", "#492970", "#804000", "#f28f43"]

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
let chartRequestId = 0
let skipCommandWatch = false

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

function defaultRange(): [string, string] {
  const end = new Date()
  const start = new Date(end.getTime() - 6 * 60 * 60 * 1000)
  return [fmtDateTime(start), fmtDateTime(end)]
}

const dateRange = ref<[string, string]>(defaultRange())

// 选完时间区间即自动查询
useAutoQuery(dateRange, () => handleQuery())

function queryParams() {
  const [start, end] = dateRange.value
  return { startDate: start, endDate: end }
}

function syncSelectAllState() {
  const total = allCommands.value.length
  const checked = selectedCommands.value.length
  selectAll.value = total > 0 && checked === total
  isIndeterminate.value = checked > 0 && checked < total
  if (selectAllRef.value) {
    selectAllRef.value.indeterminate = isIndeterminate.value
  }
}

function onSelectAllChange(event: Event) {
  const checked = (event.target as HTMLInputElement).checked
  selectedCommands.value = checked ? [...allCommands.value] : []
  isIndeterminate.value = false
}

function onCommandCheckboxChange() {
  syncSelectAllState()
}

async function loadCommandNames() {
  const { data } = await getAppCommandNamesApi(props.appId, queryParams())
  allCommands.value = data ?? []
  skipCommandWatch = true
  if (!selectedCommands.value.length && allCommands.value.length) {
    selectedCommands.value = [...allCommands.value]
  } else {
    selectedCommands.value = selectedCommands.value.filter(c => allCommands.value.includes(c))
  }
  skipCommandWatch = false
  syncSelectAllState()
}

async function renderChart(seriesMap: Record<string, ChartPoint[]>) {
  const echarts = await import("echarts")
  if (!chartRef.value) return
  chart?.dispose()
  chart = initGrafanaChart(echarts, chartRef.value)
  const names = Object.keys(seriesMap)
  showEmptyHint.value = names.length === 0
  chart.setOption({
    color: HC_COLORS,
    tooltip: { trigger: "axis" },
    legend: { type: "scroll", bottom: 0, data: names },
    grid: { left: 48, right: 24, top: 24, bottom: 48 },
    xAxis: { type: "time" },
    yAxis: { type: "value", name: "次数" },
    series: names.map(name => ({
      name,
      type: "line",
      smooth: true,
      showSymbol: false,
      data: (seriesMap[name] ?? []).map(p => [p.x, p.y])
    }))
  })
}

async function renderEmptyChart() {
  await renderChart({})
}

async function fetchChartData() {
  const requestId = ++chartRequestId
  if (!selectedCommands.value.length) {
    chartLoading.value = true
    try {
      await renderEmptyChart()
    } finally {
      if (requestId === chartRequestId) {
        chartLoading.value = false
      }
    }
    return
  }

  chartLoading.value = true
  try {
    const { data } = await getAppCommandChartsBatchApi(props.appId, {
      commands: selectedCommands.value.join(","),
      ...queryParams()
    })
    if (requestId !== chartRequestId) return
    await renderChart(data ?? {})
  } finally {
    if (requestId === chartRequestId) {
      chartLoading.value = false
    }
  }
}

async function handleQuery() {
  loading.value = true
  try {
    await loadCommandNames()
    await fetchChartData()
  } finally {
    loading.value = false
  }
}

async function init() {
  await handleQuery()
}

watch(selectedCommands, () => {
  if (skipCommandWatch) return
  syncSelectAllState()
  void fetchChartData()
}, { deep: true })

watch(() => props.appId, init, { immediate: true })

function handleResize() {
  chart?.resize()
}

onMounted(() => {
  window.addEventListener("resize", handleResize)
})

onActivated(() => {
  handleResize()
})

onBeforeUnmount(() => {
  window.removeEventListener("resize", handleResize)
  chart?.dispose()
})
</script>

<template>
  <div v-loading="loading" class="app-command-analysis-page">
    <div class="app-command-toolbar">
      <el-date-picker
        v-model="dateRange"
        type="datetimerange"
        format="YYYY-MM-DD HH:mm:ss"
        value-format="YYYY-MM-DD HH:mm:ss"
        :shortcuts="rangeShortcuts"
        range-separator="至"
        start-placeholder="开始时间"
        end-placeholder="结束时间"
        class="app-command-toolbar__range"
      />
      <el-button type="primary" :icon="Search" @click="handleQuery">
        查询
      </el-button>
    </div>
    <p class="app-stat-range-hint">
      时间范围最长 7 天
    </p>

    <div class="app-command-filter app-command-filter--multi">
      <span class="app-command-filter__title">命令筛选</span>
      <div class="app-command-filter__options app-command-filter__options--multi">
        <template v-if="!allCommands.length">
          <span class="app-command-filter__empty">暂无命令数据</span>
        </template>
        <template v-else>
          <label class="app-command-filter__option app-command-filter__option--all">
            <input
              ref="selectAllRef"
              type="checkbox"
              :checked="selectAll"
              @change="onSelectAllChange"
            >
            <span>全选</span>
          </label>
          <label
            v-for="cmd in allCommands"
            :key="cmd"
            class="app-command-filter__option"
            :class="{ 'is-active': selectedCommands.includes(cmd) }"
          >
            <input
              v-model="selectedCommands"
              type="checkbox"
              :value="cmd"
              @change="onCommandCheckboxChange"
            >
            <span>{{ cmd }}</span>
          </label>
        </template>
      </div>
    </div>

    <div v-loading="chartLoading" class="app-command-chart-wrap">
      <div ref="chartRef" class="app-command-chart" />
      <div v-if="showEmptyHint" class="app-command-empty-hint">
        暂无数据
      </div>
    </div>
  </div>
</template>
