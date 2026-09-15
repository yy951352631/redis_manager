<script lang="ts" setup>
import type { ExternalRedisNode } from "@/api/cachecloud"
import { Refresh, Search } from "@element-plus/icons-vue"
import { getExternalRedisListApi, repairExternalRedisApi } from "@/api/cachecloud"
import { formatUptime } from "@/common/utils/duration"
import { formatClusterType } from "@/common/utils/redis-type"
import "@/common/assets/styles/app-ops.scss"

const router = useRouter()
const route = useRoute()
const loading = ref(false)
const repairingId = ref(0)
const nodes = ref<ExternalRedisNode[]>([])
const total = ref(0)
const searchIp = ref(String(route.query.ip || ""))
const searchStatus = ref(Number(route.query.status ?? -1))
const showSentinel = ref(route.query.showSentinel === "1")
const pageNo = ref(1)
const pageSize = ref(20)

const hasSearch = computed(() => !!searchIp.value.trim())

function tableIndex(index: number) {
  return (pageNo.value - 1) * pageSize.value + index + 1
}

function memProgressStatus(ratio: number) {
  if (ratio >= 80) return "exception"
  if (ratio >= 60) return "warning"
  return "success"
}

const FRAG_MEM_THRESHOLD_MB = 500

function usedMemMb(row: ExternalRedisNode) {
  return Number(row.usedMemGb || 0) * 1024
}

function fragClass(row: ExternalRedisNode) {
  // 已用内存不超过 500MB 时，碎片率天然偏高且参考意义有限，不参与判定
  if (usedMemMb(row) <= FRAG_MEM_THRESHOLD_MB) return "is-success"
  const ratio = Number(row.memFragmentationRatio || 0)
  if (ratio > 3) return "is-danger"
  if (ratio >= 1.5) return "is-warning"
  return "is-success"
}

function fragTip(row: ExternalRedisNode) {
  const used = usedMemMb(row)
  const head = used <= FRAG_MEM_THRESHOLD_MB
    ? `当前已用内存 ${used.toFixed(0)}MB，未超过 ${FRAG_MEM_THRESHOLD_MB}MB，不参与判定（显示为正常）`
    : `当前已用内存 ${used.toFixed(0)}MB，已超过 ${FRAG_MEM_THRESHOLD_MB}MB，按下列规则判定`
  return [
    "碎片率 = used_memory_rss / used_memory",
    head,
    "· 大于 3：碎片严重，建议排查并择机重启",
    "· 1.5 ~ 3：碎片偏高，需持续关注",
    "· 小于 1.5：正常"
  ].join("\n")
}

function statusTagType(status: number) {
  if (status === 1) return "success"
  if (status === 0) return "danger"
  return "info"
}

function openNodeDetail(node: ExternalRedisNode) {
  if (!node.synced || node.instanceId <= 0) return
  router.push({
    path: `/external/redis/detail/${node.instanceId}`,
    query: { appId: node.appId, from: "node" }
  })
}

async function fetchList() {
  loading.value = true
  try {
    const ip = searchIp.value.trim()
    const { data } = await getExternalRedisListApi({
      ip: ip || undefined,
      status: searchStatus.value >= 0 ? searchStatus.value : undefined,
      includeSentinel: showSentinel.value,
      pageNo: pageNo.value,
      pageSize: pageSize.value
    })
    nodes.value = data?.items ?? []
    total.value = data?.totalCount ?? 0
    if (data?.pageNo) pageNo.value = data.pageNo
    if (data?.pageSize) pageSize.value = data.pageSize
    router.replace({
      path: route.path,
      query: {
        ...(ip ? { ip } : {}),
        ...(searchStatus.value >= 0 ? { status: String(searchStatus.value) } : {}),
        ...(showSentinel.value ? { showSentinel: "1" } : {})
      }
    })
  } finally {
    loading.value = false
  }
}

function handleSearch() {
  pageNo.value = 1
  fetchList()
}

function handleReset() {
  searchIp.value = ""
  searchStatus.value = -1
  showSentinel.value = false
  pageNo.value = 1
  fetchList()
}

function handlePageChange(page: number) {
  pageNo.value = page
  fetchList()
}

function handleSizeChange(size: number) {
  pageSize.value = size
  pageNo.value = 1
  fetchList()
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

onMounted(fetchList)
</script>

<template>
  <div class="external-redis-page">
    <el-card shadow="never" class="content-card">
      <el-form :inline="true" class="search-form" @submit.prevent="handleSearch">
        <el-form-item label="IP">
          <el-input v-model="searchIp" placeholder="IP 模糊 / ip:port" clearable style="width: 200px" />
        </el-form-item>
        <el-form-item>
          <el-select v-model="searchStatus" style="width: 130px" @change="handleSearch">
            <el-option label="全部状态" :value="-1" />
            <el-option label="运行中" :value="1" />
            <el-option label="异常" :value="0" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :icon="Search" @click="handleSearch">
            查询
          </el-button>
          <el-button :icon="Refresh" @click="handleReset">
            清除
          </el-button>
        </el-form-item>
        <el-form-item>
          <el-switch v-model="showSentinel" active-text="显示哨兵节点" @change="handleSearch" />
        </el-form-item>
      </el-form>

      <el-alert
        v-if="hasSearch"
        type="info"
        :closable="false"
        class="search-alert"
        :title="`搜索「${searchIp.trim()}」：命中 ${total} 个节点${total ? '' : '，请确认该地址已纳管'}`"
      />

      <el-table v-loading="loading" :data="nodes" stripe border style="width: 100%">
        <el-table-column label="序号" width="60">
          <template #default="{ $index }">
            {{ tableIndex($index) }}
          </template>
        </el-table-column>
        <el-table-column label="节点地址" min-width="180">
          <template #default="{ row }">
            <el-link
              v-if="row.synced && row.instanceId > 0"
              type="primary"
              :underline="false"
              @click="openNodeDetail(row)"
            >
              {{ row.ip }}:{{ row.port }}
            </el-link>
            <template v-else>
              {{ row.ip }}:{{ row.port }}
              <el-tag size="small" type="warning" class="inline-tag">
                未入库
              </el-tag>
            </template>
            <div v-if="row.nodeTypeDesc === 'sentinel' && row.cmd" class="sentinel-master">
              master: {{ row.cmd }}
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="roleDesc" label="角色" width="90" />
        <el-table-column prop="appName" label="所属集群" min-width="120" show-overflow-tooltip />
        <el-table-column label="集群类型" width="120">
          <template #default="{ row }">
            {{ formatClusterType(row.appTypeDesc) || "-" }}
          </template>
        </el-table-column>
        <el-table-column label="内存详情" min-width="200">
          <template #default="{ row }">
            <template v-if="row.nodeTypeDesc === 'sentinel'">
              -
            </template>
            <template v-else-if="row.totalMemGb > 0">
              <el-progress
                :percentage="Math.min(row.memUsePercent, 100)"
                :status="memProgressStatus(row.memUsePercent)"
                :stroke-width="14"
              />
              <span class="mem-text">
                {{ row.usedMemGb.toFixed(2) }}G Used / {{ row.totalMemGb.toFixed(2) }}G Total
              </span>
            </template>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column label="运行时长" width="112" align="center">
          <template #default="{ row }">
            {{ row.nodeTypeDesc === "sentinel" ? "-" : formatUptime(row.uptimeSeconds) }}
          </template>
        </el-table-column>
        <el-table-column label="key数量" width="108" align="center">
          <template #default="{ row }">
            {{ row.nodeTypeDesc === "sentinel" ? "-" : (row.keyCount || 0).toLocaleString() }}
          </template>
        </el-table-column>
        <el-table-column label="CPU使用率" width="108" align="center">
          <template #default="{ row }">
            {{ row.nodeTypeDesc === "sentinel" ? "-" : `${Number(row.cpuUsePercent || 0).toFixed(1)}%` }}
          </template>
        </el-table-column>
        <el-table-column label="碎片率" width="96" align="center">
          <template #default="{ row }">
            <template v-if="row.nodeTypeDesc === 'sentinel' || !row.memFragmentationRatio">
              -
            </template>
            <el-tooltip v-else placement="top" :content="fragTip(row)" popper-class="frag-tip-popper">
              <span class="app-ops-frag" :class="fragClass(row)">
                {{ Number(row.memFragmentationRatio).toFixed(2) }}
              </span>
            </el-tooltip>
          </template>
        </el-table-column>
        <el-table-column label="连接数量" width="104" align="center">
          <template #default="{ row }">
            {{ row.nodeTypeDesc === "sentinel" ? "-" : (row.connectedClients || 0).toLocaleString() }}
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="statusTagType(row.status)" size="small">
              {{ row.statusDesc }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="140" fixed="right">
          <template #default="{ row }">
            <div class="table-actions">
              <el-button
                v-if="row.synced && row.instanceId > 0"
                type="primary"
                size="small"
                @click="openNodeDetail(row)"
              >
                节点详情
              </el-button>
              <el-button
                v-if="!row.synced"
                type="warning"
                size="small"
                :loading="repairingId === row.appId"
                @click="handleRepair(row.appId)"
              >
                补写节点
              </el-button>
            </div>
          </template>
        </el-table-column>
        <template #empty>
          <template v-if="hasSearch">
            未找到匹配节点，<el-button link type="primary" @click="handleReset">
              查看全部
            </el-button>
          </template>
          <template v-else>
            暂无纳管节点
          </template>
        </template>
      </el-table>

      <div v-if="total > 0" class="pagination-wrap">
        <el-pagination
          :current-page="pageNo"
          :page-size="pageSize"
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
.external-redis-page {
  padding: 16px;
}

.search-form {
  margin-bottom: 12px;
}

.content-card {
  :deep(.el-card__body) {
    padding: 16px;
  }
}

.search-alert {
  margin-bottom: 12px;
}

.mem-text {
  display: block;
  margin-top: 4px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.sentinel-master {
  margin-top: 4px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.inline-tag {
  margin-left: 6px;
}

.pagination-wrap {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;
}
</style>

<!-- tooltip 通过 teleport 挂到 body 上，scoped 选择器命中不到，这里用非 scoped 样式 -->
<style lang="scss">
.frag-tip-popper {
  max-width: 320px;
  line-height: 1.7;
  white-space: pre-line;
}
</style>
