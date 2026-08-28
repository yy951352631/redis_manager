<script lang="ts" setup>
import type { OperationAuditItem } from "@/api/cachecloud"
import { Refresh, Search } from "@element-plus/icons-vue"
import { getOperationAuditsApi } from "@/api/cachecloud"
import { useAutoQuery } from "@@/composables/useAutoQuery"
import { formatAuditHandler, hasAuditHandlerText } from "@/common/utils/audit-handler-meta"

const loading = ref(false)
const items = ref<OperationAuditItem[]>([])
const total = ref(0)
const modules = ref<string[]>([])
const detail = ref<OperationAuditItem | null>(null)
const detailVisible = ref(false)

function shiftRange(ms: number): [Date, Date] {
  const end = new Date()
  return [new Date(end.getTime() - ms), end]
}
const DAY_MS = 24 * 60 * 60 * 1000
const rangeShortcuts = [
  { text: "最近 1 天", value: () => shiftRange(DAY_MS) },
  { text: "最近 3 天", value: () => shiftRange(3 * DAY_MS) },
  { text: "最近 7 天", value: () => shiftRange(7 * DAY_MS) }
]

/** 查询条件；时间区间由 el-date-picker 直接给出 yyyy-MM-dd HH:mm:ss */
const query = reactive({
  userName: "",
  module: "",
  keyword: "",
  success: undefined as number | undefined,
  pageNo: 1,
  pageSize: 20
})
const timeRange = ref<[string, string] | null>(null)

const METHOD_TAG: Record<string, "success" | "warning" | "danger" | "info"> = {
  POST: "success",
  PUT: "warning",
  DELETE: "danger",
  PATCH: "info"
}

async function fetchList() {
  loading.value = true
  try {
    const { data } = await getOperationAuditsApi({
      userName: query.userName || undefined,
      module: query.module || undefined,
      keyword: query.keyword || undefined,
      success: query.success,
      startTime: timeRange.value?.[0] || undefined,
      endTime: timeRange.value?.[1] || undefined,
      pageNo: query.pageNo,
      pageSize: query.pageSize
    })
    items.value = data?.items ?? []
    total.value = data?.totalCount ?? 0
    modules.value = data?.modules ?? []
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
  query.userName = ""
  query.module = ""
  query.keyword = ""
  query.success = undefined
  // 重置后统一查一次，清空时间区间不再单独触发一次自动查询
  silentRange(() => { timeRange.value = null })
  handleSearch()
}

function openDetail(row: OperationAuditItem) {
  detail.value = row
  detailVisible.value = true
}

/** 参数是后端序列化过的 JSON 字符串，展示时尽量格式化 */
function prettyParams(params?: string) {
  if (!params) return "-"
  try {
    const parsed = JSON.parse(params)
    if (typeof parsed._body === "string") {
      try {
        parsed._body = JSON.parse(parsed._body)
      } catch {
        // 非 JSON 请求体，保持原样
      }
    }
    return JSON.stringify(parsed, null, 2)
  } catch {
    return params
  }
}

onMounted(fetchList)
</script>

<template>
  <div class="audit-page">
    <el-card shadow="never" class="audit-page__filter">
      <div class="audit-page__filter-row">
        <el-input v-model="query.userName" placeholder="操作人" clearable style="width: 140px" />
        <el-select v-model="query.module" placeholder="业务域" clearable style="width: 150px">
          <el-option v-for="m in modules" :key="m" :label="m" :value="m" />
        </el-select>
        <el-select v-model="query.success" placeholder="结果" clearable style="width: 110px">
          <el-option label="成功" :value="1" />
          <el-option label="失败" :value="0" />
        </el-select>
        <el-date-picker
          v-model="timeRange"
          type="datetimerange"
          value-format="YYYY-MM-DD HH:mm:ss"
          :shortcuts="rangeShortcuts"
          range-separator="至"
          start-placeholder="开始时间"
          end-placeholder="结束时间"
          style="width: 360px"
        />
        <el-input v-model="query.keyword" placeholder="路径 / 方法 / 参数关键字" clearable style="width: 220px" />
        <el-button type="primary" :icon="Search" @click="handleSearch">查询</el-button>
        <el-button :icon="Refresh" @click="handleReset">重置</el-button>
      </div>
    </el-card>

    <el-card shadow="never">
      <el-table v-loading="loading" :data="items" stripe border size="small">
        <el-table-column prop="createTime" label="操作时间" width="165" />
        <el-table-column prop="userName" label="操作人" width="110">
          <template #default="{ row }">
            {{ row.userName || "-" }}
          </template>
        </el-table-column>
        <el-table-column prop="module" label="业务域" width="110" />
        <el-table-column label="方法" width="90">
          <template #default="{ row }">
            <el-tag :type="METHOD_TAG[row.httpMethod] || 'info'" size="small">{{ row.httpMethod }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="requestUri" label="请求路径" min-width="240" show-overflow-tooltip />
        <el-table-column label="处理方法" min-width="200" show-overflow-tooltip>
          <template #default="{ row }">
            <!-- 映射不到的原样显示并弱化，提醒新接口还没登记中文简述 -->
            <span :class="{ 'audit-page__handler-raw': !hasAuditHandlerText(row.handler) }">
              {{ formatAuditHandler(row.handler) }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="对象" min-width="200" show-overflow-tooltip>
          <template #default="{ row }">
            <!--
              文案由服务端统一算好：集群名 / 节点 ip:port / 迁移的「源 → 目标」。
              不做跳转：审计记的是当时那一刻，而链接指向的是集群此刻的样子，
              集群若已删除更是跳不过去；这里只作陈述。
            -->
            <span>{{ row.objectLabel || (row.appId ? `集群 ${row.appId}` : "-") }}</span>
          </template>
        </el-table-column>
        <el-table-column label="结果" width="90">
          <template #default="{ row }">
            <el-tag :type="row.success ? 'success' : 'danger'" size="small">
              {{ row.success ? "成功" : "失败" }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="costMs" label="耗时(ms)" width="95" />
        <el-table-column prop="clientIp" label="来源IP" width="130" />
        <el-table-column label="详情" width="80" fixed="right">
          <template #default="{ row }">
            <el-link type="primary" :underline="false" @click="openDetail(row)">查看</el-link>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination
        v-model:current-page="query.pageNo"
        :page-size="query.pageSize"
        :total="total"
        layout="total, prev, pager, next"
        class="audit-page__pager"
        @current-change="fetchList"
      />
    </el-card>

    <el-drawer v-model="detailVisible" title="操作详情" size="620px">
      <el-descriptions v-if="detail" :column="1" border size="small">
        <el-descriptions-item label="操作时间">{{ detail.createTime }}</el-descriptions-item>
        <el-descriptions-item label="操作人">{{ detail.userName || "-" }}</el-descriptions-item>
        <el-descriptions-item label="业务域">{{ detail.module }}</el-descriptions-item>
        <el-descriptions-item label="请求">{{ detail.httpMethod }} {{ detail.requestUri }}</el-descriptions-item>
        <el-descriptions-item label="处理方法">
          {{ formatAuditHandler(detail.handler) }}
          <span v-if="hasAuditHandlerText(detail.handler)" class="audit-page__handler-raw">（{{ detail.handler }}）</span>
        </el-descriptions-item>
        <el-descriptions-item label="来源IP">{{ detail.clientIp || "-" }}</el-descriptions-item>
        <el-descriptions-item label="响应码">{{ detail.statusCode }}</el-descriptions-item>
        <el-descriptions-item label="耗时">{{ detail.costMs }} ms</el-descriptions-item>
        <el-descriptions-item v-if="detail.errorMsg" label="失败原因">{{ detail.errorMsg }}</el-descriptions-item>
      </el-descriptions>
      <h4 class="audit-page__detail-title">请求参数（敏感字段已脱敏）</h4>
      <pre class="audit-page__params">{{ prettyParams(detail?.params) }}</pre>
    </el-drawer>
  </div>
</template>

<style lang="scss" scoped>
.audit-page__filter {
  margin-bottom: 12px;
}

.audit-page__filter-row {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
}

.audit-page__pager {
  margin-top: 12px;
  justify-content: flex-end;
}

.audit-page__handler-raw {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.audit-page__detail-title {
  margin: 16px 0 8px;
  font-size: 14px;
}

.audit-page__params {
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
