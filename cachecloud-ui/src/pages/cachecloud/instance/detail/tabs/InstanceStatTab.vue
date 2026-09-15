<script lang="ts" setup>
import type { ChartPoint, InstanceStat } from "@/api/cachecloud"
import type { MetricChartSlot, MetricSeries } from "@/pages/cachecloud/components/metric-chart-slots"
import { useAutoQuery } from "@@/composables/useAutoQuery"
import { RefreshLeft, Search } from "@element-plus/icons-vue"
import { getInstanceStatApi, getInstanceStatChartsApi } from "@/api/cachecloud"
import { formatUptime } from "@/common/utils/duration"
import { METRIC_CHART_SLOTS } from "@/pages/cachecloud/components/metric-chart-slots"
import MetricChartGrid from "@/pages/cachecloud/components/MetricChartGrid.vue"
import MetricPiePanel from "@/pages/cachecloud/components/MetricPiePanel.vue"
import "@/common/assets/styles/app-tab.scss"
import "@/common/assets/styles/app-stat.scss"

const props = defineProps<{ instanceId: number, appId?: number }>()
const emit = defineEmits<{ openApp: [appId: number] }>()

const chartSlots = METRIC_CHART_SLOTS

const loading = ref(false)
const chartsLoading = ref(false)
const stat = ref<InstanceStat | null>(null)
const chartData = ref<Record<string, ChartPoint[]>>({})
const gridRef = useTemplateRef<InstanceType<typeof MetricChartGrid>>("gridRef")
const pieRef = useTemplateRef<InstanceType<typeof MetricPiePanel>>("pieRef")

function shiftRange(ms: number): [Date, Date] {
  const end = new Date()
  return [new Date(end.getTime() - ms), end]
}
const rangeShortcuts = [
  { text: "最近 5 分钟", value: () => shiftRange(5 * 60 * 1000) },
  { text: "最近 15 分钟", value: () => shiftRange(15 * 60 * 1000) },
  { text: "最近 30 分钟", value: () => shiftRange(30 * 60 * 1000) },
  { text: "最近 1 小时", value: () => shiftRange(60 * 60 * 1000) },
  { text: "最近 6 小时", value: () => shiftRange(6 * 60 * 60 * 1000) },
  { text: "最近 24 小时", value: () => shiftRange(24 * 60 * 60 * 1000) },
  { text: "最近 7 天", value: () => shiftRange(7 * 24 * 60 * 60 * 1000) }
]
function fmt(d: Date) {
  const p = (n: number) => String(n).padStart(2, "0")
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
}
function defaultRange(): [string, string] {
  const [start, end] = shiftRange(6 * 60 * 60 * 1000)
  return [fmt(start), fmt(end)]
}
const dateRange = ref<[string, string]>(defaultRange())

// 选完时间区间即自动查询
useAutoQuery(dateRange, () => fetchData())
function queryParams() {
  return { startDate: dateRange.value[0], endDate: dateRange.value[1] }
}
function pointValue(p: ChartPoint) {
  return p.yDouble ?? p.ydouble ?? p.y ?? 0
}
function lineData(points: ChartPoint[]) {
  return points.filter(p => p.x != null).map(p => [p.x!, pointValue(p)] as [number, number])
}
function netUnit(points: ChartPoint[]) {
  let max = 0
  for (const point of points) {
    const value = pointValue(point)
    if (value > max) max = value
  }
  return max >= 1024 * 1024 ? { divisor: 1024 * 1024, label: "MB" } : max >= 1024 ? { divisor: 1024, label: "KB" } : { divisor: 1, label: "byte" }
}
function memBarClass(ratio: number) {
  return ratio >= 80 ? "is-danger" : "is-success"
}

/** 交给 MetricChartGrid 渲染：本页数据走单接口，序列整理仍留在这里 */
function resolveSeries(slot: MetricChartSlot) {
  const unit = slot.netUnit
    ? netUnit(slot.statNames.flatMap(name => chartData.value[name] ?? []))
    : null
  const seriesList: MetricSeries[] = slot.statNames.map((name, index) => ({
    name: slot.seriesNames[index] ?? name,
    data: lineData(chartData.value[name] ?? []).map(([x, y]) => [
      x,
      slot.cpuPercent ? Number(y.toFixed(2)) : unit ? y / unit.divisor : y
    ] as [number, number])
  }))

  if (slot.memTotalLine && stat.value?.memTotalGb && seriesList[0]?.data.length) {
    const totalMb = stat.value.memTotalGb * 1024
    seriesList.push({
      name: "节点总内存",
      data: seriesList[0].data.map(([x]) => [x, totalMb] as [number, number])
    })
  }

  return {
    seriesList,
    yAxisName: unit?.label ?? slot.yAxisName,
    dualHitRate: slot.dualHitRate,
    dualPersistence: slot.dualPersistence,
    cpuPercent: slot.cpuPercent,
    integerOnly: slot.integerOnly
  }
}

const piePoints = computed(() =>
  (stat.value?.topCommands ?? []).map(point => ({
    name: point.commandName || String(point.y ?? ""),
    value: point.y ?? 0
  }))
)

async function renderCharts() {
  gridRef.value?.dispose()
  await nextTick()
  await pieRef.value?.render()
  await gridRef.value?.renderSlots()
}

async function fetchData() {
  loading.value = true
  chartsLoading.value = true
  try {
    const statNames = Array.from(new Set(chartSlots.flatMap(slot => slot.statNames))).join(",")
    const [summaryResponse, chartsResponse] = await Promise.all([
      getInstanceStatApi(props.instanceId, queryParams()),
      getInstanceStatChartsApi(props.instanceId, { statName: statNames, ...queryParams() })
    ])
    stat.value = summaryResponse.data
    chartData.value = chartsResponse.data ?? {}
    await renderCharts()
  } finally {
    loading.value = false
    chartsLoading.value = false
  }
}
function resizeCharts() {
  gridRef.value?.resize()
  pieRef.value?.resize()
}

function handleResetOrder() {
  gridRef.value?.resetOrder()
}
watch(() => props.instanceId, () => {
  dateRange.value = defaultRange()
  void fetchData()
}, { immediate: true })
onMounted(() => window.addEventListener("resize", resizeCharts))
onActivated(resizeCharts)
onBeforeUnmount(() => {
  window.removeEventListener("resize", resizeCharts)
  gridRef.value?.dispose()
  pieRef.value?.dispose()
})
</script>

<template>
  <div v-loading="loading" class="app-tab-embed app-instance-stat-page app-stat-page">
    <div class="filter-bar">
      <el-date-picker v-model="dateRange" type="datetimerange" format="YYYY-MM-DD HH:mm:ss" value-format="YYYY-MM-DD HH:mm:ss" :shortcuts="rangeShortcuts" range-separator="至" start-placeholder="开始时间" end-placeholder="结束时间" class="filter-bar__range" />
      <el-button type="primary" :icon="Search" @click="fetchData">
        查询
      </el-button>
      <el-button :icon="RefreshLeft" title="把图表顺序恢复为默认排列" @click="handleResetOrder">
        恢复默认顺序
      </el-button>
    </div>
    <p class="range-hint">
      时间范围最长 7 天
    </p>

    <div v-if="stat" class="panels-row app-instance-overview-panels">
      <div class="panel-col app-instance-overview-left">
        <div class="page-header">
          <h4>
            节点信息-所属集群 <el-link type="primary" :underline="false" @click="emit('openApp', stat.appId)">
              [{{ stat.appName }}]
            </el-link>
          </h4>
        </div>
        <table class="app-instance-summary-table app-instance-summary-table--stacked">
          <tbody>
            <tr>
              <td>内存使用率</td><td>
                <div class="instance-mem-progress">
                  <div class="instance-mem-progress__bar" :class="memBarClass(stat.memUsePercent)" :style="{ width: `${Math.min(stat.memUsePercent, 100)}%` }">
                    <label>{{ stat.memUsedGb.toFixed(2) }}G Used/{{ stat.memTotalGb.toFixed(2) }}G Total</label>
                  </div>
                </div>
              </td>
            </tr>
            <tr><td>当前对象数</td><td>{{ stat.currItems.toLocaleString() }}</td></tr>
            <tr><td>节点地址</td><td>{{ stat.hostPort }}</td></tr>
            <tr>
              <td>操作系统架构</td>
              <td>
                <span v-if="stat.osArch">
                  {{ stat.osArch }}
                  <el-tooltip v-if="stat.osInfo" placement="top" :content="`INFO server os: ${stat.osInfo}`">
                    <el-icon class="os-arch-hint"><InfoFilled /></el-icon>
                  </el-tooltip>
                </span>
                <span v-else>-</span>
              </td>
            </tr>
          </tbody>
        </table>
        <table class="app-instance-summary-table app-instance-summary-table--stacked app-instance-runtime-table">
          <tbody>
            <tr><td>命中率</td><td>{{ stat.hitPercent || "无操作数据" }}</td><td>节点角色</td><td>{{ stat.roleDesc || "-" }}</td></tr>
            <tr><td>节点类型</td><td>{{ stat.typeDesc }}</td><td>当前连接数</td><td>{{ stat.currConnections }}</td></tr>
            <tr><td>运行状态</td><td>{{ stat.statusDesc }}</td><td>运行时长</td><td>{{ formatUptime(stat.uptimeSeconds) }}</td></tr>
          </tbody>
        </table>
        <h4 class="app-stat-section-title">
          各命令峰值信息
        </h4>
        <table class="app-stat-info-table">
          <tbody>
            <tr>
              <td>命令</td><td>峰值QPM</td><td colspan="2">
                峰值产生时间
              </td>
            </tr>
            <tr v-for="row in stat.top5Climax" :key="row.commandName">
              <td>{{ row.commandName }}</td><td>{{ row.commandCount.toLocaleString() }}</td><td colspan="2">
                {{ row.createTime || "-" }}
              </td>
            </tr>
            <tr v-if="!stat.top5Climax?.length">
              <td colspan="4" class="instance-command-empty">
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
      storage-key="instance-stat"
      :slots="chartSlots"
      :resolve="resolveSeries"
      :loading="chartsLoading"
    />
  </div>
</template>

<style scoped>
.app-instance-overview-panels {
  align-items: start;
}
.app-instance-overview-left {
  min-width: 0;
}
.app-instance-summary-table--stacked {
  table-layout: fixed;
}
.app-instance-summary-table--stacked td:first-child {
  width: 108px !important;
}
.app-instance-runtime-table {
  margin: 12px 0 20px;
}
.app-instance-runtime-table td:nth-child(3) {
  width: 108px !important;
}
.instance-command-empty {
  text-align: center;
  color: var(--rp-text-muted);
}
.os-arch-hint {
  margin-left: 4px;
  color: var(--rp-text-muted);
  vertical-align: -2px;
  cursor: help;
}
</style>
