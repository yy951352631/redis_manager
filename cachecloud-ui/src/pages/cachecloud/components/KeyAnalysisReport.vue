<script lang="ts" setup>
import type { ECharts } from "echarts"
import type { KeyAnalysisResult, ParamCountItem } from "@/api/cachecloud"
import { ArrowLeft, Download } from "@element-plus/icons-vue"
import { initGrafanaChart } from "@/common/utils/chart-theme"
import "@/common/assets/styles/app-tab.scss"

/**
 * 键值分析报告的展示组件。
 *
 * 在线键值分析与离线 RDB 分析共用它——两边产出的结果结构完全一致，
 * 差别只在标题与来源描述，因此这里只接收结果数据，不关心它是怎么来的。
 */
const props = withDefaults(defineProps<{
  result: KeyAnalysisResult | null
  loading?: boolean
  /** 标题下方的来源说明，例如「记录 #12 · 集群A」或「dump.rdb」 */
  subtitle?: string
  /** 导出 PDF 的文件名（不含扩展名） */
  exportName?: string
}>(), {
  loading: false,
  subtitle: "",
  exportName: "Redis键值分析"
})

const emit = defineEmits<{ back: [] }>()

const HC_COLORS = ["#5470c6", "#91cc75", "#fac858", "#ee6666", "#73c0de", "#3ba272", "#fc8452", "#9a60b4"]
const TYPE_COLORS = ["#ff9d3f", "#4d8dff", "#22c55e", "#ef4444", "#14b8a6", "#8b5cf6", "#facc15", "#64748b"]

const reportRef = ref<HTMLElement | null>(null)
const exportingPdf = ref(false)
const chartRefs = reactive<Record<string, HTMLElement | null>>({})
const chartInstances: ECharts[] = []

const chartDefs = [
  { key: "keyTtl", title: "TTL 分布" },
  { key: "idleKey", title: "空闲键分布" },
  { key: "valueSize", title: "键值大小分布" }
] as const

function chartData(key: string): ParamCountItem[] {
  const data = props.result
  if (!data) return []
  switch (key) {
    case "typeMemory": return data.keyTypeMemoryDistri
    case "typeCount": return data.keyTypeDistri
    case "keyTtl": return data.keyTtlDistri
    case "idleKey": return data.idleKeyDistri
    case "valueSize": return data.keyValueSizeDistri
    default: return []
  }
}

/**
 * 键值大小极值。区间分布图只说明「落在哪一档」，读不出真实的最大/最小值，
 * 后端在扫描时单独累计了原始量，这里只做展示换算。
 * sampleCount 为 0 的情况来自两处：本次改动之前的历史分析记录，
 * 以及从 assist Redis 反推的快照（那里只留下了区间计数），此时不画图。
 */
const valueSizeExtremes = computed(() => {
  const r = props.result
  const samples = r?.valueSizeSampleCount || 0
  if (!r || samples <= 0) return null
  return {
    max: r.valueSizeMaxBytes || 0,
    avg: Math.round((r.valueSizeSumBytes || 0) / samples),
    min: r.valueSizeMinBytes || 0,
    samples
  }
})

/**
 * 前缀统计按 key 数量倒序。
 *
 * 后端两条链路给出的顺序并不一致：在线分析是「按类型分组、组内按数量排」，
 * 离线解析是「全局按占用排」，都不是这张表想回答的问题（哪个前缀的 key 最多）。
 * 排序放在展示层做，两条链路的结果才是同一个口径。
 * 数量相同时用占用兜底，否则同为 N 个 key 的前缀顺序会随后端返回顺序漂移。
 */
const prefixRows = computed(() => {
  const rows = props.result?.keyPrefixTop
  if (!rows?.length) return []
  return [...rows].sort((a, b) => (b.count - a.count) || (b.bytes - a.bytes))
})

const conclusionTag = computed(() => {
  const r = props.result
  if (!r) return { text: "—", cls: "" }
  if (r.emptyDb) return { text: "空库扫描完成", cls: "app-key-result-summary__tag--empty" }
  if (r.statsMissing) return { text: "统计未写入", cls: "app-key-result-summary__tag--missing" }
  if (r.hasDistributionData && r.hasAnalysisRisk) return { text: "部分统计有风险", cls: "app-key-result-summary__tag--risk" }
  if (r.hasDistributionData) return { text: "已生成统计", cls: "app-key-result-summary__tag--ok" }
  if (r.hasAnalysisRisk) return { text: "扫描完成（统计不完整）", cls: "app-key-result-summary__tag--risk" }
  return { text: "扫描完成", cls: "" }
})

function setChartRef(key: string, el: unknown) {
  chartRefs[key] = el instanceof HTMLElement ? el : null
}

function toPieData(items: ParamCountItem[], mode: "plain" | "count" | "bytes" = "plain") {
  const total = items.reduce((sum, item) => sum + item.count, 0)
  return items.map((item) => {
    const pct = total > 0 ? (item.count / total * 100).toFixed(1) : "0.0"
    const valueLabel = mode === "bytes" ? formatBytes(item.count) : item.count.toLocaleString()
    return {
      name: mode === "plain" ? `${item.name}: ${valueLabel}` : `${item.name}: ${valueLabel} (${pct}%)`,
      value: item.count
    }
  })
}

function formatBytes(value: number) {
  if (!Number.isFinite(value) || value <= 0) return "0 B"
  const units = ["B", "KB", "MB", "GB"]
  let size = value
  let unit = 0
  while (size >= 1024 && unit < units.length - 1) {
    size /= 1024
    unit++
  }
  return `${size >= 10 || unit === 0 ? size.toFixed(0) : size.toFixed(1)} ${units[unit]}`
}

function bigKeyRuleText(data = props.result) {
  const stringBytes = data?.bigKeyStringBytes || 100 * 1024
  const elements = data?.bigKeyCollectionElements || 50000
  return `String 大小 >= ${formatBytes(stringBytes)}；Hash/List/Set/ZSet 元素数 >= ${elements.toLocaleString()}`
}

function disposeCharts() {
  chartInstances.forEach(c => c.dispose())
  chartInstances.length = 0
}

async function renderCharts() {
  disposeCharts()
  await nextTick()
  if (!props.result) return
  const echarts = await import("echarts")
  const targets: Array<{ key: string, mode: "plain" | "count" | "bytes", donut?: boolean }> = [
    { key: "typeCount", mode: "count", donut: true },
    { key: "typeMemory", mode: "bytes", donut: true },
    ...chartDefs.map(d => ({ key: d.key, mode: "plain" as const }))
  ]
  for (const target of targets) {
    const el = chartRefs[target.key]
    if (!el) continue
    const data = chartData(target.key)
    const chart = initGrafanaChart(echarts, el)
    if (!data.length) {
      chart.clear()
      chartInstances.push(chart)
      continue
    }
    const total = data.reduce((sum, item) => sum + item.count, 0)
    const centerValue = target.mode === "bytes" ? formatBytes(total) : total.toLocaleString()
    const centerLabel = target.mode === "bytes" ? "总内存" : "总数"
    chart.setOption({
      color: target.donut ? TYPE_COLORS : HC_COLORS,
      title: target.donut
        ? {
            text: centerValue,
            subtext: centerLabel,
            left: "center",
            top: "37%",
            textStyle: { color: "#1e293b", fontSize: 20, fontWeight: 600 },
            subtextStyle: { color: "#64748b", fontSize: 12, lineHeight: 18 }
          }
        : undefined,
      tooltip: {
        trigger: "item",
        formatter: (params: { name: string, value: number }) =>
          target.mode === "bytes"
            ? `${params.name}<br/>内存: ${formatBytes(params.value)}`
            : `${params.name}<br/>数量: ${params.value.toLocaleString()}`
      },
      legend: { bottom: 4, left: "center", textStyle: { fontSize: 11, color: "#6b7a90" } },
      series: [{
        type: "pie",
        radius: target.donut ? ["34%", "58%"] : "55%",
        center: ["50%", "44%"],
        data: toPieData(data, target.mode),
        avoidLabelOverlap: true,
        label: { show: true, formatter: "{b}", overflow: "break" },
        labelLine: { show: true, length: 10, length2: 8 }
      }]
    })
    chartInstances.push(chart)
  }
  renderValueSizeExtremes(echarts)
}

function renderValueSizeExtremes(echarts: typeof import("echarts")) {
  const el = chartRefs.valueSizeExtremes
  if (!el) return
  const chart = initGrafanaChart(echarts, el)
  chartInstances.push(chart)
  const stat = valueSizeExtremes.value
  if (!stat) {
    chart.clear()
    return
  }
  const rows = [
    { name: "最大", value: stat.max, color: "#ef4444" },
    { name: "平均", value: stat.avg, color: "#3b82f6" },
    { name: "最小", value: stat.min, color: "#22c55e" }
  ]
  // 最大值常比最小值大几个数量级，线性轴会把「平均 / 最小」压成贴地的一条线；
  // 差距超过两个数量级时改用对数轴，让三根柱子都还能读出高度。
  const useLog = stat.min > 0 && stat.max / stat.min >= 100
  chart.setOption({
    grid: { left: 72, right: 24, top: 34, bottom: 32 },
    tooltip: {
      trigger: "axis",
      axisPointer: { type: "shadow" },
      formatter: (params: Array<{ name: string, value: number }>) => {
        const item = params[0]
        return `${item.name}键值<br/>${formatBytes(item.value)}（${item.value.toLocaleString()} 字节）`
      }
    },
    xAxis: {
      type: "category",
      data: rows.map(row => row.name),
      axisLabel: { fontSize: 12, color: "#475569" }
    },
    yAxis: {
      type: useLog ? "log" : "value",
      name: useLog ? "字节（对数轴）" : "字节",
      nameTextStyle: { fontSize: 11, color: "#6b7a90" },
      axisLabel: { formatter: (value: number) => formatBytes(value) }
    },
    series: [{
      type: "bar",
      barMaxWidth: 64,
      data: rows.map(row => ({
        value: row.value,
        itemStyle: { color: row.color, borderRadius: [4, 4, 0, 0] }
      })),
      label: {
        show: true,
        position: "top",
        fontSize: 12,
        fontWeight: 600,
        color: "#1e293b",
        formatter: (params: { value: number }) => formatBytes(params.value)
      }
    }]
  })
}

async function exportPdf() {
  if (!reportRef.value || !props.result || exportingPdf.value) return
  exportingPdf.value = true
  const loadingMessage = ElMessage({ message: "正在生成分析报告...", type: "info", duration: 0 })
  reportRef.value.classList.add("is-exporting")
  try {
    await nextTick()
    chartInstances.forEach(chart => chart.resize())
    await new Promise<void>(resolve => requestAnimationFrame(() => requestAnimationFrame(() => resolve())))
    const [{ default: html2canvas }, { jsPDF: JsPdf }] = await Promise.all([import("html2canvas"), import("jspdf")])
    const target = reportRef.value
    const canvas = await html2canvas(target, {
      scale: 1.5,
      useCORS: true,
      backgroundColor: "#ffffff",
      logging: false,
      width: target.scrollWidth,
      height: target.scrollHeight,
      windowWidth: target.scrollWidth,
      windowHeight: target.scrollHeight
    })
    const pdf = new JsPdf({ orientation: "portrait", unit: "mm", format: "a4" })
    const margin = 7
    const pageWidth = pdf.internal.pageSize.getWidth()
    const pageHeight = pdf.internal.pageSize.getHeight()
    const usableWidth = pageWidth - margin * 2
    const usableHeight = pageHeight - margin * 2
    const pagePixelHeight = Math.max(1, Math.floor(canvas.width * usableHeight / usableWidth))
    let sourceY = 0
    let pageIndex = 0
    while (sourceY < canvas.height) {
      const sliceHeight = Math.min(pagePixelHeight, canvas.height - sourceY)
      const pageCanvas = document.createElement("canvas")
      pageCanvas.width = canvas.width
      pageCanvas.height = sliceHeight
      const context = pageCanvas.getContext("2d")
      if (!context) throw new Error("无法创建 PDF 页面画布")
      context.fillStyle = "#ffffff"
      context.fillRect(0, 0, pageCanvas.width, pageCanvas.height)
      context.drawImage(canvas, 0, sourceY, canvas.width, sliceHeight, 0, 0, canvas.width, sliceHeight)
      if (pageIndex > 0) pdf.addPage()
      const renderedHeight = sliceHeight * usableWidth / canvas.width
      pdf.addImage(pageCanvas.toDataURL("image/jpeg", 0.94), "JPEG", margin, margin, usableWidth, renderedHeight)
      sourceY += sliceHeight
      pageIndex++
    }
    pdf.save(`${props.exportName}.pdf`)
    ElMessage.success("PDF 已下载")
  } catch (error: unknown) {
    ElMessage.error(error instanceof Error ? error.message : "PDF 生成失败")
  } finally {
    reportRef.value?.classList.remove("is-exporting")
    exportingPdf.value = false
    await nextTick()
    chartInstances.forEach(chart => chart.resize())
    loadingMessage.close()
  }
}

watch(() => props.result, () => {
  void renderCharts()
}, { immediate: true })
onBeforeUnmount(disposeCharts)

defineExpose({ renderCharts })
</script>

<template>
  <div ref="reportRef" v-loading="loading" class="app-key-result-page">
    <div class="app-key-result-header">
      <el-button class="app-key-result-no-export" :icon="ArrowLeft" link @click="emit('back')">
        返回记录列表
      </el-button>
      <div class="app-key-result-header__text">
        <div class="app-key-result-header__title">
          分析结果
        </div>
        <div class="app-key-result-header__sub">
          {{ subtitle }}
        </div>
      </div>
      <div class="app-key-result-header__actions app-key-result-no-export">
        <el-button :icon="Download" type="primary" plain :loading="exportingPdf" @click="exportPdf">
          导出 PDF
        </el-button>
      </div>
    </div>

    <template v-if="result">
      <div class="app-key-result-summary">
        <div class="app-key-result-summary__card app-key-result-summary__card--node">
          <div class="app-key-result-summary__label">
            分析节点
          </div>
          <div class="app-key-result-summary__value">
            {{ result.analysisNodes.length ? result.analysisNodes.join(" / ") : "—" }}
          </div>
        </div>
        <div class="app-key-result-summary__card app-key-result-summary__card--keys">
          <div class="app-key-result-summary__label">
            扫描 key 总数
          </div>
          <div class="app-key-result-summary__value">
            <strong
              v-if="result.totalKeyCount >= 0"
              :class="result.totalKeyCount === 0 ? 'app-key-analysis-key-count--zero' : 'app-key-analysis-key-count--ok'"
            >
              {{ result.totalKeyCount }}
            </strong>
            <span v-else>—</span>
          </div>
        </div>
        <div class="app-key-result-summary__card app-key-result-summary__card--status">
          <div class="app-key-result-summary__label">
            结论
          </div>
          <div class="app-key-result-summary__value">
            <span class="app-key-result-summary__tag" :class="conclusionTag.cls">{{ conclusionTag.text }}</span>
          </div>
        </div>
      </div>

      <div v-if="result.hasAnalysisRisk" class="app-key-result-empty app-key-result-empty--warn">
        <div class="app-key-result-risk-title">
          扫描已完成，但以下情况可能影响统计完整性：
        </div>
        <ul class="app-key-result-risk-list">
          <li v-for="risk in result.analysisRisks" :key="risk">
            {{ risk }}
          </li>
        </ul>
        <div class="app-key-result-risk-hint">
          有数据的图表仍可参考；若统计缺失请查看任务日志后重新发起分析。
        </div>
      </div>

      <div v-if="result.statsMissing" class="app-key-result-empty app-key-result-empty--warn">
        扫描 key 总数为 <strong>{{ result.totalKeyCount }}</strong>，但分布统计未写入数据库。
        请<strong>重新发起一次键值分析</strong>；子任务扫描结果会合并写入 MySQL，任意 API 节点均可查看。
      </div>

      <div v-if="result.emptyDb" class="app-key-result-empty app-key-result-empty--info">
        流程已正常跑完。当前 Redis 节点中 <strong>没有任何 key</strong>，因此类型/TTL/BigKey 分布均为 0，不会出现饼图。
        这不是分析失败；需要先在 Redis 写入数据后重新分析，才能看到分布统计。
      </div>

      <div class="app-key-result-highlight" :class="{ 'app-key-result-charts--dimmed': result.emptyDb }">
        <div class="app-key-result-chart-card app-key-result-chart-card--wide">
          <div class="app-key-result-chart-card__head">
            <span class="app-key-result-chart-card__bar" />
            <span class="app-key-result-chart-card__title">各类型数量占比</span>
          </div>
          <div class="app-key-result-chart-card__body">
            <div
              v-show="chartData('typeCount').length"
              :ref="el => setChartRef('typeCount', el)"
              class="app-key-result-chart"
            />
            <div
              class="app-key-result-chart-empty"
              :class="{ 'is-visible': !chartData('typeCount').length }"
            >
              暂无数量占比数据
            </div>
          </div>
        </div>

        <div class="app-key-result-chart-card app-key-result-chart-card--wide">
          <div class="app-key-result-chart-card__head">
            <span class="app-key-result-chart-card__bar app-key-result-chart-card__bar--memory" />
            <span class="app-key-result-chart-card__title">各类型内存占比</span>
          </div>
          <div class="app-key-result-chart-card__body">
            <div
              v-show="chartData('typeMemory').length"
              :ref="el => setChartRef('typeMemory', el)"
              class="app-key-result-chart"
            />
            <div
              class="app-key-result-chart-empty"
              :class="{ 'is-visible': !chartData('typeMemory').length }"
            >
              {{ result.hasTypeMemoryData ? "暂无分布数据" : "暂无内存占比数据，请重新发起分析后查看" }}
            </div>
          </div>
        </div>
      </div>

      <div class="app-key-result-charts" :class="{ 'app-key-result-charts--dimmed': result.emptyDb }">
        <div v-for="def in chartDefs" :key="def.key" class="app-key-result-chart-card">
          <div class="app-key-result-chart-card__head">
            <span class="app-key-result-chart-card__bar" />
            <span class="app-key-result-chart-card__title">{{ def.title }}</span>
          </div>
          <div class="app-key-result-chart-card__body">
            <div
              v-show="chartData(def.key).length"
              :ref="el => setChartRef(def.key, el)"
              class="app-key-result-chart"
            />
            <div
              class="app-key-result-chart-empty"
              :class="{ 'is-visible': !chartData(def.key).length }"
            >
              暂无分布数据
            </div>
          </div>
        </div>

        <div class="app-key-result-chart-card">
          <div class="app-key-result-chart-card__head">
            <span class="app-key-result-chart-card__bar app-key-result-chart-card__bar--memory" />
            <span class="app-key-result-chart-card__title">键值大小极值</span>
            <span v-if="valueSizeExtremes" class="app-key-result-section__rule">
              样本 {{ valueSizeExtremes.samples.toLocaleString() }} 个 key
            </span>
          </div>
          <div class="app-key-result-chart-card__body">
            <div
              v-show="valueSizeExtremes"
              :ref="el => setChartRef('valueSizeExtremes', el)"
              class="app-key-result-chart"
            />
            <div
              class="app-key-result-chart-empty"
              :class="{ 'is-visible': !valueSizeExtremes }"
            >
              本次分析未采集大小极值，重新发起一次键值分析后可见
            </div>
          </div>
        </div>
      </div>

      <div class="app-key-result-section app-key-result-section--table">
        <div class="app-key-result-section__head">
          <span class="app-key-result-section__bar" />
          <span class="app-key-result-section__title">键类型分布明细</span>
        </div>
        <div class="app-key-result-table-wrap">
          <el-table :data="result.keyTypeDistri" size="small" stripe border>
            <el-table-column prop="name" label="类型" />
            <el-table-column prop="count" label="个数" width="120" />
          </el-table>
          <div v-if="!result.keyTypeDistri.length" class="app-key-analysis-muted">
            暂无数据
          </div>
        </div>
      </div>

      <div class="app-key-result-section app-key-result-section--table">
        <div class="app-key-result-section__head">
          <span class="app-key-result-section__bar" />
          <span class="app-key-result-section__title">TTL 分布明细</span>
        </div>
        <div class="app-key-result-table-wrap">
          <el-table :data="result.keyTtlDistri" size="small" stripe border>
            <el-table-column prop="name" label="分布" />
            <el-table-column prop="count" label="个数" width="120" />
          </el-table>
          <div v-if="!result.keyTtlDistri.length" class="app-key-analysis-muted">
            暂无数据
          </div>
        </div>
      </div>

      <div class="app-key-result-section app-key-result-section--table">
        <div class="app-key-result-section__head">
          <span class="app-key-result-section__bar" />
          <span class="app-key-result-section__title">BigKey 列表（共 {{ result.bigKeyCount }} 个）</span>
          <span class="app-key-result-section__rule">定义规则：{{ bigKeyRuleText(result) }}</span>
        </div>
        <div class="app-key-result-table-wrap">
          <el-table :data="result.bigKeys" size="small" stripe border :max-height="exportingPdf ? undefined : 400">
            <el-table-column prop="instance" label="节点" width="160" />
            <el-table-column prop="keyName" label="键名" min-width="200" show-overflow-tooltip />
            <el-table-column prop="type" label="类型" width="80" />
            <el-table-column prop="sizeLabel" label="大小 / 元素数" width="140" />
          </el-table>
          <div v-if="!result.bigKeys.length" class="app-key-analysis-muted">
            {{ result.bigKeyCount > 0 ? "仅展示前 100 条" : "0 个 BigKey" }}
          </div>
        </div>
      </div>

      <div class="app-key-result-section app-key-result-section--table">
        <div class="app-key-result-section__head">
          <span class="app-key-result-section__bar" />
          <span class="app-key-result-section__title">不同数据类型 Key 前缀统计（每类 Top50）</span>
        </div>
        <div class="app-key-result-table-wrap">
          <el-table :data="prefixRows" size="small" stripe border :max-height="exportingPdf ? undefined : 620">
            <el-table-column prop="type" label="数据类型" width="110" />
            <el-table-column prop="prefix" label="Key 前缀" min-width="220" show-overflow-tooltip />
            <el-table-column prop="count" label="Key 数量" width="130" sortable />
            <el-table-column prop="bytes" label="总占用" width="130" sortable>
              <template #default="{ row }">
                {{ row.bytesLabel }}
              </template>
            </el-table-column>
          </el-table>
          <div v-if="!prefixRows.length" class="app-key-analysis-muted">
            暂无前缀统计数据
          </div>
        </div>
      </div>

      <div class="app-key-result-section app-key-result-section--table app-key-result-section--top-keys">
        <div class="app-key-result-section__head">
          <span class="app-key-result-section__bar" />
          <span class="app-key-result-section__title">最大的 100 个 Key</span>
        </div>
        <div v-if="!result.topKeyFromMemoryScan && result.topKeys.length" class="app-key-result-section__hint">
          以下按元素个数/长度排序；内存扫描完成后将按实际占用字节排序。
        </div>
        <div class="app-key-result-table-wrap">
          <el-table :data="result.topKeys" size="small" stripe border class="app-key-result-table" :max-height="exportingPdf ? undefined : 900">
            <el-table-column type="index" label="#" width="52" :index="i => i + 1" />
            <el-table-column prop="instance" label="节点" width="160" />
            <el-table-column prop="keyName" label="键名" min-width="240" show-overflow-tooltip class-name="app-key-result-table__key" />
            <el-table-column prop="type" label="类型" width="90" />
            <el-table-column prop="sizeLabel" label="占用" width="130" />
          </el-table>
          <div v-if="!result.topKeys.length" class="app-key-analysis-muted">
            暂无数据
          </div>
        </div>
      </div>
    </template>
  </div>
</template>
