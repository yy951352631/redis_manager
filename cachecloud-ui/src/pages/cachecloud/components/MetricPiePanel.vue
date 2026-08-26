<script lang="ts" setup>
import type { ECharts } from "echarts"
import { initGrafanaChart } from "@/common/utils/chart-theme"
import { formatMetricValue } from "@/common/utils/metric-format"
import { METRIC_CHART_COLORS } from "./metric-chart-slots"

export interface MetricPiePoint {
  name: string
  value: number
}

const props = withDefaults(defineProps<{
  points: MetricPiePoint[]
  title?: string
  loading?: boolean
}>(), {
  title: "命令分布统计",
  loading: false
})

const expandedVisible = ref(false)

const pieEl = useTemplateRef<HTMLDivElement>("pieRef")
const expandedPieEl = useTemplateRef<HTMLDivElement>("expandedPieRef")
let pieChart: ECharts | null = null
let expandedChart: ECharts | null = null
let echartsModule: typeof import("echarts") | null = null

async function getEcharts() {
  if (!echartsModule) echartsModule = await import("echarts")
  return echartsModule
}

const total = computed(() => props.points.reduce((sum, p) => sum + (p.value || 0), 0))

const shareRows = computed(() =>
  [...props.points]
    .sort((a, b) => b.value - a.value)
    .map(p => ({
      name: p.name,
      value: p.value,
      percent: total.value > 0 ? (p.value / total.value) * 100 : 0
    }))
)

function buildOption(expanded: boolean) {
  return {
    animation: false,
    color: METRIC_CHART_COLORS,
    tooltip: {
      trigger: "item",
      formatter: (params: { name: string, value: number, percent: number }) =>
        `${params.name}<br/>次数: ${formatMetricValue(params.value)}<br/>占比: ${formatMetricValue(params.percent)}%`
    },
    legend: { bottom: 8, left: "center", type: "scroll", textStyle: { fontSize: 12, color: "#6b7a90" } },
    series: [{
      type: "pie",
      radius: expanded ? "62%" : "58%",
      center: ["50%", "48%"],
      label: expanded
        ? { formatter: (params: { name: string, percent: number }) => `${params.name} ${formatMetricValue(params.percent)}%` }
        : { show: false },
      data: props.points.length
        ? props.points.map(p => ({ name: p.name, value: p.value }))
        : [{ name: "暂无数据", value: 0 }]
    }]
  }
}

async function render() {
  if (!pieEl.value) return
  const echarts = await getEcharts()
  pieChart?.dispose()
  pieChart = initGrafanaChart(echarts, pieEl.value)
  pieChart.setOption(buildOption(false))
}

async function renderExpanded() {
  if (!expandedPieEl.value) return
  const echarts = await getEcharts()
  expandedChart?.dispose()
  expandedChart = initGrafanaChart(echarts, expandedPieEl.value)
  expandedChart.setOption(buildOption(true))
}

function handleExpandedClosed() {
  expandedChart?.dispose()
  expandedChart = null
}

function resize() {
  pieChart?.resize()
  expandedChart?.resize()
}

function dispose() {
  pieChart?.dispose()
  pieChart = null
  expandedChart?.dispose()
  expandedChart = null
}

defineExpose({ render, resize, dispose })
</script>

<template>
  <div v-loading="loading" class="app-stat-chart-card manage-chart-card metric-card metric-pie-card">
    <div class="metric-card__head metric-card__head--static">
      <span class="metric-card__title">{{ title }}</span>
      <el-button class="metric-card__action" text size="small" title="放大查看" @mousedown.stop @click="expandedVisible = true">
        <el-icon><FullScreen /></el-icon>
      </el-button>
    </div>
    <div ref="pieRef" class="app-stat-pie-box" />

    <el-drawer
      v-model="expandedVisible"
      :title="title"
      direction="rtl"
      size="72%"
      class="metric-expand-drawer"
      append-to-body
      destroy-on-close
      @opened="renderExpanded"
      @closed="handleExpandedClosed"
    >
      <div class="metric-expand">
        <div ref="expandedPieRef" class="metric-expand__chart" />
        <div class="metric-expand__table">
          <div class="metric-expand__table-title">
            命令占比（共 {{ formatMetricValue(total) }} 次）
          </div>
          <el-table :data="shareRows" size="small" border stripe max-height="320">
            <el-table-column prop="name" label="命令" min-width="160" />
            <el-table-column label="次数" min-width="120" align="right">
              <template #default="{ row }">
                {{ formatMetricValue(row.value) }}
              </template>
            </el-table-column>
            <el-table-column label="占比" min-width="120" align="right">
              <template #default="{ row }">
                {{ formatMetricValue(row.percent) }}%
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
