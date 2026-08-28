<script lang="ts" setup>
import type { ECharts } from "echarts"
import type { DashboardActiveAlert, DashboardOps, DashboardTopBoard, DashboardTopBoardRow } from "@/api/cachecloud"
import { Refresh } from "@element-plus/icons-vue"
import { getDashboardOpsApi } from "@/api/cachecloud"
import { formatAuditHandler, hasAuditHandlerText } from "@/common/utils/audit-handler-meta"
import { initGrafanaChart } from "@/common/utils/chart-theme"

const router = useRouter()

const loading = ref(false)
const ops = ref<DashboardOps | null>(null)

/** 采集是分钟级，刷新快于 60s 只会拿到同一份数据 */
const REFRESH_MS = 60_000
/** 默认关闭：整页数据量不小，让使用者自己决定要不要后台一直拉 */
const autoRefresh = ref(false)
let timer: ReturnType<typeof setInterval> | null = null

const trendRef = useTemplateRef<HTMLDivElement>("trendRef")
const healthRef = useTemplateRef<HTMLDivElement>("healthRef")
let trendChart: ECharts | null = null
let healthChart: ECharts | null = null

const kpi = computed(() => ops.value?.kpi ?? null)
const health = computed(() => ops.value?.clusterHealth ?? null)

const HEALTH_PAGE_SIZE = 5
const healthPage = ref(1)
const healthRows = computed(() => health.value?.rows ?? [])
const healthPagedRows = computed(() => {
  const start = (healthPage.value - 1) * HEALTH_PAGE_SIZE
  return healthRows.value.slice(start, start + HEALTH_PAGE_SIZE)
})
// 集群下线后总数会变少，停在越界的页码上会显示成空表
watch(() => healthRows.value.length, (total) => {
  const maxPage = Math.max(1, Math.ceil(total / HEALTH_PAGE_SIZE))
  if (healthPage.value > maxPage) healthPage.value = maxPage
})
const posture = computed(() => ops.value?.posture ?? null)
const unmonitored = computed(() => posture.value?.unmonitoredApps ?? [])
const unmonitoredVisible = ref(false)
const alertDetail = ref<DashboardActiveAlert | null>(null)
const alertDetailVisible = ref(false)

function openAlertDetail(row: DashboardActiveAlert) {
  alertDetail.value = row
  alertDetailVisible.value = true
}
const activeAlerts = computed(() => ops.value?.activeAlerts ?? [])
const sentinels = computed(() => ops.value?.sentinels ?? [])
const boards = computed(() => ops.value?.boards ?? [])
const resources = computed(() => ops.value?.resources ?? [])
const slowCommands = computed(() => ops.value?.slowCommands ?? [])
const recentOps = computed(() => ops.value?.recentOps ?? [])

function humanBytes(bytes: number) {
  if (!bytes || bytes <= 0) return "0"
  const units = ["B", "KB", "MB", "GB", "TB"]
  let v = bytes
  let i = 0
  while (v >= 1024 && i < units.length - 1) {
    v /= 1024
    i++
  }
  return `${v >= 100 ? v.toFixed(0) : v.toFixed(1)}${units[i]}`
}

function humanCount(n: number) {
  if (n >= 1e8) return `${(n / 1e8).toFixed(2)} 亿`
  if (n >= 1e4) return `${(n / 1e4).toFixed(2)} 万`
  return String(n ?? 0)
}

async function fetchData() {
  loading.value = true
  try {
    const { data } = await getDashboardOpsApi()
    ops.value = data ?? null
    await nextTick()
    await renderCharts()
  } finally {
    loading.value = false
  }
}

const HC_COLORS = ["#4d8dff", "#22c55e", "#f59e0b", "#ef4444", "#8b5cf6", "#06b6d4", "#ec4899", "#64748b"]

/** echarts 走动态 import，与其它图表页保持一致，避免打进首屏包 */
async function renderCharts() {
  const echarts = await import("echarts")
  renderTrend(echarts)
  renderHealth(echarts)
}

function renderTrend(echarts: typeof import("echarts")) {
  const data = ops.value?.trend
  if (!trendRef.value) return
  trendChart?.dispose()
  trendChart = null
  if (!data || !data.times.length) return
  trendChart = initGrafanaChart(echarts, trendRef.value)
  trendChart.setOption({
    tooltip: {
      trigger: "axis",
      valueFormatter: (v: number | null) => (v == null ? "无采样" : `${v.toFixed(1)}%`)
    },
    legend: { bottom: 0, type: "scroll" },
    grid: { left: 56, right: 16, top: 16, bottom: 42 },
    xAxis: { type: "category", data: data.times, boundaryGap: false },
    yAxis: {
      type: "value",
      max: 100,
      axisLabel: { formatter: (v: number) => `${v}%` }
    },
    series: data.series.map((s, i) => ({
      name: s.name,
      type: "line",
      smooth: false,
      showSymbol: false,
      // connectNulls 保持 false：缺采样点断线，不画出一条不存在的直线
      connectNulls: false,
      itemStyle: { color: HC_COLORS[i % HC_COLORS.length] },
      data: s.values
    }))
  })
}

function renderHealth(echarts: typeof import("echarts")) {
  const data = ops.value?.clusterHealth
  if (!healthRef.value) return
  healthChart?.dispose()
  healthChart = null
  if (!data) return
  const total = data.healthy + data.warning + data.abnormal + data.offline
  healthChart = initGrafanaChart(echarts, healthRef.value)
  healthChart.setOption({
    tooltip: { trigger: "item" },
    legend: { orient: "vertical", right: 0, top: "center", itemWidth: 10, itemHeight: 10 },
    series: [{
      type: "pie",
      radius: ["62%", "84%"],
      center: ["36%", "50%"],
      avoidLabelOverlap: false,
      label: {
        show: true,
        position: "center",
        formatter: () => `{v|${total}}\n{l|集群}`,
        rich: {
          v: { fontSize: 26, fontWeight: 700, color: "#1a2332" },
          l: { fontSize: 12, color: "#6b7a90", padding: [4, 0, 0, 0] }
        }
      },
      data: [
        { name: "健康", value: data.healthy, itemStyle: { color: "#22c55e" } },
        { name: "告警", value: data.warning, itemStyle: { color: "#f59e0b" } },
        { name: "异常", value: data.abnormal, itemStyle: { color: "#ef4444" } },
        { name: "离线", value: data.offline, itemStyle: { color: "#94a3b8" } }
      ]
    }]
  })
}

function levelTag(level: number) {
  if (level >= 2) return "danger"
  if (level === 1) return "warning"
  return "info"
}

function statusTag(status: string) {
  if (status === "healthy") return "success"
  if (status === "warning") return "warning"
  if (status === "abnormal") return "danger"
  return "info"
}

function openApp(appId: number, tab = "app_alert_record") {
  if (!appId) return
  router.push({ path: `/app/detail/${appId}`, query: { tab } })
}

function openInstance(instanceId: number, appId: number, tab?: string) {
  if (!instanceId) return
  const query: Record<string, string> = { appId: String(appId) }
  if (tab) query.tab = tab
  router.push({ path: `/external/redis/detail/${instanceId}`, query })
}

/** 各榜的下钻目标不同：连接数看连接信息，慢查询看集群的延迟监控 */
function openBoardRow(board: DashboardTopBoard, row: DashboardTopBoardRow) {
  if (board.linkType === "appLatency") {
    openApp(row.appId, "app_latency")
    return
  }
  if (board.linkType === "instanceClients") {
    openInstance(row.instanceId, row.appId, "instance_clientList")
    return
  }
  openInstance(row.instanceId, row.appId)
}

function boardBarWidth(board: DashboardTopBoard, value: number) {
  const max = board.rows.length ? Math.max(...board.rows.map(r => r.value)) : 0
  if (max <= 0) return "0%"
  return `${Math.max(2, Math.round((value / max) * 100))}%`
}

function handleResize() {
  trendChart?.resize()
  healthChart?.resize()
}

function setupTimer() {
  if (timer) clearInterval(timer)
  timer = null
  if (autoRefresh.value) {
    timer = setInterval(() => void fetchData(), REFRESH_MS)
  }
}

watch(autoRefresh, setupTimer)

onMounted(() => {
  void fetchData()
  setupTimer()
  window.addEventListener("resize", handleResize)
})

onActivated(handleResize)

onBeforeUnmount(() => {
  if (timer) clearInterval(timer)
  window.removeEventListener("resize", handleResize)
  trendChart?.dispose()
  healthChart?.dispose()
})
</script>

<template>
  <div v-loading="loading" class="ops-dash">
    <!-- ── 顶部 6 张 KPI 卡 ───────────────────────────────── -->
    <div class="ops-dash__kpis">
      <div class="ops-kpi">
        <div class="ops-kpi__label">
          实例总数
        </div>
        <div class="ops-kpi__value">
          {{ kpi?.instanceTotal ?? 0 }}
        </div>
        <div class="ops-kpi__sub">
          <span>在线 <b class="is-ok">{{ kpi?.instanceOnline ?? 0 }}</b></span>
          <span>离线 <b :class="{ 'is-bad': (kpi?.instanceOffline ?? 0) > 0 }">{{ kpi?.instanceOffline ?? 0 }}</b></span>
        </div>
      </div>
      <div class="ops-kpi">
        <div class="ops-kpi__label">
          集群总数
        </div>
        <div class="ops-kpi__value">
          {{ kpi?.clusterTotal ?? 0 }}
        </div>
        <div class="ops-kpi__sub">
          <span>主节点 {{ kpi?.masterCount ?? 0 }}</span>
          <span>从节点 {{ kpi?.slaveCount ?? 0 }}</span>
        </div>
      </div>
      <div class="ops-kpi">
        <div class="ops-kpi__label">
          内存使用
        </div>
        <div class="ops-kpi__value">
          {{ humanBytes(kpi?.memUsed ?? 0) }}
        </div>
        <div class="ops-kpi__sub">
          <span>总量 {{ humanBytes(kpi?.memTotal ?? 0) }}</span>
          <span>使用率 <b>{{ (kpi?.memRatio ?? 0).toFixed(1) }}%</b></span>
        </div>
      </div>
      <div class="ops-kpi">
        <div class="ops-kpi__label">
          Key 总数
        </div>
        <div class="ops-kpi__value">
          {{ humanCount(kpi?.keyTotal ?? 0) }}
        </div>
        <div class="ops-kpi__sub">
          <span>过期 Key {{ humanCount(kpi?.keyExpires ?? 0) }}</span>
          <span>({{ (kpi?.keyExpiresRatio ?? 0).toFixed(1) }}%)</span>
        </div>
      </div>
      <div class="ops-kpi">
        <div class="ops-kpi__label">
          QPS
        </div>
        <div class="ops-kpi__value">
          {{ (kpi?.qps ?? 0).toLocaleString() }}
        </div>
        <div class="ops-kpi__sub">
          <span>近 {{ ops?.windowMinutes ?? 60 }} 分钟峰值 {{ (kpi?.qpsPeak ?? 0).toLocaleString() }}</span>
        </div>
      </div>
      <div class="ops-kpi">
        <div class="ops-kpi__label">
          命中率
        </div>
        <div class="ops-kpi__value">
          <template v-if="kpi?.hitRate != null">
            {{ kpi.hitRate.toFixed(2) }}%
          </template>
          <!-- 0% 会被读成「全部穿透」，窗口内没有请求就如实显示「—」 -->
          <template v-else>
            —
          </template>
        </div>
        <div class="ops-kpi__sub">
          <span v-if="kpi?.hitRate == null" class="is-muted">近 {{ ops?.windowMinutes ?? 60 }} 分钟无请求</span>
          <span v-else-if="kpi?.hitRateDelta != null">
            较昨日
            <b :class="kpi.hitRateDelta >= 0 ? 'is-ok' : 'is-bad'">
              {{ kpi.hitRateDelta >= 0 ? "+" : "" }}{{ kpi.hitRateDelta.toFixed(2) }}%
            </b>
          </span>
          <!-- 无昨日样本时显式说明，不用 0 冒充"与昨日持平" -->
          <span v-else class="is-muted">无昨日同期数据</span>
        </div>
      </div>
    </div>

    <!-- ── 态势条 ─────────────────────────────────────────── -->
    <div class="ops-dash__bar">
      <span class="ops-chip ops-chip--severe">严重 <b>{{ posture?.severeCount ?? 0 }}</b></span>
      <span class="ops-chip ops-chip--warn">警告 <b>{{ posture?.warningCount ?? 0 }}</b></span>
      <span
        v-if="unmonitored.length"
        class="ops-chip ops-chip--muted"
        role="button"
        tabindex="0"
        @click="unmonitoredVisible = true"
        @keydown.enter="unmonitoredVisible = true"
      >
        未监控 <b>{{ unmonitored.length }}</b> 个集群
      </span>
      <span
        v-for="item in sentinels"
        :key="item.key"
        class="ops-chip"
        :class="item.value > 0 ? 'ops-chip--severe' : 'ops-chip--ok'"
        :title="item.hotNodes.join('、')"
      >
        {{ item.label }} <b>{{ item.value }}</b>
      </span>
      <span class="ops-dash__spacer" />
      <span class="ops-dash__time">截至 {{ ops?.dataTime || "—" }}</span>
      <span class="ops-dash__auto">
        自动刷新
        <el-switch v-model="autoRefresh" size="small" />
      </span>
      <el-button :icon="Refresh" size="small" text @click="fetchData">
        刷新
      </el-button>
    </div>

    <!-- ── 集群健康 + 指标趋势 ────────────────────────────── -->
    <div class="ops-dash__row ops-dash__row--2">
      <el-card shadow="never" class="ops-panel">
        <template #header>
          <span class="ops-panel__title">集群健康状态</span>
        </template>
        <div class="ops-health">
          <div ref="healthRef" class="ops-health__chart" />
          <div class="ops-health__list">
            <el-table :data="healthPagedRows" size="small" class="ops-health__table">
              <el-table-column label="集群名称" min-width="140" show-overflow-tooltip>
                <template #default="{ row }">
                  <el-link type="primary" :underline="false" @click="openApp(row.appId, 'app_topology')">
                    {{ row.appName }}
                  </el-link>
                </template>
              </el-table-column>
              <el-table-column label="状态" width="80">
                <template #default="{ row }">
                  <el-tag :type="statusTag(row.status)" size="small">
                    {{ row.statusDesc }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column prop="masterCount" label="主" width="52" align="right" />
              <el-table-column prop="slaveCount" label="从" width="52" align="right" />
              <el-table-column label="内存使用率" min-width="150">
                <template #default="{ row }">
                  <div class="ops-mem">
                    <span class="ops-mem__bar">
                      <span
                        class="ops-mem__fill"
                        :class="{ 'is-high': row.memRatio >= 90 }"
                        :style="{ width: `${Math.min(100, row.memRatio)}%` }"
                      />
                    </span>
                    <span class="ops-mem__text">{{ row.memRatio.toFixed(1) }}%</span>
                  </div>
                </template>
              </el-table-column>
            </el-table>
            <el-pagination
              v-if="healthRows.length > HEALTH_PAGE_SIZE"
              v-model:current-page="healthPage"
              :page-size="HEALTH_PAGE_SIZE"
              :total="healthRows.length"
              layout="total, prev, pager, next"
              small
              class="ops-health__pager"
            />
          </div>
        </div>
      </el-card>

      <el-card shadow="never" class="ops-panel">
        <template #header>
          <span class="ops-panel__title">内存使用率</span>
          <span class="ops-panel__hint">Top5 节点 · only for master</span>
        </template>
        <div ref="trendRef" class="ops-trend" />
      </el-card>
    </div>

    <!-- ── 资源使用汇总 ───────────────────────────────────── -->
    <el-card shadow="never" class="ops-panel ops-panel--flat">
      <template #header>
        <span class="ops-panel__title">资源使用情况（汇总）</span>
      </template>
      <div class="ops-res">
        <div v-for="item in resources" :key="item.key" class="ops-res__item">
          <div class="ops-res__label">
            {{ item.label }}
          </div>
          <div class="ops-res__value">
            {{ item.display }}
          </div>
          <div v-if="item.percent != null" class="ops-res__bar">
            <span
              class="ops-res__fill"
              :class="{ 'is-high': item.percent >= 90 }"
              :style="{ width: `${Math.min(100, item.percent)}%` }"
            />
          </div>
          <div class="ops-res__sub">
            {{ item.sub }}
          </div>
        </div>
      </div>
    </el-card>

    <!-- ── 当前告警 ───────────────────────────────────────── -->
    <el-card shadow="never" class="ops-panel">
      <template #header>
        <span class="ops-panel__title">当前告警</span>
        <span class="ops-panel__hint">按「集群 + 节点 + 指标」归并，5 分钟内无新记录判定已恢复</span>
      </template>
      <el-table v-if="activeAlerts.length" :data="activeAlerts" size="small" stripe max-height="300">
        <el-table-column label="状态" width="86">
          <template #default="{ row }">
            <el-tag :type="row.active ? 'danger' : 'info'" size="small" effect="plain">
              {{ row.active ? "持续中" : "已恢复" }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="级别" width="74">
          <template #default="{ row }">
            <el-tag :type="levelTag(row.importantLevel)" size="small">
              {{ row.importantLevelDesc }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="集群" min-width="140" show-overflow-tooltip>
          <template #default="{ row }">
            <el-link type="primary" :underline="false" @click="openApp(row.appId)">
              {{ row.appName }}
            </el-link>
          </template>
        </el-table-column>
        <el-table-column label="实例" width="150">
          <template #default="{ row }">
            <el-link
              v-if="row.instanceId"
              type="primary"
              :underline="false"
              @click="openInstance(row.instanceId, row.appId)"
            >
              {{ row.hostPort }}
            </el-link>
            <span v-else>{{ row.hostPort }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="metric" label="指标" min-width="130" show-overflow-tooltip />
        <el-table-column prop="duration" label="持续" width="80" align="right" />
        <el-table-column prop="count" label="次数" width="70" align="right" />
        <el-table-column prop="lastTime" label="最近一次" width="130" />
        <el-table-column label="详情" width="72" fixed="right">
          <template #default="{ row }">
            <el-link type="primary" :underline="false" @click="openAlertDetail(row)">
              查看
            </el-link>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-else description="近 6 小时内没有告警记录" :image-size="60" />
    </el-card>

    <!-- ── Top-N 榜 ───────────────────────────────────────── -->
    <div class="ops-dash__boards">
      <el-card v-for="board in boards" :key="board.key" shadow="never" class="ops-panel">
        <template #header>
          <span class="ops-panel__title">{{ board.label }}</span>
          <span class="ops-panel__hint">
            <!-- 榜名里已有语义时不再重复 TopN，避免出现「Top5 · top5 ...」 -->
            <template v-if="board.hint">{{ board.hint }}</template>
            <template v-else>Top{{ board.rows.length || 5 }}</template>
          </span>
        </template>
        <div v-if="board.rows.length" class="ops-board">
          <div
            v-for="row in board.rows"
            :key="row.instanceId || row.hostPort"
            class="ops-board__row"
            :class="{ 'is-exceeded': row.exceeded }"
            role="button"
            tabindex="0"
            @click="openBoardRow(board, row)"
            @keydown.enter="openBoardRow(board, row)"
          >
            <span class="ops-board__node">{{ row.hostPort }}</span>
            <span class="ops-board__bar">
              <span class="ops-board__fill" :style="{ width: boardBarWidth(board, row.value) }" />
            </span>
            <span class="ops-board__value">{{ row.display }}</span>
          </div>
        </div>
        <!-- 全零是"当前健康"，不是"没数据"，必须写清楚 -->
        <div v-else class="ops-board__empty">
          窗口内无非零记录（该项当前正常）
        </div>
      </el-card>
    </div>

    <!-- ── 慢命令 + 最近操作 ──────────────────────────────── -->
    <div class="ops-dash__row ops-dash__row--2">
      <el-card shadow="never" class="ops-panel">
        <template #header>
          <span class="ops-panel__title">慢命令 (Top 10)</span>
          <span class="ops-panel__hint">近 24 小时</span>
        </template>
        <el-table v-if="slowCommands.length" :data="slowCommands" size="small">
          <el-table-column prop="command" label="命令" min-width="110" />
          <el-table-column label="平均耗时" width="110" align="right">
            <template #default="{ row }">
              {{ row.avgCostMs.toFixed(2) }} ms
            </template>
          </el-table-column>
          <el-table-column prop="calls" label="次数" width="90" align="right" />
          <el-table-column label="耗时占比" min-width="140">
            <template #default="{ row }">
              <div class="ops-mem">
                <span class="ops-mem__bar">
                  <span class="ops-mem__fill" :style="{ width: `${Math.min(100, row.costRatio)}%` }" />
                </span>
                <span class="ops-mem__text">{{ row.costRatio.toFixed(1) }}%</span>
              </div>
            </template>
          </el-table-column>
        </el-table>
        <el-empty v-else description="近 24 小时无慢查询" :image-size="60" />
      </el-card>

      <el-card shadow="never" class="ops-panel">
        <template #header>
          <span class="ops-panel__title">最近操作</span>
          <span class="ops-panel__hint">最近 10 条</span>
        </template>
        <el-table v-if="recentOps.length" :data="recentOps" size="small">
          <el-table-column prop="time" label="时间" width="110" />
          <el-table-column prop="userName" label="用户" width="90" />
          <el-table-column label="操作" min-width="150" show-overflow-tooltip>
            <template #default="{ row }">
              <!-- 与审计日志共用同一张映射表，映射不到时保留原始方法名 -->
              <span :class="{ 'ops-recent__handler-raw': !hasAuditHandlerText(row.handler) }">
                {{ formatAuditHandler(row.handler) }}
              </span>
            </template>
          </el-table-column>
          <el-table-column label="操作对象" min-width="130" show-overflow-tooltip>
            <template #default="{ row }">
              <!-- 与审计日志一致，只陈述不跳转：见该页「对象」列的说明 -->
              <span>{{ row.objectLabel || "-" }}</span>
            </template>
          </el-table-column>
          <el-table-column label="结果" width="76">
            <template #default="{ row }">
              <el-tag :type="row.success ? 'success' : 'danger'" size="small" effect="plain">
                {{ row.success ? "成功" : "失败" }}
              </el-tag>
            </template>
          </el-table-column>
        </el-table>
        <el-empty v-else description="暂无操作记录" :image-size="60" />
      </el-card>
    </div>

    <el-drawer v-model="alertDetailVisible" title="告警详情" size="560px">
      <el-descriptions v-if="alertDetail" :column="1" border size="small">
        <el-descriptions-item label="状态">
          <el-tag :type="alertDetail.active ? 'danger' : 'info'" size="small" effect="plain">
            {{ alertDetail.active ? "持续中" : "已恢复" }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="级别">
          {{ alertDetail.importantLevelDesc }}
        </el-descriptions-item>
        <el-descriptions-item label="集群">
          {{ alertDetail.appName }}
        </el-descriptions-item>
        <el-descriptions-item label="实例">
          {{ alertDetail.hostPort }}
        </el-descriptions-item>
        <el-descriptions-item label="指标">
          {{ alertDetail.metric }}
        </el-descriptions-item>
        <el-descriptions-item label="首次发生">
          {{ alertDetail.firstTime }}
        </el-descriptions-item>
        <el-descriptions-item label="最近一次">
          {{ alertDetail.lastTime }}
        </el-descriptions-item>
        <el-descriptions-item label="持续时长">
          {{ alertDetail.duration }}
        </el-descriptions-item>
        <el-descriptions-item label="累计次数">
          {{ alertDetail.count }} 次
        </el-descriptions-item>
      </el-descriptions>
      <h4 class="ops-detail__title">
        最近一条告警原文
      </h4>
      <pre class="ops-detail__content">{{ alertDetail?.latestContent || "-" }}</pre>
      <p class="ops-detail__hint">
        该行由「集群 + 节点 + 指标」归并而来，累计次数为窗口内的原始告警条数。
      </p>
      <template #footer>
        <el-button @click="alertDetailVisible = false">
          关闭
        </el-button>
        <el-button
          v-if="alertDetail?.appId"
          type="primary"
          @click="openApp(alertDetail.appId); alertDetailVisible = false"
        >
          查看该集群全部告警
        </el-button>
      </template>
    </el-drawer>

    <el-drawer v-model="unmonitoredVisible" title="未开启告警的集群" size="420px">
      <el-alert
        type="warning"
        :closable="false"
        show-icon
        title="这些集群不会产生任何告警记录，面板上的「无告警」对它们不成立。"
        class="ops-unmon__tip"
      />
      <div v-for="app in unmonitored" :key="app.appId" class="ops-unmon__row">
        <span>{{ app.appName }}</span>
        <el-link type="primary" :underline="false" @click="openApp(app.appId, 'app_detail')">
          去开启
        </el-link>
      </div>
    </el-drawer>
  </div>
</template>

<style lang="scss" scoped>
.ops-dash {
  padding: 2px 0 16px;
}

/* 顶部 KPI：一行 6 张，窄屏自动折行 */
.ops-dash__kpis {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(190px, 1fr));
  gap: 10px;
  margin-bottom: 10px;
}

.ops-kpi {
  padding: 12px 14px;
  background: var(--rp-surface, #fff);
  border: 1px solid var(--rp-border, #e8edf3);
  border-radius: 8px;
}

.ops-kpi__label {
  font-size: 12px;
  color: var(--rp-text-muted, #6b7a90);
}

.ops-kpi__value {
  margin: 4px 0 6px;
  font-size: 26px;
  font-weight: 700;
  line-height: 1.15;
  color: var(--rp-text, #1a2332);
}

.ops-kpi__sub {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  font-size: 12px;
  color: var(--rp-text-muted, #6b7a90);
}

.ops-kpi__sub b {
  font-weight: 600;
}
.is-ok {
  color: #16a34a;
}
.is-bad {
  color: #ef4444;
}
.is-muted {
  color: var(--rp-text-muted, #6b7a90);
}

/* 态势条：告警计数 + 哨兵计数 + 刷新控制并排一行 */
.ops-dash__bar {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
  padding: 8px 12px;
  margin-bottom: 10px;
  background: var(--rp-surface, #fff);
  border: 1px solid var(--rp-border, #e8edf3);
  border-radius: 8px;
}

.ops-chip {
  padding: 4px 12px;
  font-size: 12px;
  border: 1px solid var(--rp-border, #e8edf3);
  border-radius: 999px;
}

.ops-chip b {
  font-weight: 700;
}

.ops-chip--severe {
  color: #b91c1c;
  background: #fef2f2;
  border-color: #fecaca;
}

.ops-chip--warn {
  color: #b45309;
  background: #fffbeb;
  border-color: #fde68a;
}

.ops-chip--ok {
  color: #15803d;
  background: #f0fdf4;
  border-color: #bbf7d0;
}

.ops-chip--muted {
  color: #475569;
  cursor: pointer;
  background: #f8fafc;
}

.ops-dash__spacer {
  flex: 1 1 auto;
}

.ops-dash__time,
.ops-dash__auto {
  display: inline-flex;
  gap: 6px;
  align-items: center;
  font-size: 12px;
  color: var(--rp-text-muted, #6b7a90);
}

.ops-dash__row {
  display: grid;
  gap: 10px;
  margin-bottom: 10px;
}

.ops-dash__row--2 {
  grid-template-columns: repeat(auto-fit, minmax(460px, 1fr));
}

.ops-panel {
  margin-bottom: 10px;
}

.ops-panel__title {
  font-size: 14px;
  font-weight: 600;
}

.ops-panel__hint {
  margin-left: 10px;
  font-size: 12px;
  color: var(--rp-text-muted, #6b7a90);
}

.ops-health {
  display: grid;
  grid-template-columns: 220px 1fr;
  gap: 8px;
  align-items: center;
}

.ops-health__chart {
  height: 190px;
}

/* 表格与分页器共占网格右侧那一格，否则分页器会掉到饼图下面去 */
.ops-health__list {
  display: flex;
  flex-direction: column;
  gap: 6px;
  min-width: 0;
}

.ops-recent__handler-raw {
  color: var(--rp-text-muted, #6b7a90);
}

.ops-health__pager {
  justify-content: flex-end;
}

.ops-trend {
  height: 230px;
}

/* 内存条：表格内与资源汇总共用 */
.ops-mem {
  display: flex;
  gap: 8px;
  align-items: center;
}

.ops-mem__bar {
  flex: 1 1 auto;
  height: 8px;
  background: var(--rp-primary-soft, #eaf2ff);
  border-radius: 999px;
}

.ops-mem__fill {
  display: block;
  height: 100%;
  background: var(--rp-primary, #4d8dff);
  border-radius: 999px;
}

.ops-mem__fill.is-high {
  background: #ef4444;
}

.ops-mem__text {
  min-width: 46px;
  font-size: 12px;
  text-align: right;
}

.ops-res {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(150px, 1fr));
  gap: 12px;
}

.ops-res__item {
  padding: 10px 12px;
  background: var(--rp-surface-muted, #f8fafc);
  border-radius: 8px;
}

.ops-res__label {
  font-size: 12px;
  color: var(--rp-text-muted, #6b7a90);
}

.ops-res__value {
  margin: 4px 0;
  font-size: 18px;
  font-weight: 700;
}

.ops-res__bar {
  height: 6px;
  margin-bottom: 4px;
  background: var(--rp-primary-soft, #eaf2ff);
  border-radius: 999px;
}

.ops-res__fill {
  display: block;
  height: 100%;
  background: var(--rp-primary, #4d8dff);
  border-radius: 999px;
}

.ops-res__fill.is-high {
  background: #ef4444;
}

.ops-res__sub {
  font-size: 11px;
  color: var(--rp-text-muted, #6b7a90);
}

.ops-dash__boards {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(300px, 1fr));
  gap: 10px;
  margin-bottom: 10px;
}

.ops-board {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.ops-board__row {
  display: grid;
  grid-template-columns: 124px 1fr 68px;
  gap: 8px;
  align-items: center;
  padding: 3px 4px;
  font-size: 12px;
  cursor: pointer;
  border-radius: 4px;
}

.ops-board__row:hover {
  background: var(--rp-primary-soft, #eaf2ff);
}

.ops-board__node {
  overflow: hidden;
  font-family: Menlo, Monaco, Consolas, monospace;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ops-board__bar {
  height: 8px;
  background: var(--rp-primary-soft, #eaf2ff);
  border-radius: 999px;
}

.ops-board__fill {
  display: block;
  height: 100%;
  background: var(--rp-primary, #4d8dff);
  border-radius: 999px;
}

.ops-board__value {
  font-weight: 600;
  text-align: right;
}

.ops-board__row.is-exceeded .ops-board__fill {
  background: #ef4444;
}
.ops-board__row.is-exceeded .ops-board__value {
  color: #ef4444;
}

.ops-board__empty {
  padding: 10px 2px;
  font-size: 12px;
  color: #16a34a;
}

.ops-detail__title {
  margin: 16px 0 8px;
  font-size: 14px;
}

.ops-detail__content {
  padding: 12px;
  margin: 0;
  font-size: 12px;
  line-height: 1.6;
  word-break: break-all;
  white-space: pre-wrap;
  background: var(--el-fill-color-light);
  border-radius: 4px;
}

.ops-detail__hint {
  margin: 10px 0 0;
  font-size: 12px;
  color: var(--rp-text-muted, #6b7a90);
}

.ops-unmon__tip {
  margin-bottom: 12px;
}

.ops-unmon__row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 2px;
  font-size: 13px;
  border-bottom: 1px solid var(--rp-border, #e8edf3);
}
</style>
