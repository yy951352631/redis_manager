<script lang="ts" setup>
import type { AppListItem } from "@/api/cachecloud"
import { Plus, Refresh, Search } from "@element-plus/icons-vue"
import { getAppListApi, offlineAppApi, permanentlyDeleteOfflineAppApi, recoverAppApi, repairExternalRedisApi } from "@/api/cachecloud"
import { clusterRouteId } from "@/common/utils/cluster-no"
import { formatClusterType } from "@/common/utils/redis-type"
import { formatRedisVersion } from "@/common/utils/redis-version"

const router = useRouter()
const loading = ref(false)
const tableData = ref<AppListItem[]>([])
const total = ref(0)
const offlineSubmittingId = ref(0)
const recoveringId = ref(0)
const repairingId = ref(0)
const deletingId = ref(0)

const query = reactive({
  ip: "",
  appParam: "",
  appStatus: -1,
  pageNo: 1,
  pageSize: 20
})

function memUsedGb(row: AppListItem) {
  if (!row.mem) return 0
  return (row.mem * (row.memUsePercent || 0) / 100 / 1024)
}

function memTotalGb(row: AppListItem) {
  if (!row.mem) return 0
  return row.mem / 1024
}

function memProgressStatus(ratio: number) {
  if (ratio >= 80) return "exception"
  if (ratio >= 60) return "warning"
  return "success"
}

/**
 * 命中率公式，带入实际数字。
 *
 * 分子分母是窗口内的增量（末值减首值），不是累计值——口径与主页 KPI 一致，
 * 末行写明窗口，免得被当成开机至今的平均值来读。
 */
function hitFormula(row: AppListItem) {
  const hits = Number(row.keyspaceHits ?? 0)
  const misses = Number(row.keyspaceMisses ?? 0)
  const lookups = hits + misses
  if (lookups <= 0) return "近 1 小时该集群没有读请求"
  return [
    "命中率 = 命中次数 / (命中次数 + 未命中次数) × 100%",
    `= ${hits.toLocaleString()} / (${hits.toLocaleString()} + ${misses.toLocaleString()}) × 100%`,
    `= ${hits.toLocaleString()} / ${lookups.toLocaleString()} × 100%`,
    `= ${row.hitPercent}%`,
    "统计口径：近 1 小时各数据节点的命中增量汇总"
  ].join("<br/>")
}

function hitTagType(hit: number) {
  if (hit <= 0) return "info"
  if (hit <= 30) return "danger"
  if (hit < 50) return "warning"
  if (hit < 90) return "info"
  return "success"
}

function runtimeTagType(status: number) {
  if (status === 2) return "success"
  if (status === 3 || status === 6) return "danger"
  return "info"
}

function goOps(row: AppListItem) {
  router.push({ path: `/app/detail/${clusterRouteId(row.clusterNo, row.appId)}`, query: { tab: "app_stat" } })
}

async function handleOffline(row: AppListItem) {
  const confirmMessage = `确认要下线该应用？应用id=${row.appId}，确认请输入 YES。\n有管控主机时将远程关闭 Redis；无管控主机时仅从平台标记下线。`
  let userInput: string
  try {
    const { value } = await ElMessageBox.prompt(confirmMessage, "集群下线", {
      confirmButtonText: "确认下线",
      cancelButtonText: "取消",
      inputPlaceholder: "YES",
      inputValidator: (val: string) => (val === "YES" ? true : "请输入 YES 确认下线"),
      type: "warning"
    })
    userInput = value
  } catch {
    return
  }
  if (userInput !== "YES") {
    ElMessage.warning("输入的字符不正确，下线操作未生效")
    return
  }

  offlineSubmittingId.value = row.appId
  try {
    const { data } = await offlineAppApi(row.appId)
    const taskText = data?.taskId ? `，任务ID：${data.taskId}` : ""
    ElMessage.success(data?.message || `下线任务已提交，将在后台执行${taskText}`)
    await fetchList()
  } catch (e: unknown) {
    ElMessage.error(e instanceof Error ? e.message : "下线失败")
  } finally {
    offlineSubmittingId.value = 0
  }
}

function formatUptime(seconds: number) {
  if (!seconds || seconds < 0) return "-"
  if (seconds < 60) return `${seconds}秒`
  const minutes = Math.floor(seconds / 60)
  if (minutes < 60) return `${minutes}分钟`
  const hours = Math.floor(minutes / 60)
  if (hours < 24) return `${hours}小时${minutes % 60 ? `${minutes % 60}分钟` : ""}`
  const days = Math.floor(hours / 24)
  return `${days}天${hours % 24 ? `${hours % 24}小时` : ""}`
}

async function handleRecover(row: AppListItem) {
  try {
    await ElMessageBox.confirm(`确认恢复集群 ${row.appName}？恢复前会校验节点 IP:port 是否被当前在线集群占用。`, "恢复上线", {
      type: "warning"
    })
  } catch {
    return
  }
  recoveringId.value = row.appId
  try {
    const { data } = await recoverAppApi(row.appId)
    ElMessage.success(data?.message || "恢复上线成功")
    query.appStatus = 2
    query.pageNo = 1
    await fetchList()
  } catch (e: unknown) {
    ElMessage.error(e instanceof Error ? e.message : "恢复上线失败")
  } finally {
    recoveringId.value = 0
  }
}

async function handlePermanentDelete(row: AppListItem) {
  try {
    await ElMessageBox.confirm(
      `该操作将永久删除集群“${row.appName}”及其纳管节点记录，且不可恢复。确认继续吗？`,
      "彻底删除集群",
      { type: "error", confirmButtonText: "确认彻底删除", cancelButtonText: "取消" }
    )
  } catch {
    return
  }
  deletingId.value = row.appId
  try {
    await permanentlyDeleteOfflineAppApi(row.appId)
    ElMessage.success("集群已彻底删除")
    await fetchList()
  } catch (e: unknown) {
    ElMessage.error(e instanceof Error ? e.message : "彻底删除失败")
  } finally {
    deletingId.value = 0
  }
}

async function handleRepair(appId: number) {
  try {
    await ElMessageBox.confirm(`确认为集群 ${appId} 补写节点？`, "补写节点", { type: "warning" })
  } catch {
    return
  }
  repairingId.value = appId
  try {
    const { data } = await repairExternalRedisApi(appId)
    ElMessage.success(data?.message || "补写成功")
    await fetchList()
  } catch (e: unknown) {
    ElMessage.error(e instanceof Error ? e.message : "补写失败")
  } finally {
    repairingId.value = 0
  }
}

async function fetchList() {
  loading.value = true
  try {
    const { data } = await getAppListApi({
      ip: query.ip || undefined,
      appParam: query.appParam || undefined,
      appStatus: query.appStatus,
      pageNo: query.pageNo,
      pageSize: query.pageSize
    })
    tableData.value = data.items ?? []
    total.value = data.totalCount ?? 0
  } finally {
    loading.value = false
  }
}

function handleSearch() {
  query.pageNo = 1
  fetchList()
}

function handleReset() {
  query.ip = ""
  query.appParam = ""
  query.appStatus = -1
  query.pageNo = 1
  fetchList()
}

function handlePageChange(page: number) {
  query.pageNo = page
  fetchList()
}

function handleSizeChange(size: number) {
  query.pageSize = size
  query.pageNo = 1
  fetchList()
}

function tableIndex(index: number) {
  return (query.pageNo - 1) * query.pageSize + index + 1
}

function nodeTooltipLines(row: AppListItem) {
  if (row.allNodeLines?.length) {
    return row.allNodeLines
  }
  if (row.extraNodeLines?.length) {
    return [...row.nodeLines, ...row.extraNodeLines]
  }
  return row.nodeLines ?? []
}

function hasNodeTooltip(row: AppListItem) {
  return (row.extraNodeCount ?? 0) > 0 && nodeTooltipLines(row).length > (row.nodeLines?.length ?? 0)
}

onMounted(fetchList)
</script>

<template>
  <div class="app-list-page">
    <div class="page-header">
      <div class="page-actions">
        <el-button type="success" @click="router.push('/external/redis/add')">
          <el-icon><Plus /></el-icon>
          纳管集群
        </el-button>
      </div>
    </div>

    <el-card shadow="never" class="content-card">
      <el-form :inline="true" class="search-form" @submit.prevent="handleSearch">
        <el-form-item label="IP">
          <el-input v-model="query.ip" placeholder="IP / ip:port" clearable style="width: 160px" />
        </el-form-item>
        <el-form-item label="集群">
          <el-input v-model="query.appParam" placeholder="集群编码 / 名称" clearable style="width: 160px" />
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="query.appStatus" style="width: 120px" @change="handleSearch">
            <el-option label="全部" :value="-1" />
            <el-option label="运行中" :value="2" />
            <el-option label="已下线" :value="3" />
            <el-option label="未知" :value="5" />
            <el-option label="异常" :value="6" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :icon="Search" @click="handleSearch">
            查询
          </el-button>
          <el-button :icon="Refresh" @click="handleReset">
            重置
          </el-button>
        </el-form-item>
      </el-form>

      <el-table v-loading="loading" :data="tableData" stripe border style="width: 100%">
        <el-table-column label="序号" width="60" align="center" fixed>
          <template #default="{ $index }">
            {{ tableIndex($index) }}
          </template>
        </el-table-column>
        <el-table-column label="集群名称" min-width="180" show-overflow-tooltip>
          <template #default="{ row }">
            <el-link
              v-if="row.runtimeStatus === 2 || row.runtimeStatus === 5 || row.runtimeStatus === 6"
              type="primary"
              :underline="false"
              @click="goOps(row)"
            >
              <span :class="{ 'text-danger': row.hasOfflineInstances }">{{ row.appName }}</span>
            </el-link>
            <span v-else>{{ row.appName }}</span>
          </template>
        </el-table-column>
        <el-table-column label="版本" min-width="92">
          <template #default="{ row }">
            {{ formatRedisVersion(row.versionName) || "-" }}
          </template>
        </el-table-column>
        <el-table-column label="类型" min-width="110">
          <template #default="{ row }">
            {{ formatClusterType(row.typeDesc) || "-" }}
          </template>
        </el-table-column>
        <el-table-column label="节点信息" min-width="220">
          <template #default="{ row }">
            <el-popover
              v-if="hasNodeTooltip(row)"
              placement="top-start"
              trigger="hover"
              :show-after="150"
              :width="300"
              popper-class="app-list-node-popover"
            >
              <template #reference>
                <div class="node-info-cell">
                  <div v-if="row.matchedNodes?.length" class="node-lines">
                    <span class="text-muted">匹配：</span>
                    <div v-for="n in row.matchedNodes" :key="n">
                      {{ n }}
                    </div>
                  </div>
                  <div v-for="n in row.nodeLines" :key="n" class="node-lines">
                    {{ n }}
                  </div>
                  <span class="text-muted">+{{ row.extraNodeCount }} 个节点</span>
                </div>
              </template>
              <div class="node-popover-list">
                <div v-for="n in nodeTooltipLines(row)" :key="n" class="node-popover-line">
                  {{ n }}
                </div>
              </div>
            </el-popover>
            <div v-else class="node-info-cell">
              <div v-if="row.matchedNodes?.length" class="node-lines">
                <span class="text-muted">匹配：</span>
                <div v-for="n in row.matchedNodes" :key="n">
                  {{ n }}
                </div>
              </div>
              <div v-for="n in row.nodeLines" :key="n" class="node-lines">
                {{ n }}
              </div>
              <span v-if="!row.nodeLines?.length && !row.matchedNodes?.length">-</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="节点数" min-width="80" align="center">
          <template #default="{ row }">
            <span :class="{ 'text-danger': row.instanceCount === 0 }">{{ row.instanceCount }}</span>
          </template>
        </el-table-column>
        <el-table-column label="key数量" min-width="96" align="center">
          <template #default="{ row }">
            {{ row.keyCount > 0 ? row.keyCount.toLocaleString() : "0" }}
          </template>
        </el-table-column>
        <el-table-column label="内存详情" min-width="200" align="center">
          <template #default="{ row }">
            <template v-if="row.mem > 0">
              <div class="mem-progress-wrap">
                <el-progress
                  :percentage="Math.min(Math.max(row.memUsePercent, 5), 100)"
                  :status="memProgressStatus(row.memUsePercent)"
                  :stroke-width="18"
                  :show-text="false"
                />
                <span class="mem-progress-label">
                  {{ memUsedGb(row).toFixed(2) }}G Used / {{ memTotalGb(row).toFixed(2) }}G Total
                </span>
              </div>
            </template>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column label="集群QPS" min-width="100" align="center">
          <template #default="{ row }">
            {{ Number(row.qps || 0).toLocaleString() }}
          </template>
        </el-table-column>
        <el-table-column label="命中率" min-width="88" align="center">
          <template #default="{ row }">
            <el-tooltip v-if="row.hitPercent > 0" placement="top" raw-content :content="hitFormula(row)">
              <el-tag :type="hitTagType(row.hitPercent)" size="small">
                {{ row.hitPercent }}%
              </el-tag>
            </el-tooltip>
            <span v-else>无</span>
          </template>
        </el-table-column>
        <el-table-column label="运行时长" min-width="100" align="center">
          <template #default="{ row }">
            {{ formatUptime(row.uptimeSeconds) }}
          </template>
        </el-table-column>
        <el-table-column label="运行状态" min-width="96" align="center">
          <template #default="{ row }">
            <el-popover v-if="row.runtimeStatus === 6 && row.runtimeStatusDetail" placement="top" trigger="click" :width="360">
              <template #reference>
                <el-tag :type="runtimeTagType(row.runtimeStatus)" size="small" class="status-clickable">
                  异常
                </el-tag>
              </template>
              <div class="status-detail">
                {{ row.runtimeStatusDetail }}
              </div>
            </el-popover>
            <el-tag v-else :type="runtimeTagType(row.runtimeStatus)" size="small">
              {{ row.runtimeStatus === 2 ? "运行中" : row.runtimeStatus === 3 ? "已下线" : row.runtimeStatus === 5 ? "未知" : row.runtimeStatus === 6 ? "异常" : row.runtimeStatusLabel }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="240" fixed="right">
          <template #default="{ row }">
            <div class="table-actions">
              <template v-if="row.runtimeStatus === 2 || row.runtimeStatus === 5 || row.runtimeStatus === 6">
                <el-button type="success" size="small" @click="goOps(row)">
                  集群运维
                </el-button>
                <el-button
                  type="danger"
                  size="small"
                  :loading="offlineSubmittingId === row.appId"
                  @click="handleOffline(row)"
                >
                  集群下线
                </el-button>
              </template>
              <el-button
                v-else-if="row.runtimeStatus === 3"
                type="success"
                size="small"
                :loading="recoveringId === row.appId"
                @click="handleRecover(row)"
              >
                恢复上线
              </el-button>
              <el-button
                v-if="row.runtimeStatus === 3"
                type="danger"
                plain
                size="small"
                :loading="deletingId === row.appId"
                @click="handlePermanentDelete(row)"
              >
                彻底删除
              </el-button>
              <el-button
                v-if="row.externalManaged && row.instanceCount === 0"
                type="warning"
                size="small"
                :loading="repairingId === row.appId"
                @click="handleRepair(row.appId)"
              >
                补写实例
              </el-button>
            </div>
          </template>
        </el-table-column>
      </el-table>

      <div class="pagination-wrap">
        <el-pagination
          :current-page="query.pageNo"
          :page-size="query.pageSize"
          :total="total"
          :page-sizes="[10, 20, 50, 100]"
          layout="total, sizes, prev, pager, next"
          background
          @current-change="handlePageChange"
          @size-change="handleSizeChange"
        />
      </div>
    </el-card>
  </div>
</template>

<style lang="scss" scoped>
.app-list-page {
  padding: 16px;
}

.page-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16px;
}

.page-title {
  margin: 0;
  font-size: 20px;
  font-weight: 600;
}

.page-actions {
  display: flex;
  gap: 8px;
}

.search-form {
  margin-bottom: 16px;
}

.content-card {
  :deep(.el-card__body) {
    padding: 16px;
  }
}

.node-lines {
  font-size: 12px;
  line-height: 1.5;
}

.node-info-cell {
  display: inline-block;
  width: 100%;
  font-size: 12px;
  line-height: 1.5;
  cursor: default;
}

.el-popover__reference-wrapper .node-info-cell {
  cursor: help;
}

.mem-progress-wrap {
  position: relative;
  width: 188px;
  max-width: 100%;
  margin: 0 auto;
}

.mem-progress-wrap :deep(.el-progress-bar__outer) {
  border-radius: 4px;
}

.mem-progress-label {
  position: absolute;
  left: 0;
  right: 0;
  top: 0;
  height: 18px;
  z-index: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 11px;
  color: #333;
  line-height: 18px;
  pointer-events: none;
  white-space: nowrap;
}

.text-muted {
  color: var(--el-text-color-secondary);
}

.text-danger {
  color: var(--el-color-danger);
}

.pagination-wrap {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;
}

.table-actions {
  display: flex;
  flex-wrap: nowrap;
  align-items: center;
  justify-content: center;
  gap: 6px;
  white-space: nowrap;
}

.table-actions :deep(.el-button) {
  margin-left: 0;
}
</style>

<style lang="scss">
.app-list-node-popover {
  .node-popover-list {
    max-height: 240px;
    overflow-y: auto;
  }

  .node-popover-line {
    font-size: 12px;
    line-height: 1.6;
    word-break: break-all;
  }
}
</style>
