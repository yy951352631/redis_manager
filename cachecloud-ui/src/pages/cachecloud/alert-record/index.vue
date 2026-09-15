<script lang="ts" setup>
import type { AlertRecordItem } from "@/api/cachecloud"
import { useAutoQuery } from "@@/composables/useAutoQuery"
import { Refresh, Search } from "@element-plus/icons-vue"
import { getAlertRecordsApi } from "@/api/cachecloud"

const loading = ref(false)
const items = ref<AlertRecordItem[]>([])
const total = ref(0)
const importantLevels = ref<{ value: number, label: string }[]>([])
const apps = ref<{ appId: number, appName: string }[]>([])
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
  const [start, end] = shiftRange(60 * 60 * 1000)
  return [fmt(start), fmt(end)]
}

const query = reactive({
  importantLevel: undefined as number | undefined,
  appId: undefined as number | undefined,
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
  loading.value = true
  try {
    const { data } = await getAlertRecordsApi({
      importantLevel: query.importantLevel,
      appId: query.appId,
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
    apps.value = data?.apps ?? []
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
  query.appId = undefined
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

onMounted(fetchList)
</script>

<template>
  <div class="alert-record-page">
    <el-card shadow="never" class="alert-record-page__filter">
      <div class="alert-record-page__filter-row">
        <el-select v-model="query.importantLevel" placeholder="重要程度" clearable style="width: 130px">
          <el-option v-for="l in importantLevels" :key="l.value" :label="l.label" :value="l.value" />
        </el-select>
        <el-select v-model="query.appId" placeholder="集群名称" clearable filterable style="width: 200px">
          <el-option v-for="a in apps" :key="a.appId" :label="a.appName" :value="a.appId" />
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
          class="alert-record-page__range"
        />
        <el-input v-model="query.keyword" placeholder="标题 / 内容关键字" clearable style="width: 220px" />
        <el-button type="primary" :icon="Search" @click="handleSearch">
          查询
        </el-button>
        <el-button :icon="Refresh" @click="handleReset">
          重置
        </el-button>
      </div>
    </el-card>

    <el-card shadow="never">
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
        <el-table-column label="集群" width="180" show-overflow-tooltip>
          <template #default="{ row }">
            <router-link v-if="row.appId" :to="`/app/detail/${row.appId}`">
              {{ row.appName || `应用 ${row.appId}` }}
            </router-link>
            <span v-else>-</span>
          </template>
        </el-table-column>
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
          <el-empty description="暂无报警记录" :image-size="80" />
        </template>
      </el-table>
      <el-pagination
        v-model:current-page="query.pageNo"
        :page-size="query.pageSize"
        :total="total"
        layout="total, prev, pager, next"
        class="alert-record-page__pager"
        @current-change="fetchList"
      />
    </el-card>

    <el-drawer v-model="detailVisible" title="报警详情" size="620px">
      <el-descriptions v-if="detail" :column="1" border size="small">
        <el-descriptions-item label="报警时间">
          {{ detail.createTime }}
        </el-descriptions-item>
        <el-descriptions-item label="重要程度">
          {{ detail.importantLevelDesc }}
        </el-descriptions-item>
        <el-descriptions-item label="集群">
          {{ detail.appName || (detail.appId ? `应用 ${detail.appId}` : "-") }}
        </el-descriptions-item>
        <el-descriptions-item label="节点">
          {{ targetLabel(detail) }}
        </el-descriptions-item>
        <el-descriptions-item label="标题">
          {{ detail.title }}
        </el-descriptions-item>
      </el-descriptions>
      <h4 class="alert-record-page__detail-title">
        报警内容
      </h4>
      <pre class="alert-record-page__content">{{ detail?.content || "-" }}</pre>
    </el-drawer>
  </div>
</template>

<style lang="scss" scoped>
.alert-record-page__filter {
  margin-bottom: 12px;
}

.alert-record-page__filter-row {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
  // 各控件保持自身宽度，行内自然靠左排布，不做等分拉伸
  justify-content: flex-start;
}

// el-date-editor 默认按 --el-date-editor-width 撑开，这里按内容实际长度固定。
// 360px 与 filter-bar__range 一致：同为 datetimerange + YYYY-MM-DD HH:mm:ss，
// 该宽度已在集群/节点统计页验证过，刚好容下两侧时间戳且不留多余空白。
.alert-record-page__range {
  flex: 0 0 auto;
  width: 360px;
  max-width: 100%;

  &.el-date-editor {
    --el-date-editor-width: 360px;
  }
}

.alert-record-page__pager {
  margin-top: 12px;
  justify-content: flex-end;
}

.alert-record-page__detail-title {
  margin: 16px 0 8px;
  font-size: 14px;
}

.alert-record-page__content {
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
