<script lang="ts" setup>
import type { BenchmarkProgress, BenchmarkResult, DiagnosticAppOption } from "@/api/cachecloud"
import {
  deleteBenchmarkApi,
  getBenchmarkCommandsApi,
  getBenchmarkListApi,
  getBenchmarkProgressApi,
  getBenchmarkTargetsApi,
  startBenchmarkApi,
  stopBenchmarkApi
} from "@/api/cachecloud"

const props = defineProps<{ apps: DiagnosticAppOption[] }>()

/** 与后端 BenchmarkCommand 的分组一致 */
const catalog = ref<Record<string, { name: string, kind: string }[]>>({})
const targets = ref<{ hostPort: string, slotRange: string, slotCount: number }[]>([])
const history = ref<BenchmarkResult[]>([])
const progress = ref<BenchmarkProgress | null>(null)
const runningTaskId = ref<number | null>(null)
const starting = ref(false)
const loadingTargets = ref(false)

/** 实时曲线只保留最近 120 个采样点，压测最长也就十分钟量级 */
const qpsSeries = ref<{ t: number, qps: number, p99: number }[]>([])
let timer: ReturnType<typeof setInterval> | null = null

const form = reactive({
  appId: undefined as number | undefined,
  targetMode: "cluster" as "cluster" | "node",
  targetNodes: [] as string[],
  commands: ["GET", "SET"] as string[],
  concurrency: 50,
  keySpace: 10000,
  valueSize: 128,
  ttlSeconds: 300,
  stopBy: "duration" as "duration" | "requests",
  /** 界面按分钟填，提交时换算成秒；最低 1 分钟——几十秒的压测受连接建立与预热影响太大 */
  durationMinutes: 1,
  totalRequests: 1000000,
  pipeline: 1,
  hotspot: false,
  readWeight: 5,
  writeWeight: 5,
  cleanup: true
})

const selectedApp = computed(() => props.apps.find(a => a.appId === form.appId))
/** 只有 Cluster 才谈得上「指定节点」，其余类型直接压主节点 */
const isCluster = computed(() => selectedApp.value?.type === 2)

const hasWriteCommand = computed(() =>
  form.commands.some(name => Object.values(catalog.value).flat().find(c => c.name === name)?.kind === "WRITE")
)
const hasReadCommand = computed(() =>
  form.commands.some(name => Object.values(catalog.value).flat().find(c => c.name === name)?.kind === "READ")
)

// 超出常规范围时给出提示，但不阻止提交
const concurrencyWarn = computed(() => form.concurrency > 200)
const durationWarn = computed(() => form.stopBy === "duration" && form.durationMinutes > 10)
const keySpaceWarn = computed(() => form.keySpace > 1000000)

async function loadCatalog() {
  const { data } = await getBenchmarkCommandsApi()
  catalog.value = data ?? {}
}

async function loadTargets() {
  form.targetNodes = []
  targets.value = []
  if (!form.appId || !isCluster.value) return
  loadingTargets.value = true
  try {
    const { data } = await getBenchmarkTargetsApi(form.appId)
    targets.value = data ?? []
  } finally {
    loadingTargets.value = false
  }
}

async function loadHistory() {
  const { data } = await getBenchmarkListApi({ appId: form.appId, pageNo: 1, pageSize: 20 })
  history.value = data?.items ?? []
}

function handleAppChange() {
  form.targetMode = "cluster"
  loadTargets()
  loadHistory()
}

function toggleCommand(name: string) {
  const index = form.commands.indexOf(name)
  if (index >= 0) form.commands.splice(index, 1)
  else form.commands.push(name)
}

async function handleStart() {
  if (!form.appId) return ElMessage.warning("请选择目标集群")
  if (!form.commands.length) return ElMessage.warning("请至少勾选一个命令")
  starting.value = true
  try {
    const { data } = await startBenchmarkApi({
      appId: form.appId,
      targetNodes: form.targetMode === "node" ? form.targetNodes : [],
      commands: form.commands,
      concurrency: form.concurrency,
      keySpace: form.keySpace,
      valueSize: form.valueSize,
      ttlSeconds: form.ttlSeconds,
      durationSeconds: form.stopBy === "duration" ? form.durationMinutes * 60 : 0,
      totalRequests: form.stopBy === "requests" ? form.totalRequests : 0,
      pipeline: form.pipeline,
      hotspot: form.hotspot,
      readWeight: form.readWeight,
      writeWeight: form.writeWeight,
      cleanup: form.cleanup
    })
    runningTaskId.value = data?.taskId ?? null
    qpsSeries.value = []
    startPolling()
    ElMessage.success("压测已启动")
  } finally {
    starting.value = false
  }
}

async function handleStop() {
  if (!runningTaskId.value) return
  await stopBenchmarkApi(runningTaskId.value)
  ElMessage.success("已发送停止指令")
}

function startPolling() {
  stopPolling()
  timer = setInterval(pollProgress, 1000)
}

function stopPolling() {
  if (timer) clearInterval(timer)
  timer = null
}

async function pollProgress() {
  if (!runningTaskId.value) return
  const { data } = await getBenchmarkProgressApi(runningTaskId.value)
  progress.value = data ?? null
  if (data) {
    qpsSeries.value.push({ t: data.elapsedSeconds, qps: data.currentQps, p99: data.p99Ms })
    if (qpsSeries.value.length > 120) qpsSeries.value.shift()
  }
  if (data && data.status !== "RUNNING") {
    stopPolling()
    runningTaskId.value = null
    loadHistory()
  }
}

const maxQps = computed(() => Math.max(1, ...qpsSeries.value.map(p => p.qps)))

const detailVisible = ref(false)
const detailRow = ref<BenchmarkResult | null>(null)

function openDetail(row: BenchmarkResult) {
  detailRow.value = row
  detailVisible.value = true
}

async function handleDelete(row: BenchmarkResult) {
  await ElMessageBox.confirm(
    `确认删除 ${row.appName} 在 ${row.startTime} 的这条压测记录？`,
    "删除压测记录",
    { type: "warning" }
  )
  await deleteBenchmarkApi(row.id)
  ElMessage.success("已删除")
  loadHistory()
}

/** 参数快照按人话展开；未知字段直接原样列出，免得新增参数在详情里凭空消失 */
const detailOptions = computed(() => {
  const o = detailRow.value?.options
  if (!o) return []
  const labels: Record<string, string> = {
    concurrency: "并发数",
    keySpace: "键空间",
    valueSize: "value 大小(字节)",
    ttlSeconds: "TTL(秒)",
    durationSeconds: "压测时长(秒)",
    totalRequests: "总请求数上限",
    pipeline: "Pipeline 深度",
    hotspot: "key 分布",
    readWeight: "读权重",
    writeWeight: "写权重",
    cleanup: "结束后清理",
    commands: "压测命令",
    targetNodes: "定向节点"
  }
  const format = (key: string, value: any) => {
    if (key === "hotspot") return value ? "热点(约两成 key 承担八成访问)" : "均匀随机"
    if (key === "cleanup") return value ? "是" : "否"
    if (key === "durationSeconds") return value > 0 ? `${value} 秒（${(value / 60).toFixed(1)} 分钟）` : "未按时长终止"
    if (key === "totalRequests") return value > 0 ? Number(value).toLocaleString() : "未按请求数终止"
    if (Array.isArray(value)) return value.length ? value.join(", ") : "整集群"
    return String(value)
  }
  return Object.keys(labels)
    .filter(k => o[k] !== undefined && o[k] !== null)
    .map(k => ({ label: labels[k], value: format(k, o[k]) }))
})

const detailCommandRows = computed(() => {
  const counts = detailRow.value?.commandStats?.counts ?? {}
  const avg = detailRow.value?.commandStats?.avgMs ?? {}
  return Object.entries(counts)
    .map(([name, count]) => ({ name, count, avgMs: (avg[name] ?? 0) / 1000 }))
    .sort((a, b) => b.count - a.count)
})

function statusTag(status?: string) {
  if (status === "FINISHED") return "success"
  if (status === "RUNNING") return "warning"
  if (status === "FAILED") return "danger"
  return "info"
}

onMounted(() => {
  loadCatalog()
  loadHistory()
})
onBeforeUnmount(stopPolling)
</script>

<template>
  <div class="benchmark-tab">
    <el-alert type="info" :closable="false" show-icon class="benchmark-tab__notice">
      <template #title>
        压测会向目标实例写入真实数据。所有键使用 <b>cc:bench:{taskId}:</b> 前缀并强制 TTL，结束后自动清理。
        平台自身即压测机，结果里的「平台CPU」若接近饱和，说明瓶颈可能在压测端而非 Redis。
      </template>
    </el-alert>

    <el-card shadow="never" class="benchmark-tab__form">
      <template #header>
        <span>压测参数</span>
      </template>

      <el-form label-width="112px">
        <el-row :gutter="16">
          <el-col :span="8">
            <el-form-item label="目标集群">
              <el-select v-model="form.appId" filterable clearable placeholder="选择集群" style="width: 100%" @change="handleAppChange">
                <el-option v-for="app in props.apps" :key="app.appId" :label="app.appName" :value="app.appId" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="16">
            <el-form-item v-if="isCluster" label="压测范围">
              <el-radio-group v-model="form.targetMode">
                <el-radio label="cluster">整集群</el-radio>
                <el-radio label="node">指定节点</el-radio>
              </el-radio-group>
              <span class="benchmark-tab__hint">
                指定节点用 hashtag 定向，key 会集中在该节点的少数 slot 上——测的是单节点上限，不是集群吞吐
              </span>
            </el-form-item>
          </el-col>
        </el-row>

        <el-form-item v-if="isCluster && form.targetMode === 'node'" label="选择节点">
          <el-checkbox-group v-model="form.targetNodes" v-loading="loadingTargets">
            <el-checkbox v-for="t in targets" :key="t.hostPort" :label="t.hostPort">
              {{ t.hostPort }}
              <span class="benchmark-tab__hint">slot {{ t.slotRange }}（{{ t.slotCount }} 个）</span>
            </el-checkbox>
          </el-checkbox-group>
        </el-form-item>

        <el-form-item label="压测命令">
          <div class="benchmark-tab__commands">
            <div v-for="(items, group) in catalog" :key="group" class="benchmark-tab__group">
              <div class="benchmark-tab__group-name">
                {{ group }}
              </div>
              <div class="benchmark-tab__group-items">
                <el-check-tag
                  v-for="c in items"
                  :key="c.name"
                  :checked="form.commands.includes(c.name)"
                  :class="c.kind === 'WRITE' ? 'is-write' : 'is-read'"
                  @change="toggleCommand(c.name)"
                >
                  {{ c.name }}
                </el-check-tag>
              </div>
            </div>
          </div>
        </el-form-item>

        <el-row :gutter="16">
          <el-col :span="6">
            <el-form-item label="并发数">
              <el-input-number v-model="form.concurrency" :min="1" controls-position="right" style="width: 100%" />
              <span v-if="concurrencyWarn" class="benchmark-tab__warn">⚠ 建议不超过 200</span>
            </el-form-item>
          </el-col>
          <el-col :span="6">
            <el-form-item label="键空间">
              <el-input-number v-model="form.keySpace" :min="1" controls-position="right" style="width: 100%" />
              <span v-if="keySpaceWarn" class="benchmark-tab__warn">⚠ 键空间较大，清理耗时会变长</span>
            </el-form-item>
          </el-col>
          <el-col :span="6">
            <el-form-item label="value 大小">
              <el-input-number v-model="form.valueSize" :min="1" controls-position="right" style="width: 100%" />
              <span class="benchmark-tab__hint">字节</span>
            </el-form-item>
          </el-col>
          <el-col :span="6">
            <el-form-item label="TTL">
              <el-input-number v-model="form.ttlSeconds" :min="1" controls-position="right" style="width: 100%" />
              <span class="benchmark-tab__hint">秒，强制</span>
            </el-form-item>
          </el-col>
        </el-row>

        <el-row :gutter="16">
          <el-col :span="6">
            <el-form-item label="终止条件">
              <el-select v-model="form.stopBy" style="width: 100%">
                <el-option label="按时长" value="duration" />
                <el-option label="按请求数" value="requests" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="6">
            <el-form-item :label="form.stopBy === 'duration' ? '压测时长' : '总请求数'">
              <el-input-number
                v-if="form.stopBy === 'duration'"
                v-model="form.durationMinutes" :min="1" controls-position="right" style="width: 100%"
              />
              <el-input-number v-else v-model="form.totalRequests" :min="1" :step="100000" controls-position="right" style="width: 100%" />
              <span v-if="form.stopBy === 'duration'" class="benchmark-tab__hint">分钟，最低 1 分钟</span>
              <span v-else class="benchmark-tab__hint">次</span>
              <span v-if="durationWarn" class="benchmark-tab__warn">⚠ 超过 10 分钟</span>
            </el-form-item>
          </el-col>
          <el-col :span="6">
            <el-form-item label="Pipeline">
              <el-input-number v-model="form.pipeline" :min="1" :max="512" controls-position="right" style="width: 100%" />
              <span class="benchmark-tab__hint">P=1 反映真实往返延迟</span>
            </el-form-item>
          </el-col>
          <el-col :span="6">
            <el-form-item label="key 分布">
              <el-radio-group v-model="form.hotspot">
                <el-radio :label="false">均匀</el-radio>
                <el-radio :label="true">热点</el-radio>
              </el-radio-group>
            </el-form-item>
          </el-col>
        </el-row>

        <el-row :gutter="16">
          <el-col :span="12">
            <el-form-item label="读写比例">
              <div class="benchmark-tab__ratio">
                <el-input-number v-model="form.readWeight" :min="0" :disabled="!hasReadCommand" controls-position="right" />
                <span>读 : 写</span>
                <el-input-number v-model="form.writeWeight" :min="0" :disabled="!hasWriteCommand" controls-position="right" />
                <span v-if="!hasReadCommand || !hasWriteCommand" class="benchmark-tab__hint">
                  只勾选了单侧命令，比例不生效
                </span>
              </div>
            </el-form-item>
          </el-col>
          <el-col :span="6">
            <el-form-item label="结束后清理">
              <el-switch v-model="form.cleanup" />
            </el-form-item>
          </el-col>
          <el-col :span="6">
            <el-form-item>
              <el-button type="primary" :loading="starting" :disabled="!!runningTaskId" @click="handleStart">
                开始压测
              </el-button>
              <el-button type="danger" plain :disabled="!runningTaskId" @click="handleStop">
                停止
              </el-button>
            </el-form-item>
          </el-col>
        </el-row>
      </el-form>
    </el-card>

    <el-card v-if="progress" shadow="never" class="benchmark-tab__live">
      <template #header>
        <span>运行中</span>
        <el-tag :type="statusTag(progress.status)" size="small" class="benchmark-tab__status">{{ progress.status }}</el-tag>
        <span class="benchmark-tab__hint">已运行 {{ progress.elapsedSeconds }}s</span>
      </template>
      <div class="benchmark-tab__kpis">
        <div class="benchmark-tab__kpi">
          <div class="benchmark-tab__kpi-label">瞬时 QPS</div>
          <div class="benchmark-tab__kpi-value">{{ progress.currentQps.toLocaleString() }}</div>
        </div>
        <div class="benchmark-tab__kpi">
          <div class="benchmark-tab__kpi-label">平均 QPS</div>
          <div class="benchmark-tab__kpi-value">{{ progress.avgQps.toLocaleString() }}</div>
        </div>
        <div class="benchmark-tab__kpi">
          <div class="benchmark-tab__kpi-label">总请求</div>
          <div class="benchmark-tab__kpi-value">{{ progress.totalRequests.toLocaleString() }}</div>
        </div>
        <div class="benchmark-tab__kpi">
          <div class="benchmark-tab__kpi-label">P99</div>
          <div class="benchmark-tab__kpi-value">{{ progress.p99Ms.toFixed(2) }}<small>ms</small></div>
        </div>
        <div class="benchmark-tab__kpi">
          <div class="benchmark-tab__kpi-label">错误</div>
          <div class="benchmark-tab__kpi-value" :class="{ 'is-bad': progress.errorCount > 0 }">
            {{ progress.errorCount.toLocaleString() }}
          </div>
        </div>
        <div class="benchmark-tab__kpi">
          <div class="benchmark-tab__kpi-label">平台 CPU</div>
          <div class="benchmark-tab__kpi-value">{{ progress.clientCpuPercent.toFixed(1) }}<small>%</small></div>
        </div>
      </div>
      <!-- 简易柱状曲线：压测本身已经很吃 CPU，这里不再引入图表库渲染 -->
      <div class="benchmark-tab__spark">
        <div
          v-for="(p, i) in qpsSeries"
          :key="i"
          class="benchmark-tab__spark-bar"
          :style="{ height: `${Math.max(2, p.qps * 100 / maxQps)}%` }"
          :title="`${p.t}s  QPS ${p.qps.toLocaleString()}  P99 ${p.p99.toFixed(2)}ms`"
        />
      </div>
    </el-card>

    <el-card shadow="never">
      <template #header>
        <span>历史结果</span>
        <span class="benchmark-tab__hint">参数与结果一同留存，可纵向对比配置调整前后的差异</span>
      </template>
      <el-table :data="history" size="small" border stripe>
        <el-table-column prop="startTime" label="开始时间" width="165" />
        <el-table-column prop="appName" label="集群" min-width="130" show-overflow-tooltip />
        <el-table-column prop="targetDesc" label="目标" min-width="150" show-overflow-tooltip />
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="statusTag(row.status)" size="small">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="参数" min-width="190" show-overflow-tooltip>
          <template #default="{ row }">
            <span v-if="row.options">
              并发{{ row.options.concurrency }} · P={{ row.options.pipeline }} ·
              键空间{{ row.options.keySpace }} · {{ row.options.hotspot ? "热点" : "均匀" }}
            </span>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column label="QPS" width="100" align="right">
          <template #default="{ row }">{{ row.qps.toLocaleString() }}</template>
        </el-table-column>
        <el-table-column label="P99" width="88" align="right">
          <template #default="{ row }">{{ row.p99Ms.toFixed(2) }}ms</template>
        </el-table-column>
        <el-table-column label="P95" width="88" align="right">
          <template #default="{ row }">{{ row.p95Ms.toFixed(2) }}ms</template>
        </el-table-column>
        <el-table-column label="max" width="90" align="right">
          <template #default="{ row }">{{ row.maxMs.toFixed(2) }}ms</template>
        </el-table-column>
        <el-table-column label="错误" width="80" align="right">
          <template #default="{ row }">
            <span :class="{ 'is-bad': row.errorCount > 0 }">{{ row.errorCount.toLocaleString() }}</span>
          </template>
        </el-table-column>
        <el-table-column label="平台CPU" width="92" align="right">
          <template #default="{ row }">{{ row.clientCpuPercent.toFixed(1) }}%</template>
        </el-table-column>
        <el-table-column label="操作" width="130" fixed="right">
          <template #default="{ row }">
            <el-button type="primary" size="small" @click="openDetail(row)">详情</el-button>
            <el-button type="danger" plain size="small" @click="handleDelete(row)">删除</el-button>
          </template>
        </el-table-column>
        <template #empty>暂无压测记录</template>
      </el-table>
    </el-card>

    <el-drawer v-model="detailVisible" title="压测详情" size="640px" destroy-on-close>
      <template v-if="detailRow">
        <h4 class="benchmark-tab__detail-title">基本信息</h4>
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="集群">{{ detailRow.appName }}</el-descriptions-item>
          <el-descriptions-item label="压测目标">{{ detailRow.targetDesc }}</el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag :type="statusTag(detailRow.status)" size="small">{{ detailRow.status }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="操作人">{{ detailRow.userName || "-" }}</el-descriptions-item>
          <el-descriptions-item label="开始">{{ detailRow.startTime || "-" }}</el-descriptions-item>
          <el-descriptions-item label="结束">{{ detailRow.endTime || "-" }}</el-descriptions-item>
        </el-descriptions>

        <h4 class="benchmark-tab__detail-title">配置参数</h4>
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item v-for="item in detailOptions" :key="item.label" :label="item.label">
            {{ item.value }}
          </el-descriptions-item>
        </el-descriptions>

        <h4 class="benchmark-tab__detail-title">结果</h4>
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="总请求">{{ detailRow.totalRequests.toLocaleString() }}</el-descriptions-item>
          <el-descriptions-item label="QPS">{{ detailRow.qps.toLocaleString() }}</el-descriptions-item>
          <el-descriptions-item label="平均延迟">{{ detailRow.avgMs.toFixed(3) }} ms</el-descriptions-item>
          <el-descriptions-item label="P50">{{ detailRow.p50Ms.toFixed(2) }} ms</el-descriptions-item>
          <el-descriptions-item label="P95">{{ detailRow.p95Ms.toFixed(2) }} ms</el-descriptions-item>
          <el-descriptions-item label="P99">{{ detailRow.p99Ms.toFixed(2) }} ms</el-descriptions-item>
          <el-descriptions-item label="最大延迟">{{ detailRow.maxMs.toFixed(2) }} ms</el-descriptions-item>
          <el-descriptions-item label="平台 CPU">{{ detailRow.clientCpuPercent.toFixed(1) }} %</el-descriptions-item>
        </el-descriptions>
        <div class="benchmark-tab__hint benchmark-tab__detail-note">
          分位数由延迟直方图给出，是「不超过该值」的上界估计；Pipeline &gt; 1 时单条延迟为整批平摊值。
        </div>

        <h4 class="benchmark-tab__detail-title">逐命令统计</h4>
        <el-table :data="detailCommandRows" size="small" border max-height="260">
          <el-table-column prop="name" label="命令" min-width="120" />
          <el-table-column label="次数" min-width="120" align="right">
            <template #default="{ row }">{{ row.count.toLocaleString() }}</template>
          </el-table-column>
          <el-table-column label="平均耗时" min-width="120" align="right">
            <template #default="{ row }">{{ row.avgMs.toFixed(3) }} ms</template>
          </el-table-column>
          <template #empty>无命令明细</template>
        </el-table>

        <template v-if="detailRow.errorStats && Object.keys(detailRow.errorStats).length">
          <h4 class="benchmark-tab__detail-title">错误分类</h4>
          <el-descriptions :column="1" border size="small">
            <el-descriptions-item v-for="(count, type) in detailRow.errorStats" :key="type" :label="String(type)">
              {{ count }}
            </el-descriptions-item>
          </el-descriptions>
        </template>

        <template v-if="detailRow.errorMsg">
          <h4 class="benchmark-tab__detail-title">失败原因</h4>
          <pre class="benchmark-tab__error">{{ detailRow.errorMsg }}</pre>
        </template>
      </template>
    </el-drawer>
  </div>
</template>

<style lang="scss" scoped>
.benchmark-tab__notice {
  margin-bottom: 12px;
}

.benchmark-tab__form,
.benchmark-tab__live {
  margin-bottom: 12px;
}

.benchmark-tab__hint {
  margin-left: 8px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.benchmark-tab__warn {
  margin-left: 8px;
  font-size: 12px;
  color: var(--el-color-warning);
}

.benchmark-tab__commands {
  display: flex;
  flex-direction: column;
  gap: 6px;
  width: 100%;
}

.benchmark-tab__group {
  display: flex;
  align-items: center;
  gap: 10px;
}

.benchmark-tab__group-name {
  width: 52px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.benchmark-tab__group-items {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.benchmark-tab__group-items :deep(.el-check-tag.is-write) {
  border-left: 3px solid var(--el-color-warning);
}

.benchmark-tab__group-items :deep(.el-check-tag.is-read) {
  border-left: 3px solid var(--el-color-success);
}

.benchmark-tab__ratio {
  display: flex;
  align-items: center;
  gap: 8px;
}

.benchmark-tab__status {
  margin-left: 8px;
}

.benchmark-tab__kpis {
  display: grid;
  grid-template-columns: repeat(6, minmax(0, 1fr));
  gap: 12px;
  margin-bottom: 12px;
}

.benchmark-tab__kpi-label {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.benchmark-tab__kpi-value {
  font-size: 20px;
  font-weight: 600;

  small {
    margin-left: 2px;
    font-size: 12px;
    font-weight: 400;
  }
}

.is-bad {
  color: var(--el-color-danger);
}

.benchmark-tab__spark {
  display: flex;
  align-items: flex-end;
  gap: 1px;
  height: 80px;
  padding: 4px;
  background: var(--el-fill-color-light);
  border-radius: 4px;
}

.benchmark-tab__detail-title {
  margin: 16px 0 8px;
  font-size: 14px;
}

.benchmark-tab__detail-note {
  display: block;
  margin: 8px 0 0;
}

.benchmark-tab__error {
  margin: 0;
  padding: 10px;
  font-size: 12px;
  white-space: pre-wrap;
  word-break: break-all;
  background: var(--el-fill-color-light);
  border-radius: 4px;
}

.benchmark-tab__spark-bar {
  flex: 1 1 auto;
  min-width: 2px;
  background: var(--el-color-primary);
  border-radius: 1px 1px 0 0;
}
</style>
