<script lang="ts" setup>
import type { ServerStatAppRow } from "@/api/cachecloud"
import {
  getServerStatAppsApi,
  sendServerStatDailyEmailApi,
  updateServerTopologyApi
} from "@/api/cachecloud"
import { usePagination } from "@/common/composables/usePagination"
import { clusterRouteId, formatClusterNo } from "@/common/utils/cluster-no"
import { Search } from "@element-plus/icons-vue"
import { formatRedisVersion } from "@/common/utils/redis-version"
import { useAutoQuery } from "@@/composables/useAutoQuery"

const route = useRoute()
const router = useRouter()

const loading = ref(false)
const updatingTopology = ref(false)
const sendingEmail = ref(false)
const activeTab = ref("mem")
const tableData = ref<ServerStatAppRow[]>([])
const searchKeyword = ref("")

const query = reactive({
  searchDate: new Date().toISOString().slice(0, 10)
})

const tabSearchPlaceholders: Record<string, string> = {
  mem: "搜索集群编码、集群名称、集群类型等",
  frag: "搜索集群编码、集群名称、Redis 版本等",
  topology: "搜索集群编码、集群名称、拓扑状态等"
}

const { paginationData, handleCurrentChange, handleSizeChange } = usePagination({
  pageSize: 30,
  pageSizes: [10, 20, 30, 50, 100]
})

const filteredData = computed(() => {
  const kw = searchKeyword.value.trim().toLowerCase()
  if (!kw) return tableData.value
  return tableData.value.filter(row =>
    formatClusterNo(row.clusterNo, row.appId).includes(kw)
    || String(row.appId).includes(kw)
    || row.appName.toLowerCase().includes(kw)
    || row.typeDesc.toLowerCase().includes(kw)
    || row.versionName.toLowerCase().includes(kw)
    || row.testLabel.includes(kw)
    || row.topologyExamLabel.includes(kw)
  )
})

const paginatedData = computed(() => {
  const start = (paginationData.currentPage - 1) * paginationData.pageSize
  return filteredData.value.slice(start, start + paginationData.pageSize)
})

watch(filteredData, (rows) => {
  paginationData.total = rows.length
  const maxPage = Math.max(1, Math.ceil(rows.length / paginationData.pageSize))
  if (paginationData.currentPage > maxPage) {
    paginationData.currentPage = maxPage
  }
}, { immediate: true })

watch(searchKeyword, () => {
  paginationData.currentPage = 1
})

watch(activeTab, () => {
  paginationData.currentPage = 1
  searchKeyword.value = ""
})

function memProgressStatus(ratio: number) {
  if (ratio >= 80) return "exception"
  if (ratio >= 60) return "warning"
  return "success"
}

function syncTab(tab: string) {
  router.replace({ path: route.path, query: { ...route.query, tab } })
}

function handleTabChange(tab: string | number | boolean) {
  activeTab.value = String(tab)
  syncTab(String(tab))
}

function goAppDetail(row: ServerStatAppRow, tab = "app_stat") {
  router.push({ path: `/app/detail/${clusterRouteId(row.clusterNo, row.appId)}`, query: { tab } })
}

function openOps(appId: number, tab = "app_topology") {
  router.push({ path: `/app/detail/${appId}`, query: { tab } })
}

// 选完日期即自动查询；fetchData 与 onMounted 里对 searchDate 的赋值用 silent 包住
const { silent: silentDate } = useAutoQuery(() => query.searchDate, () => fetchData())

async function fetchData() {
  loading.value = true
  try {
    const { data } = await getServerStatAppsApi({ searchDate: query.searchDate })
    tableData.value = data.items ?? []
    paginationData.currentPage = 1
    if (data.searchDate) {
      silentDate(() => { query.searchDate = data.searchDate })
    }
  } finally {
    loading.value = false
  }
}

async function handleSendDailyEmail() {
  sendingEmail.value = true
  try {
    await sendServerStatDailyEmailApi(query.searchDate)
    ElMessage.success("异常集群日报已发送，请查收")
  } finally {
    sendingEmail.value = false
  }
}

async function handleTopologyUpdate() {
  try {
    await ElMessageBox.confirm("确认提交拓扑检查更新？更新完毕后请刷新查看结果。", "提示", { type: "warning" })
    updatingTopology.value = true
    await updateServerTopologyApi()
    ElMessage.success("拓扑检测更新成功")
    activeTab.value = "topology"
    syncTab("topology")
    await fetchData()
  } catch {
    // cancelled or failed
  } finally {
    updatingTopology.value = false
  }
}

onMounted(() => {
  const tab = route.query.tab as string | undefined
  if (tab === "frag" || tab === "topology") {
    activeTab.value = tab
  }
  const searchDate = route.query.searchDate as string | undefined
  if (searchDate) {
    silentDate(() => { query.searchDate = searchDate })
  }
  fetchData()
})
</script>

<template>
  <div class="server-stat-page">
    <el-card shadow="never">
      <div class="filter-bar">
        <el-date-picker
          v-model="query.searchDate"
          type="date"
          value-format="YYYY-MM-DD"
          placeholder="查询日期"
          class="filter-bar__date"
        />
        <el-button type="primary" :icon="Search" :loading="loading" @click="fetchData">
          查询
        </el-button>
        <el-button :loading="sendingEmail" @click="handleSendDailyEmail">
          发送日报
        </el-button>
      </div>

      <el-tabs v-model="activeTab" @tab-change="handleTabChange">
        <el-tab-pane label="内存指标" name="mem">
          <div class="tab-toolbar">
            <el-input
              v-model="searchKeyword"
              :placeholder="tabSearchPlaceholders.mem"
              clearable
              class="tab-toolbar__search"
            />
            <el-button type="primary" size="small" :icon="Search">查询</el-button>
          </div>
          <el-table v-loading="loading" :data="paginatedData" stripe border>
            <el-table-column label="集群编码" width="90">
              <template #default="{ row }">
                <el-link type="primary" :underline="false" @click="goAppDetail(row)">
                  {{ formatClusterNo(row.clusterNo, row.appId) }}
                </el-link>
              </template>
            </el-table-column>
            <el-table-column label="集群名称" min-width="140">
              <template #default="{ row }">
                <el-link type="primary" :underline="false" @click="goAppDetail(row)">
                  {{ row.appName }}
                </el-link>
              </template>
            </el-table-column>
            <el-table-column prop="testLabel" label="是否测试" width="90" />
            <el-table-column prop="typeDesc" label="集群类型" width="120" />
            <el-table-column prop="shardSpec" label="分片数*分片内存G" width="150" />
            <el-table-column label="内存使用" min-width="220">
              <template #default="{ row }">
                <el-progress
                  :percentage="Math.min(row.memUsageRatio, 100)"
                  :status="memProgressStatus(row.memUsageRatio)"
                  :stroke-width="14"
                />
                <span class="mem-text">{{ row.memUsedGb.toFixed(2) }}G Used / {{ row.memTotalGb.toFixed(2) }}G Total</span>
              </template>
            </el-table-column>
            <el-table-column label="rss内存使用" min-width="220">
              <template #default="{ row }">
                <el-progress
                  :percentage="Math.min(row.memUsageRatioRss, 100)"
                  :status="memProgressStatus(row.memUsageRatioRss)"
                  :stroke-width="14"
                />
                <span class="mem-text">{{ row.memUsedRssGb.toFixed(2) }}G Used / {{ row.memTotalGb.toFixed(2) }}G Total</span>
              </template>
            </el-table-column>
            <el-table-column label="内存使用率%" width="110" align="center">
              <template #default="{ row }">
                {{ row.memUsePercent }}
              </template>
            </el-table-column>
            <el-table-column label="慢查询" width="90" align="center">
              <template #default="{ row }">
                <el-link
                  type="primary"
                  :underline="false"
                  @click="goAppDetail(row.appId, 'app_latency')"
                >
                  {{ row.slowLogCount }}
                </el-link>
              </template>
            </el-table-column>
            <el-table-column label="客户端连接数" width="110" align="center">
              <template #default="{ row }">
                {{ row.connectedClients }}
              </template>
            </el-table-column>
            <el-table-column label="操作" width="200" fixed="right">
              <template #default="{ row }">
                <div class="table-actions">
                  <el-button
                    type="warning"
                    size="small"
                    @click="goAppDetail(row.appId, 'app_clientList')"
                  >
                    连接信息
                  </el-button>
                  <el-button
                    type="warning"
                    size="small"
                    @click="openOps(row.appId)"
                  >
                    运维信息
                  </el-button>
                </div>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <el-tab-pane label="碎片率指标" name="frag">
          <div class="tab-toolbar">
            <el-input
              v-model="searchKeyword"
              :placeholder="tabSearchPlaceholders.frag"
              clearable
              class="tab-toolbar__search"
            />
            <el-button type="primary" size="small" :icon="Search">查询</el-button>
          </div>
          <el-table v-loading="loading" :data="paginatedData" stripe border>
            <el-table-column label="集群编码" width="90">
              <template #default="{ row }">
                <el-link type="primary" :underline="false" @click="goAppDetail(row)">
                  {{ formatClusterNo(row.clusterNo, row.appId) }}
                </el-link>
              </template>
            </el-table-column>
            <el-table-column label="集群名称" min-width="140">
              <template #default="{ row }">
                <el-link type="primary" :underline="false" @click="goAppDetail(row)">
                  {{ row.appName }}
                </el-link>
              </template>
            </el-table-column>
            <el-table-column prop="testLabel" label="是否测试" width="90" />
            <el-table-column label="redis版本" min-width="110">
              <template #default="{ row }">
                {{ formatRedisVersion(row.versionName) || "-" }}
              </template>
            </el-table-column>
            <el-table-column prop="objectSize" label="key数量" width="100" />
            <el-table-column label="内存使用" width="110">
              <template #default="{ row }">{{ row.usedMemoryMb.toFixed(2) }}</template>
            </el-table-column>
            <el-table-column label="rss内存使用" width="110">
              <template #default="{ row }">{{ row.usedMemoryRssMb.toFixed(2) }}</template>
            </el-table-column>
            <el-table-column label="平均碎片率(%)" width="120">
              <template #default="{ row }">{{ row.avgMemFragRatio }}</template>
            </el-table-column>
            <el-table-column prop="maxCpuSys" label="max cpuSys(s)" width="120" />
            <el-table-column prop="maxCpuUser" label="max cpuUser(s)" width="120" />
            <el-table-column label="操作" width="120" fixed="right">
              <template #default="{ row }">
                <el-button type="warning" size="small" @click="openOps(row.appId)">
                  运维信息
                </el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <el-tab-pane label="集群拓扑诊断" name="topology">
          <div class="tab-toolbar">
            <el-input
              v-model="searchKeyword"
              :placeholder="tabSearchPlaceholders.topology"
              clearable
              class="tab-toolbar__search"
            />
            <el-button type="primary" size="small" :icon="Search">查询</el-button>
            <el-button
              type="success"
              size="small"
              :loading="updatingTopology"
              @click="handleTopologyUpdate"
            >
              诊断更新
            </el-button>
          </div>
          <el-table v-loading="loading" :data="paginatedData" stripe border>
            <el-table-column label="集群编码" width="90">
              <template #default="{ row }">
                <el-link type="primary" :underline="false" @click="goAppDetail(row)">
                  {{ formatClusterNo(row.clusterNo, row.appId) }}
                </el-link>
              </template>
            </el-table-column>
            <el-table-column label="集群名称" min-width="140">
              <template #default="{ row }">
                <el-link type="primary" :underline="false" @click="goAppDetail(row)">
                  {{ row.appName }}
                </el-link>
              </template>
            </el-table-column>
            <el-table-column prop="testLabel" label="是否测试" width="90" />
            <el-table-column prop="typeDesc" label="类型" width="120" />
            <el-table-column label="redis版本" min-width="110">
              <template #default="{ row }">
                {{ formatRedisVersion(row.versionName) || "-" }}
              </template>
            </el-table-column>
            <el-table-column label="节点分布" width="130">
              <template #default="{ row }">
                主从节点:
                <span :class="{ 'text-danger': !row.nodeBalanced }">
                  {{ row.masterNum }},{{ row.slaveNum }}
                </span>
              </template>
            </el-table-column>
            <el-table-column label="拓扑诊断" width="100" align="center">
              <template #default="{ row }">
                <el-tag
                  :type="row.topologyExamResult === 0 ? 'success' : 'danger'"
                  size="small"
                >
                  {{ row.topologyExamLabel }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="120" fixed="right">
              <template #default="{ row }">
                <el-button type="primary" size="small" @click="openOps(row.appId, 'topology')">
                  查看诊断
                </el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>
      </el-tabs>

      <div class="pagination-wrap">
        <el-pagination
          :current-page="paginationData.currentPage"
          :page-size="paginationData.pageSize"
          :total="paginationData.total"
          :page-sizes="paginationData.pageSizes"
          layout="total, sizes, prev, pager, next"
          background
          @current-change="handleCurrentChange"
          @size-change="handleSizeChange"
        />
      </div>
    </el-card>
  </div>
</template>

<style lang="scss" scoped>
.server-stat-page {
  padding: 16px;
}

.filter-bar {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 12px;
  margin-bottom: 16px;

  &__date {
    width: 160px;
  }
}

.tab-toolbar {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 12px;

  &__search {
    width: 280px;
  }
}

.table-actions {
  display: flex;
  align-items: center;
  flex-wrap: nowrap;
  gap: 4px;
  white-space: nowrap;
}

.pagination-wrap {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;
}

.mem-text {
  display: block;
  margin-top: 4px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.text-danger {
  color: var(--el-color-danger);
}
</style>
