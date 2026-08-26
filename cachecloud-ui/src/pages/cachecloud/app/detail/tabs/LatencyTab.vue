<script lang="ts" setup>
import type { ECharts } from "echarts"
import type { AppLatency, LatencyChartSeries, LatencyEventDetail } from "@/api/cachecloud"
import { useAutoQuery } from "@@/composables/useAutoQuery"
import { Delete, Search } from "@element-plus/icons-vue"
import { cleanAppSlowLogsApi, countCleanableSlowLogsApi, getAppLatencyApi } from "@/api/cachecloud"
import { initGrafanaChart } from "@/common/utils/chart-theme"
import "@/common/assets/styles/app-tab.scss"

const props = defineProps<{ appId: number }>()

/** 与 manage-highcharts / myhighchart.js MANAGE_HC_COLORS 一致 */
const HC_COLORS = ["#4d8dff", "#22c55e", "#f59e0b", "#ef4444", "#8b5cf6", "#06b6d4", "#ec4899", "#64748b", "#14b8a6", "#f97316"]
const router = useRouter()
const loading = ref(false)
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
  const [start, end] = shiftRange(60 * 60 * 1000)
  return [fmt(start), fmt(end)]
}
const dateRange = ref<[string, string]>(defaultRange())

// 选完时间区间即自动查询
useAutoQuery(dateRange, () => fetchData())
/** 默认只看超过 20ms 的慢查询，阈值为 0 时几乎所有命令都会被列出来，噪音过大 */
const minCostMillis = ref(20)
const latency = ref<AppLatency | null>(null)
const chartRef = useTemplateRef<HTMLDivElement>("chartRef")
const drawerVisible = ref(false)
const selectedEvents = ref<LatencyEventDetail[]>([])
let chart: ECharts | null = null

const hasLatencyEvents = computed(() => (latency.value?.latencyEvents?.length ?? 0) > 0)

const combinedRows = computed(() => {
  if (!latency.value) return []
  const latMap = new Map(latency.value.instanceLatencies.map(i => [i.hostPort, i.count]))
  const slowMap = new Map(latency.value.instanceSlowLogCounts.map(i => [i.hostPort, i.count]))
  return latency.value.instances.map((hostPort, index) => ({
    index: index + 1,
    hostPort,
    instanceId: latency.value?.instanceIdByHostPort?.[hostPort],
    latencyCount: latMap.get(hostPort) ?? 0,
    slowLogCount: slowMap.get(hostPort) ?? 0
  }))
})

/** 延迟事件图表默认展开；收起再展开时 ECharts 需要重新测量容器 */
const activeChartPanels = ref<string[]>(["latencyChart"])

watch(activeChartPanels, async (panels) => {
  if (!panels.includes("latencyChart")) return
  // 面板收起期间容器高度为 0，ECharts 记住的是那个尺寸，展开后必须重新测量
  await nextTick()
  chart?.resize()
})

/** 慢查询清理抽屉 */
const cleanVisible = ref(false)
const cleanKeepDays = ref(7)
const cleanSubmitting = ref(false)
const cleanPreview = ref<number | null>(null)
const cleanPreviewLoading = ref(false)
const KEEP_DAYS_PRESETS = [
  { label: "最近 3 天", value: 3 },
  { label: "最近 7 天", value: 7 },
  { label: "最近 15 天", value: 15 },
  { label: "最近 30 天", value: 30 },
  { label: "全部清空", value: 0 }
]

function openCleanDrawer() {
  cleanVisible.value = true
  void refreshCleanPreview()
}

/** 预估会删掉多少条，避免用户在不知情的情况下清掉大量历史 */
async function refreshCleanPreview() {
  const days = Number(cleanKeepDays.value)
  if (!Number.isFinite(days) || days < 0) {
    cleanPreview.value = null
    return
  }
  cleanPreviewLoading.value = true
  try {
    const { data } = await countCleanableSlowLogsApi(props.appId, days)
    cleanPreview.value = data?.count ?? 0
  } catch {
    cleanPreview.value = null
  } finally {
    cleanPreviewLoading.value = false
  }
}

watch(cleanKeepDays, () => {
  if (cleanVisible.value) void refreshCleanPreview()
})

async function submitClean() {
  const days = Number(cleanKeepDays.value)
  if (!Number.isFinite(days) || days < 0) {
    ElMessage.warning("请填写有效的保留天数")
    return
  }
  const tip = days === 0
    ? "将清空该集群平台侧的全部慢查询记录，确认继续？"
    : `将删除 ${days} 天前的平台侧慢查询记录，确认继续？`
  try {
    await ElMessageBox.confirm(tip, "清理慢查询记录", { type: "warning" })
  } catch {
    return
  }
  cleanSubmitting.value = true
  try {
    const { data } = await cleanAppSlowLogsApi(props.appId, days)
    ElMessage.success(`已清理 ${data?.deleted ?? 0} 条慢查询记录`)
    cleanVisible.value = false
    await fetchData()
  } catch (e: unknown) {
    ElMessage.error(e instanceof Error ? e.message : "清理失败")
  } finally {
    cleanSubmitting.value = false
  }
}

/** 展开中的实例面板；只放第一个实例，其余默认收起 */
const activeSlowLogPanels = ref<string[]>([])

const groupedSlowLogs = computed(() => {
  const map = latency.value?.groupedSlowLogs
  if (map && Object.keys(map).length) return Object.entries(map)
  const fallback: Record<string, NonNullable<AppLatency["slowLogs"]>> = {}
  for (const log of latency.value?.slowLogs ?? []) {
    if (!fallback[log.hostPort]) fallback[log.hostPort] = []
    fallback[log.hostPort].push(log)
  }
  return Object.entries(fallback)
})

// 每次查询回来重置展开状态：默认只展开第一个实例，
// 否则切换时间区间后展开项会停留在上一批实例的 hostPort 上
watch(groupedSlowLogs, (groups) => {
  activeSlowLogPanels.value = groups.length ? [groups[0][0]] : []
}, { immediate: true })

function openInstance(instanceId?: number) {
  if (!instanceId) return
  router.push({ path: `/external/redis/detail/${instanceId}`, query: { appId: String(props.appId) } })
}

function buildEventSeries(chartSeries: LatencyChartSeries[]) {
  const hasAnyValue = chartSeries.some(s => s.points.some(p => p.count > 0))
  if (!chartSeries.length || !hasAnyValue) return []

  return chartSeries.map((series) => {
    const data: [number, number][] = series.points.map(point => [point.timestamp, point.count])
    return {
      name: series.event,
      type: "line" as const,
      smooth: false,
      showSymbol: true,
      symbolSize: 7,
      lineStyle: { width: 2 },
      data
    }
  })
}

function formatTooltipTime(ts: number) {
  const d = new Date(ts)
  const p = (n: number) => String(n).padStart(2, "0")
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
}

function formatCount(value: number) {
  const abs = Math.abs(value)
  const decimals = abs >= 100 ? 0 : abs >= 10 ? 1 : 2
  return value.toFixed(decimals)
}

async function fetchData() {
  loading.value = true
  try {
    const { data } = await getAppLatencyApi(props.appId, {
      startDate: dateRange.value[0],
      endDate: dateRange.value[1],
      minCostMillis: minCostMillis.value
    })
    latency.value = data
    await nextTick()
    await renderChart(data)
  } finally {
    loading.value = false
  }
}

async function renderChart(data: AppLatency | null) {
  const echarts = await import("echarts")
  chart?.dispose()
  chart = null
  if (!chartRef.value || !data?.latencyEvents?.length) return
  chart = initGrafanaChart(echarts, chartRef.value)

  const series = buildEventSeries(data?.chartSeries ?? [])

  chart.setOption({
    color: HC_COLORS,
    backgroundColor: "transparent",
    tooltip: {
      trigger: "axis",
      axisPointer: { type: "cross" },
      formatter(params: unknown) {
        const items = Array.isArray(params) ? params : [params]
        if (!items.length) return ""
        const ts = items[0].value as [number, number]
        let html = `<b>${formatTooltipTime(ts[0])}</b><br/>`
        const sorted = [...items].sort((a, b) => {
          const av = (a.value as [number, number])[1] ?? 0
          const bv = (b.value as [number, number])[1] ?? 0
          return bv - av
        })
        for (const item of sorted) {
          const y = (item.value as [number, number])[1] ?? 0
          html += `<span style="color:${item.color}">●</span> ${item.seriesName}: <b>${formatCount(y)}</b> 个数<br/>`
        }
        return html
      }
    },
    legend: {
      bottom: 0,
      textStyle: { color: "#6b7a90", fontSize: 14 }
    },
    grid: { left: 52, right: 24, top: 16, bottom: 48 },
    xAxis: {
      type: "time",
      min: new Date(dateRange.value[0]).getTime(),
      max: new Date(dateRange.value[1]).getTime(),
      splitNumber: 10,
      axisLine: { lineStyle: { color: "#e8edf3" } },
      axisTick: { lineStyle: { color: "#e8edf3" } },
      axisLabel: { color: "#6b7a90", fontSize: 13, formatter: (value: number) => formatTooltipTime(value).slice(5) }
    },
    yAxis: {
      type: "value",
      name: "个数",
      min: 0,
      nameTextStyle: { color: "#6b7a90", fontSize: 14 },
      axisLine: { lineStyle: { color: "#e8edf3" } },
      axisTick: { lineStyle: { color: "#e8edf3" } },
      axisLabel: { color: "#6b7a90", fontSize: 13 },
      splitLine: { lineStyle: { color: "#f0f4f8" } }
    },
    series
  })
  chart.on("click", (params) => {
    const value = params.value as [number, number] | undefined
    const timestamp = value?.[0]
    if (!timestamp) return
    selectedEvents.value = (data.latencyEvents ?? []).filter(item =>
      item.event === params.seriesName && Math.abs(new Date(item.executeTime.replace(" ", "T")).getTime() - timestamp) < 1000)
    drawerVisible.value = true
  })
}

function handleResize() {
  chart?.resize()
}

watch(() => props.appId, () => {
  dateRange.value = defaultRange()
  void fetchData()
}, { immediate: true })

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
  <div v-loading="loading" class="app-latency-page">
    <div class="app-latency-toolbar">
      <div class="app-latency-toolbar__search">
        <label class="app-latency-toolbar__label">查询时间</label>
        <el-date-picker
          v-model="dateRange"
          type="datetimerange"
          format="YYYY-MM-DD HH:mm:ss"
          value-format="YYYY-MM-DD HH:mm:ss"
          :shortcuts="rangeShortcuts"
          range-separator="至"
          start-placeholder="开始时间"
          end-placeholder="结束时间"
          class="app-latency-range"
        />
        <label class="app-latency-toolbar__label">执行耗时超过</label>
        <el-input-number v-model="minCostMillis" :min="0" :max="86400000" :step="10" controls-position="right" class="app-latency-threshold" />
        <span class="app-latency-toolbar__label">毫秒</span>
        <el-button type="primary" class="app-latency-query-btn" :icon="Search" @click="fetchData">
          查询
        </el-button>
        <el-button :icon="Delete" @click="openCleanDrawer">
          清理
        </el-button>
      </div>
    </div>

    <div v-if="latency?.latencyDataEmpty" class="app-latency-data-hint">
      当前时间段暂无延迟事件与符合条件的慢查询记录。延迟数据由后台采集任务通过 Redis
      <code>LATENCY</code>
      命令拉取，仅在实例实际发生延迟事件时才会入库；慢查询来自 Redis slowlog 采集。可切换日期查看，或确认实例连接正常且采集任务已运行。
    </div>

    <div class="app-latency-section">
      <div class="app-latency-section__title">
        Redis 实例延迟 &amp; 慢查询统计
      </div>
      <div class="app-latency-summary-table-wrap">
        <table class="app-topology-table app-latency-table app-latency-summary-table">
          <thead>
            <tr>
              <th style="width:72px">
                序号
              </th>
              <th>实例信息</th>
              <th style="width:140px">
                延迟事件个数
              </th>
              <th style="width:140px">
                慢查询个数
              </th>
            </tr>
          </thead>
          <tbody>
            <tr v-if="!combinedRows.length">
              <td colspan="4" class="app-latency-table__empty">
                暂无在线 Redis 实例
              </td>
            </tr>
            <tr v-for="row in combinedRows" :key="row.hostPort">
              <td>{{ row.index }}</td>
              <td>
                <el-link
                  v-if="row.instanceId"
                  type="primary"
                  :underline="false"
                  @click="openInstance(row.instanceId)"
                >
                  {{ row.hostPort }}
                </el-link>
                <span v-else>{{ row.hostPort }}</span>
              </td>
              <td>{{ row.latencyCount }}</td>
              <td>{{ row.slowLogCount }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <el-collapse
      v-if="hasLatencyEvents"
      v-model="activeChartPanels"
      class="app-latency-slowlog-collapse app-latency-chart-collapse"
    >
      <el-collapse-item name="latencyChart">
        <template #title>
          <span class="app-latency-slowlog-block__title app-latency-chart-collapse__title">延迟事件统计</span>
          <span class="app-latency-badge">{{ latency?.latencyEvents?.length ?? 0 }} 条</span>
        </template>
        <div class="app-latency-chart-wrap">
          <div ref="chartRef" class="app-latency-chart" />
        </div>
      </el-collapse-item>
    </el-collapse>

    <div class="app-latency-section">
      <div class="app-latency-section__title">
        各实例慢查询情况
        <span class="app-latency-badge">共 {{ latency?.slowLogs?.length ?? 0 }} 次</span>
      </div>

      <div v-if="!groupedSlowLogs.length" class="app-latency-slowlog-empty">
        当前时间段暂无符合条件的慢查询明细
      </div>

      <el-collapse v-model="activeSlowLogPanels" class="app-latency-slowlog-collapse">
        <el-collapse-item v-for="[hostPort, logs] in groupedSlowLogs" :key="hostPort" :name="hostPort">
          <template #title>
            <span class="app-latency-slowlog-block__title">{{ hostPort }}</span>
            <span class="app-latency-badge">{{ logs.length }} 次</span>
          </template>
          <table class="app-topology-table app-latency-table">
            <thead>
              <tr>
                <th style="width:100px">
                  ID
                </th>
                <th style="width:180px">
                  慢查询发生时间
                </th>
                <th style="width:160px">
                  执行耗时(微秒)
                </th>
                <th style="width:160px">
                  执行客户端IP
                </th>
                <th>执行命令</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="log in logs" :key="log.id">
                <td>{{ log.slowLogId }}</td>
                <td>{{ log.executeTime }}</td>
                <td>{{ log.costTime.toLocaleString() }}</td>
                <td>{{ log.clientIp || "-" }}</td>
                <td class="app-latency-command" :title="log.command">
                  {{ log.command }}
                </td>
              </tr>
            </tbody>
          </table>
        </el-collapse-item>
      </el-collapse>
    </div>

    <el-drawer v-model="cleanVisible" title="清理慢查询记录" size="420px">
      <el-form label-position="top" class="app-latency-clean-form">
        <el-form-item label="保留最近">
          <el-select v-model="cleanKeepDays" class="app-latency-clean-select">
            <el-option v-for="p in KEEP_DAYS_PRESETS" :key="p.value" :label="p.label" :value="p.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="或自定义天数">
          <el-input-number
            v-model="cleanKeepDays"
            :min="0"
            :max="3650"
            :step="1"
            controls-position="right"
            class="app-latency-clean-days"
          />
          <span class="app-latency-clean-unit">天</span>
        </el-form-item>

        <div class="app-latency-clean-preview">
          <template v-if="cleanPreviewLoading">
            正在统计可清理条数…
          </template>
          <template v-else-if="cleanPreview === null">
            无法预估可清理条数
          </template>
          <template v-else>
            本次将清理 <strong>{{ cleanPreview.toLocaleString() }}</strong> 条记录
          </template>
        </div>

        <el-alert
          type="info"
          :closable="false"
          show-icon
          class="app-latency-clean-tip"
        >
          <template #title>
            <span class="app-latency-clean-tip__text">
              仅清理平台存储的慢查询记录，不影响 Redis 本身的慢日志记录数据。
            </span>
          </template>
        </el-alert>
      </el-form>

      <template #footer>
        <el-button @click="cleanVisible = false">
          取消
        </el-button>
        <el-button type="danger" :loading="cleanSubmitting" @click="submitClean">
          确认清理
        </el-button>
      </template>
    </el-drawer>

    <el-drawer v-model="drawerVisible" title="延迟事件详情" size="520px">
      <el-descriptions v-for="event in selectedEvents" :key="event.id" :column="1" border class="app-latency-event-detail">
        <el-descriptions-item label="实例">
          {{ event.hostPort }}
        </el-descriptions-item>
        <el-descriptions-item label="事件类型">
          {{ event.event }}
        </el-descriptions-item>
        <el-descriptions-item label="发生时间">
          {{ event.executeTime }}
        </el-descriptions-item>
        <el-descriptions-item label="延迟耗时">
          {{ event.executionCost.toLocaleString() }} ms
        </el-descriptions-item>
      </el-descriptions>
      <el-empty v-if="!selectedEvents.length" description="未找到该事件明细" />
    </el-drawer>
  </div>
</template>
