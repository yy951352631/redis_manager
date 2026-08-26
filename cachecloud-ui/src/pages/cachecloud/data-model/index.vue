<script lang="ts" setup>
import type { ECharts } from "echarts"
import type { DataModelOptions, DataModelResult } from "@/api/cachecloud"
import { useAutoQuery } from "@@/composables/useAutoQuery"
import { Refresh } from "@element-plus/icons-vue"
import { getDataModelOptionsApi, getDataModelTopicApi } from "@/api/cachecloud"
import { initGrafanaChart } from "@/common/utils/chart-theme"
import { formatMetricValue } from "@/common/utils/metric-format"
import TopicShell from "./components/TopicShell.vue"
import "@/common/assets/styles/app-tab.scss"

const WINDOW_OPTIONS = [
  { label: "最近 24 小时", value: 24 },
  { label: "最近 3 天", value: 72 },
  { label: "最近 7 天", value: 168 },
  { label: "最近 30 天", value: 720 }
]

const ROLE_OPTIONS = [
  { label: "全部角色", value: "" },
  { label: "仅 master", value: 1 },
  { label: "仅 slave", value: 2 }
]

/** 主题定义：key 同时是后端路径 */
const TOPICS = [
  { key: "command-latency", label: "命令耗时基线", question: "同一条命令在不同架构、不同 Redis 大版本上的平均耗时差多少？" },
  { key: "key-size-qps", label: "key 大小与 QPS", question: "key 平均越大，实例的吞吐是否越低？" },
  { key: "memory-persistence", label: "内存与持久化开销", question: "实例内存涨到多大，fork 会阻塞多久？扩容前该看的图。" },
  { key: "cluster-comparison", label: "集群横向对比", question: "几套集群在核心指标上谁好谁差？" }
] as const

const SCATTER_COLORS = ["#3274d9", "#73bf69", "#ff9830", "#e02f44", "#8f3bb8"]

const activeTopic = ref<string>(TOPICS[0].key)
const loading = ref(false)
const options = ref<DataModelOptions>({ apps: [], archs: [], majorVersions: [], commands: [] })
const results = reactive<Record<string, DataModelResult | null>>({})

const windowHours = ref(168)
const appIds = ref<number[]>([])
const archs = ref<string[]>([])
const majorVersions = ref<string[]>([])
const role = ref<number | "">("")
const excludeTestApp = ref(true)
const commands = ref<string[]>([])

const chartRefs = reactive<Record<string, HTMLElement | null>>({})
const chartInstances = new Map<string, ECharts>()

function setChartRef(key: string, el: unknown) {
  chartRefs[key] = el instanceof HTMLElement ? el : null
}

function queryParams() {
  return {
    windowHours: windowHours.value,
    appIds: appIds.value.join(","),
    archs: archs.value.join(","),
    majorVersions: majorVersions.value.join(","),
    role: role.value === "" ? undefined : role.value,
    excludeTestApp: excludeTestApp.value,
    commands: commands.value.join(",")
  }
}

async function fetchTopic(topic: string) {
  loading.value = true
  try {
    const { data } = await getDataModelTopicApi(topic, queryParams())
    results[topic] = data
    await nextTick()
    renderTopic(topic)
  } finally {
    loading.value = false
  }
}

/** 切筛选条件时清掉所有已缓存的主题结果，避免不同条件的图混在一起 */
async function refreshAll() {
  Object.keys(results).forEach((key) => {
    results[key] = null
  })
  await fetchTopic(activeTopic.value)
}

function handleTabChange(name: string | number) {
  const topic = String(name)
  activeTopic.value = topic
  if (!results[topic]) void fetchTopic(topic)
  else void nextTick(() => renderTopic(topic))
}

// #region 图表渲染
function disposeChart(key: string) {
  chartInstances.get(key)?.dispose()
  chartInstances.delete(key)
}

async function renderTopic(topic: string) {
  const result = results[topic]
  const el = chartRefs[topic]
  if (!result || !el || result.emptyReason) return

  const echarts = await import("echarts")
  disposeChart(topic)
  const chart = initGrafanaChart(echarts, el)

  if (topic === "command-latency") {
    chart.setOption(barOption(result))
  } else if (topic === "cluster-comparison") {
    chart.setOption(comparisonOption(result))
  } else {
    chart.setOption(scatterOption(result))
  }
  chartInstances.set(topic, chart)
}

function barOption(result: DataModelResult) {
  return {
    animation: false,
    tooltip: {
      trigger: "axis",
      axisPointer: { type: "shadow" },
      valueFormatter: (value: number) => `${formatMetricValue(value)} μs`
    },
    legend: { bottom: 0, type: "scroll" },
    grid: { left: 70, right: 30, top: 30, bottom: 60 },
    xAxis: { type: "category", data: result.categories },
    yAxis: {
      type: "value",
      name: "平均耗时 (μs)",
      axisLabel: { formatter: (value: number) => formatMetricValue(value) }
    },
    series: result.groups.map((group, index) => ({
      name: group,
      type: "bar",
      data: result.series[index],
      barMaxWidth: 36
    }))
  }
}

function scatterOption(result: DataModelResult) {
  const series: Record<string, unknown>[] = result.scatterSeries.map((s, index) => ({
    name: s.name,
    type: "scatter",
    symbolSize: 12,
    itemStyle: { color: SCATTER_COLORS[index % SCATTER_COLORS.length] },
    data: s.points.map(p => [p.x, p.y, p.label])
  }))
  // 拟合线单独作为一条 line 系列叠上去
  if (result.correlation?.line?.length === 2) {
    series.push({
      name: "趋势线",
      type: "line",
      showSymbol: false,
      lineStyle: { type: "dashed", width: 2, color: "#e02f44" },
      data: result.correlation.line.map(p => [p.x, p.y])
    })
  }
  return {
    animation: false,
    tooltip: {
      trigger: "item",
      formatter: (params: { seriesName: string, value: (number | string)[] }) => {
        if (params.seriesName === "趋势线") return "趋势线"
        const [x, y, label] = params.value
        return `${label ?? ""}<br/>${result.xAxisName}: ${formatMetricValue(x)}<br/>${result.yAxisName}: ${formatMetricValue(y)}`
      }
    },
    legend: { bottom: 0, type: "scroll" },
    grid: { left: 80, right: 40, top: 30, bottom: 60 },
    xAxis: {
      type: "value",
      name: result.xAxisName,
      nameLocation: "middle",
      nameGap: 30,
      axisLabel: { formatter: (value: number) => formatMetricValue(value) }
    },
    yAxis: {
      type: "value",
      name: result.yAxisName,
      axisLabel: { formatter: (value: number) => formatMetricValue(value) }
    },
    series
  }
}

function comparisonOption(result: DataModelResult) {
  const names = result.comparisonRows.map(r => r.appName)
  const metrics = [
    { name: "QPS", key: "qps" },
    { name: "命中率 (%)", key: "hitPercent" },
    { name: "内存使用率 (%)", key: "memUsePercent" },
    { name: "碎片率", key: "memFragRatio" }
  ] as const
  return {
    animation: false,
    tooltip: { trigger: "axis", axisPointer: { type: "shadow" } },
    legend: { bottom: 0, type: "scroll" },
    grid: { left: 70, right: 30, top: 30, bottom: 60 },
    xAxis: { type: "category", data: names },
    yAxis: { type: "value", axisLabel: { formatter: (value: number) => formatMetricValue(value) } },
    series: metrics.map(m => ({
      name: m.name,
      type: "bar",
      barMaxWidth: 28,
      data: result.comparisonRows.map(row => row[m.key] ?? null)
    }))
  }
}
// #endregion

function correlationTagType(strength?: string) {
  if (strength === "STRONG") return "danger"
  if (strength === "MODERATE") return "warning"
  return "info"
}

function handleResize() {
  chartInstances.forEach(chart => chart.resize())
}

// 筛选条件一变就重查，与平台其它查询页一致
useAutoQuery(
  [windowHours, appIds, archs, majorVersions, role, excludeTestApp, commands],
  () => refreshAll()
)

onMounted(async () => {
  const { data } = await getDataModelOptionsApi()
  if (data) options.value = data
  await fetchTopic(activeTopic.value)
  window.addEventListener("resize", handleResize)
})

onBeforeUnmount(() => {
  window.removeEventListener("resize", handleResize)
  chartInstances.forEach(chart => chart.dispose())
  chartInstances.clear()
})
</script>

<template>
  <div class="dm-page">
    <el-card shadow="never" class="dm-page__filter">
      <div class="dm-page__filter-row">
        <span class="dm-page__label">时间窗口</span>
        <el-select v-model="windowHours" style="width: 140px">
          <el-option v-for="o in WINDOW_OPTIONS" :key="o.value" :label="o.label" :value="o.value" />
        </el-select>

        <span class="dm-page__label">集群</span>
        <el-select v-model="appIds" multiple collapse-tags placeholder="全部" style="width: 200px">
          <el-option v-for="a in options.apps" :key="a.appId" :label="a.appName" :value="a.appId" />
        </el-select>

        <span class="dm-page__label">架构</span>
        <el-select v-model="archs" multiple collapse-tags placeholder="全部" style="width: 140px">
          <el-option v-for="a in options.archs" :key="a" :label="a" :value="a" />
        </el-select>

        <span class="dm-page__label">Redis 版本</span>
        <el-select v-model="majorVersions" multiple collapse-tags placeholder="全部" style="width: 140px">
          <el-option v-for="v in options.majorVersions" :key="v" :label="v" :value="v" />
        </el-select>

        <el-select v-model="role" style="width: 130px">
          <el-option v-for="o in ROLE_OPTIONS" :key="String(o.value)" :label="o.label" :value="o.value" />
        </el-select>

        <el-checkbox v-model="excludeTestApp">
          排除测试集群
        </el-checkbox>

        <el-button :icon="Refresh" :loading="loading" @click="refreshAll">
          刷新
        </el-button>
      </div>
      <div class="dm-page__hint">
        筛选条件对所有主题统一生效——这一页要回答的是「同一批实例在不同维度下分别表现如何」
      </div>
    </el-card>

    <el-card shadow="never">
      <el-tabs v-model="activeTopic" @tab-change="handleTabChange">
        <el-tab-pane v-for="topic in TOPICS" :key="topic.key" :label="topic.label" :name="topic.key" lazy>
          <TopicShell :result="results[topic.key] ?? null" :loading="loading" :question="topic.question">
            <div :ref="el => setChartRef(topic.key, el)" class="dm-chart" />

            <!-- 散点主题的量化结论 -->
            <template v-if="topic.key === 'key-size-qps' || topic.key === 'memory-persistence'">
              <div v-if="results[topic.key]?.correlation" class="dm-correlation">
                <el-tag :type="correlationTagType(results[topic.key]?.correlation?.strength)" size="small">
                  {{ results[topic.key]?.correlation?.strength === "INSUFFICIENT" ? "样本不足" : "相关性" }}
                </el-tag>
                <span class="dm-correlation__text">{{ results[topic.key]?.correlation?.conclusion }}</span>
              </div>
              <div class="dm-caveat">
                相关不等于因果。图能告诉你两个指标有没有关联，不能告诉你谁导致谁——
                key 大的实例 QPS 低，可能是大 key 拖慢了处理，也可能只是那类业务本身请求量就少。
              </div>
            </template>

            <!-- 命令耗时主题：现行阈值参考 -->
            <div v-if="topic.key === 'command-latency' && results[topic.key]?.referenceLines?.length" class="dm-caveat">
              风险评估现行阈值：{{ results[topic.key]?.referenceLines?.map(l => l.label).join("；") }}。
              判定用的是「与同类基线的倍数」，不是绝对耗时；要调整请到风险评估的「评估策略」。
            </div>

            <!-- 横向对比明细表 -->
            <el-table
              v-if="topic.key === 'cluster-comparison' && results[topic.key]?.comparisonRows?.length"
              :data="results[topic.key]?.comparisonRows"
              size="small"
              border
              stripe
              class="dm-table"
            >
              <el-table-column prop="appName" label="集群" min-width="180" show-overflow-tooltip />
              <el-table-column prop="typeDesc" label="类型" width="100" />
              <el-table-column prop="osArch" label="架构" width="80" />
              <el-table-column prop="majorVersion" label="版本" width="80" />
              <el-table-column prop="instanceCount" label="实例数" width="80" align="right" />
              <el-table-column label="QPS" width="100" align="right">
                <template #default="{ row }">
                  {{ formatMetricValue(row.qps) }}
                </template>
              </el-table-column>
              <el-table-column label="命中率" width="100" align="right">
                <template #default="{ row }">
                  {{ formatMetricValue(row.hitPercent) }}%
                </template>
              </el-table-column>
              <el-table-column label="内存使用率" width="110" align="right">
                <template #default="{ row }">
                  {{ formatMetricValue(row.memUsePercent) }}%
                </template>
              </el-table-column>
              <el-table-column label="碎片率" width="90" align="right">
                <template #default="{ row }">
                  {{ formatMetricValue(row.memFragRatio) }}
                </template>
              </el-table-column>
              <el-table-column label="平均 key" width="110" align="right">
                <template #default="{ row }">
                  {{ row.avgKeyBytes == null ? "—" : `${formatMetricValue(row.avgKeyBytes)} B` }}
                </template>
              </el-table-column>
              <el-table-column label="连接数" width="90" align="right">
                <template #default="{ row }">
                  {{ formatMetricValue(row.connectedClients) }}
                </template>
              </el-table-column>
            </el-table>
          </TopicShell>
        </el-tab-pane>
      </el-tabs>
    </el-card>
  </div>
</template>

<style scoped>
.dm-page__filter {
  margin-bottom: 12px;
}

.dm-page__filter-row {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
}

.dm-page__label {
  font-size: 13px;
  color: var(--el-text-color-regular);
}

.dm-page__hint {
  margin-top: 8px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.dm-chart {
  height: 420px;
}

.dm-correlation {
  display: flex;
  gap: 8px;
  align-items: center;
  margin-top: 10px;
  font-size: 13px;
}

.dm-correlation__text {
  color: var(--el-text-color-primary);
}

.dm-caveat {
  margin-top: 8px;
  font-size: 12px;
  line-height: 1.7;
  color: var(--el-text-color-secondary);
}

.dm-table {
  margin-top: 16px;
}
</style>
