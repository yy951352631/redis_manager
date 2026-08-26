<script lang="ts" setup>
import type { AlertRecordItem, AppDetail } from "@/api/cachecloud"
import { useAutoQuery } from "@@/composables/useAutoQuery"
import { Refresh, Search } from "@element-plus/icons-vue"
import { getAlertRecordsApi } from "@/api/cachecloud"

// 与「报警记录」页共用同一个接口，这里固定 appId，只看当前集群的告警。
// 因此不提供集群筛选框，其余筛选项保持一致的交互习惯。
// detail 由集群详情页统一下发，本组件用不到，但要声明成 prop，
// 否则会以 fallthrough attribute 的形式落到根元素上
const props = defineProps<{ appId: number, detail?: AppDetail | null }>()

const loading = ref(false)
const items = ref<AlertRecordItem[]>([])
const total = ref(0)
const importantLevels = ref<{ value: number, label: string }[]>([])
const detail = ref<AlertRecordItem | null>(null)
const detailVisible = ref(false)

function shiftRange(ms: number): [Date, Date] {
  const end = new Date()
  return [new Date(end.getTime() - ms), end]
}
const rangeShortcuts = [
  { text: "最近 15 分钟", value: () => shiftRange(15 * 60 * 1000) },
  { text: "最近 30 分钟", value: () => shiftRange(30 * 60 * 1000) },
  { text: "最近 1 小时", value: () => shiftRange(60 * 60 * 1000) },
  { text: "最近 6 小时", value: () => shiftRange(6 * 60 * 60 * 1000) },
  { text: "最近 24 小时", value: () => shiftRange(24 * 60 * 60 * 1000) },
  { text: "最近 3 天", value: () => shiftRange(3 * 24 * 60 * 60 * 1000) },
  { text: "最近 7 天", value: () => shiftRange(7 * 24 * 60 * 60 * 1000) }
]
function fmt(d: Date) {
  const p = (n: number) => String(n).padStart(2, "0")
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
}
function defaultRange(): [string, string] {
  const [start, end] = shiftRange(24 * 60 * 60 * 1000)
  return [fmt(start), fmt(end)]
}

const query = reactive({
  importantLevel: undefined as number | undefined,
  ip: "",
  keyword: "",
  pageNo: 1,
  pageSize: 20
})
const timeRange = ref<[string, string] | null>(defaultRange())

/** 重要程度：0 一般 / 1 重要 / 2 紧急 */
const LEVEL_TAG: Record<number, "info" | "warning" | "danger"> = {
  0: "info",
  1: "warning",
  2: "danger"
}

async function fetchList() {
  if (!props.appId) return
  loading.value = true
  try {
    const { data } = await getAlertRecordsApi({
      appId: props.appId,
      importantLevel: query.importantLevel,
      ip: query.ip || undefined,
      keyword: query.keyword || undefined,
      startTime: timeRange.value?.[0] || undefined,
      endTime: timeRange.value?.[1] || undefined,
      pageNo: query.pageNo,
      pageSize: query.pageSize
    })
    items.value = data?.items ?? []
    total.value = data?.totalCount ?? 0
    importantLevels.value = data?.importantLevels ?? []
  } finally {
    loading.value = false
  }
}

function handleSearch() {
  query.pageNo = 1
  fetchList()
}

// 选完时间区间即自动查询
const { silent: silentRange } = useAutoQuery(timeRange, () => handleSearch())

function handleReset() {
  query.importantLevel = undefined
  query.ip = ""
  query.keyword = ""
  // 重置后统一查一次，恢复默认时间区间不再单独触发一次自动查询
  silentRange(() => {
    timeRange.value = defaultRange()
  })
  handleSearch()
}

function openDetail(row: AlertRecordItem) {
  detail.value = row
  detailVisible.value = true
}

function targetLabel(row: AlertRecordItem) {
  if (row.ip && row.port) return `${row.ip}:${row.port}`
  return row.ip || "-"
}

// 集群详情页切换集群时组件可能被复用，appId 变化要重新拉取
watch(() => props.appId, () => handleSearch(), { immediate: true })
</script>

<template>
  <div class="app-alert-record-tab">
    <div class="app-alert-record-tab__filter-row">
      <el-select v-model="query.importantLevel" placeholder="重要程度" clearable style="width: 130px">
        <el-option v-for="l in importantLevels" :key="l.value" :label="l.label" :value="l.value" />
      </el-select>
      <el-input v-model="query.ip" placeholder="节点 IP" clearable style="width: 150px" />
      <el-date-picker
        v-model="timeRange"
        type="datetimerange"
        format="YYYY-MM-DD HH:mm:ss"
        value-format="YYYY-MM-DD HH:mm:ss"
        :shortcuts="rangeShortcuts"
        range-separator="至"
        start-placeholder="开始时间"
        end-placeholder="结束时间"
        class="app-alert-record-tab__range"
      />
      <el-input v-model="query.keyword" placeholder="标题 / 内容关键字" clearable style="width: 220px" />
      <el-button type="primary" :icon="Search" @click="handleSearch">
        查询
      </el-button>
      <el-button :icon="Refresh" @click="handleReset">
        重置
      </el-button>
    </div>

    <el-table v-loading="loading" :data="items" stripe border size="small">
      <el-table-column prop="createTime" label="报警时间" width="165" />
      <el-table-column label="重要程度" width="100">
        <template #default="{ row }">
          <el-tag :type="LEVEL_TAG[row.importantLevel] || 'info'" size="small">
            {{ row.importantLevelDesc }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="title" label="报警标题" min-width="240" show-overflow-tooltip />
      <el-table-column label="节点" width="160">
        <template #default="{ row }">
          <router-link v-if="row.instanceId" :to="`/external/redis/detail/${row.instanceId}`">
            {{ targetLabel(row) }}
          </router-link>
          <span v-else>{{ targetLabel(row) }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="content" label="报警内容" min-width="320" show-overflow-tooltip />
      <el-table-column label="详情" width="80" fixed="right">
        <template #default="{ row }">
          <el-link type="primary" :underline="false" @click="openDetail(row)">
            查看
          </el-link>
        </template>
      </el-table-column>
      <template #empty>
        <el-empty description="当前时间段该集群暂无报警记录" :image-size="80" />
      </template>
    </el-table>

    <el-pagination
      v-model:current-page="query.pageNo"
      :page-size="query.pageSize"
      :total="total"
      layout="total, prev, pager, next"
      class="app-alert-record-tab__pager"
      @current-change="fetchList"
    />

    <el-drawer v-model="detailVisible" title="报警详情" size="620px">
      <el-descriptions v-if="detail" :column="1" border size="small">
        <el-descriptions-item label="报警时间">
          {{ detail.createTime }}
        </el-descriptions-item>
        <el-descriptions-item label="重要程度">
          {{ detail.importantLevelDesc }}
        </el-descriptions-item>
        <el-descriptions-item label="节点">
          {{ targetLabel(detail) }}
        </el-descriptions-item>
        <el-descriptions-item label="标题">
          {{ detail.title }}
        </el-descriptions-item>
      </el-descriptions>
      <h4 class="app-alert-record-tab__detail-title">
        报警内容
      </h4>
      <pre class="app-alert-record-tab__content">{{ detail?.content || "-" }}</pre>
    </el-drawer>
  </div>
</template>

<style lang="scss" scoped>
.app-alert-record-tab__filter-row {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
  // 各控件保持自身宽度，行内自然靠左排布，不做等分拉伸
  justify-content: flex-start;
  margin-bottom: 12px;
}

// 与报警记录页一致：按内容实际长度固定，不随容器伸缩
.app-alert-record-tab__range {
  flex: 0 0 auto;
  width: 360px;
  max-width: 100%;

  &.el-date-editor {
    --el-date-editor-width: 360px;
  }
}

.app-alert-record-tab__pager {
  margin-top: 12px;
  justify-content: flex-end;
}

.app-alert-record-tab__detail-title {
  margin: 16px 0 8px;
  font-size: 14px;
}

.app-alert-record-tab__content {
  margin: 0;
  padding: 12px;
  font-size: 12px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-all;
  background: var(--el-fill-color-light);
  border-radius: 4px;
}
</style>
