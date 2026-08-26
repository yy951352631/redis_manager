<script lang="ts" setup>
import type { MigrateListItem, MigrateText, UserListItem } from "@/api/cachecloud"
import {
  compareMigrateKeyCountApi,
  deleteMigrateApi,
  getMigrateConfigApi,
  getMigrateListApi,
  getMigrateLogApi,
  getMigrateProcessApi,
  getUserListApi,
  resyncMigrateApi,
  stopMigrateApi
} from "@/api/cachecloud"
import { CircleCheck, Plus, Refresh, Search } from "@element-plus/icons-vue"
import { formatRedisVersion } from "@/common/utils/redis-version"
import "@/common/assets/styles/migrate.scss"

const router = useRouter()
const route = useRoute()

const loading = ref(false)
const items = ref<MigrateListItem[]>([])
const total = ref(0)
const admins = ref<UserListItem[]>([])

const query = reactive({
  sourceAppName: "",
  targetAppName: "",
  userId: "" as string | number,
  status: -2,
  pageNo: 1,
  pageSize: 15
})

const textDrawerVisible = ref(false)
const textDrawerTitle = ref("")
const textContent = ref<MigrateText | null>(null)
const processVisible = ref(false)
const processText = ref("")
const processData = ref<Record<string, any> | null>(null)
const processRow = ref<MigrateListItem | null>(null)
const processRefreshing = ref(false)
const keyCompareVisible = ref(false)
const keyCompareLoading = ref(false)
const keyCompareData = ref<{ sourceKeyCount: number, targetKeyCount: number, difference: number, consistent: boolean, running: boolean } | null>(null)

async function fetchAdmins() {
  try {
    const { data } = await getUserListApi()
    admins.value = (data ?? []).filter(u => u.type === 0)
  } catch {
    admins.value = []
  }
}

async function fetchList() {
  loading.value = true
  try {
    const params: Record<string, unknown> = {
      pageNo: query.pageNo,
      pageSize: query.pageSize,
      status: query.status
    }
    if (query.sourceAppName) params.sourceAppName = query.sourceAppName.trim()
    if (query.targetAppName) params.targetAppName = query.targetAppName.trim()
    if (query.userId !== "" && query.userId !== null) params.userId = Number(query.userId)
    const { data } = await getMigrateListApi(params)
    items.value = data?.items ?? []
    total.value = data?.totalCount ?? 0
  } finally {
    loading.value = false
  }
}

function handleSearch() {
  query.pageNo = 1
  fetchList()
}

function openCreate() {
  router.push("/migrate/create")
}

function openAppDetail(appId: number) {
  if (appId > 0) router.push({ path: `/app/detail/${appId}`, query: { tab: "app_stat" } })
}

async function handleStop(row: MigrateListItem) {
  await ElMessageBox.confirm(`确认要停掉 id=${row.id} 的迁移任务吗?`, "提示", { type: "warning" })
  const { data } = await stopMigrateApi(row.id, row.migrateTool)
  ElMessage.success(data?.message || "已发送停止指令")
  fetchList()
}

async function handleResync(row: MigrateListItem) {
  await ElMessageBox.confirm(`确认重新同步迁移任务 ${row.migrateId} 吗？`, "重新同步", { type: "warning" })
  const { data } = await resyncMigrateApi(row.id)
  ElMessage.success(data?.message || "重新同步任务已启动")
  fetchList()
}

async function handleDelete(row: MigrateListItem) {
  await ElMessageBox.confirm(`删除迁移任务 ${row.migrateId} 后将同时清理日志和配置，是否继续？`, "删除任务", { type: "warning" })
  await deleteMigrateApi(row.id)
  ElMessage.success("迁移任务已删除")
  fetchList()
}

async function handleKeyCompare(row: MigrateListItem) {
  keyCompareVisible.value = true
  keyCompareLoading.value = true
  keyCompareData.value = null
  try {
    const { data } = await compareMigrateKeyCountApi(row.id)
    keyCompareData.value = data ?? null
  } finally {
    keyCompareLoading.value = false
  }
}

async function showText(title: string, loader: () => Promise<{ data?: MigrateText }>) {
  textDrawerTitle.value = title
  textDrawerVisible.value = true
  textContent.value = null
  const { data } = await loader()
  textContent.value = data ?? null
}

async function showProcess(row: MigrateListItem) {
  processVisible.value = true
  processRow.value = row
  await loadProcess(row)
}

async function loadProcess(row: MigrateListItem) {
  processText.value = ""
  processData.value = null
  const { data } = await getMigrateProcessApi(row.id, row.migrateTool)
  if (data?.stage || data?.entries || data?.raw) {
    processData.value = data as Record<string, any>
  } else if (row.migrateTool === 0) {
    processText.value = data?.process || "暂无进度信息"
  } else {
    processText.value = JSON.stringify(data?.toolStats ?? {}, null, 2)
  }
}

async function refreshProcess() {
  if (!processRow.value) return
  processRefreshing.value = true
  try {
    await loadProcess(processRow.value)
  } finally {
    processRefreshing.value = false
  }
}

function canStop(row: MigrateListItem) {
  return row.status !== 1 && row.status !== 2
}

function canShowProcess(row: MigrateListItem) {
  return row.status === -1 || row.status === 0 || row.status === 3
}

function canResync(row: MigrateListItem) {
  return row.status === 1 && row.migrateTool === 0 && row.migrateMachineIp?.startsWith("embedded@")
}

function canDelete(row: MigrateListItem) {
  return (row.status === 1 || row.status === 2) && row.migrateTool === 0 && row.migrateMachineIp?.startsWith("embedded@")
}

function canCompareKeys(row: MigrateListItem) {
  return [-1, 0, 1, 3].includes(row.status) && row.migrateTool === 0 && row.migrateMachineIp?.startsWith("embedded@")
}

function formatBytes(value: unknown) {
  const bytes = Number(value || 0)
  if (!Number.isFinite(bytes) || bytes <= 0) return "0 B"
  const units = ["B", "KB", "MB", "GB", "TB"]
  const index = Math.min(Math.floor(Math.log(bytes) / Math.log(1024)), units.length - 1)
  const amount = bytes / 1024 ** index
  const digits = index === 0 || amount >= 100 ? 0 : amount >= 10 ? 1 : 2
  return `${amount.toFixed(digits)} ${units[index]}`
}

function formatCount(value: unknown) {
  const count = Number(value || 0)
  return Number.isFinite(count) ? Math.trunc(count).toLocaleString("zh-CN") : "0"
}

onMounted(() => {
  const status = route.query.status
  if (status !== undefined && status !== "") {
    const parsed = Number(status)
    if (!Number.isNaN(parsed)) query.status = parsed
  }
  fetchAdmins()
  fetchList()
})
</script>

<template>
  <div class="migrate-list-page">
    <div class="page-header">
      <div class="page-actions">
        <el-button type="success" :icon="Plus" @click="openCreate">添加新迁移</el-button>
      </div>
    </div>

    <el-card shadow="never" class="content-card">
      <el-form :inline="true" class="search-form" @submit.prevent="handleSearch">
        <el-form-item label="源集群名称">
          <el-input v-model="query.sourceAppName" placeholder="源集群名称" clearable style="width: 160px" />
        </el-form-item>
        <el-form-item label="目标集群名称">
          <el-input v-model="query.targetAppName" placeholder="目标集群名称" clearable style="width: 160px" />
        </el-form-item>
        <el-form-item label="操作人">
          <el-select v-model="query.userId" clearable placeholder="操作人" style="width: 180px">
            <el-option
              v-for="admin in admins"
              :key="admin.id"
              :label="`【${admin.id}】${admin.name} ${admin.chName}`"
              :value="admin.id || 0"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="query.status" style="width: 120px">
            <el-option label="全部状态" :value="-2" />
            <el-option label="增量同步" :value="3" />
            <el-option label="全量同步" :value="0" />
            <el-option label="同步结束" :value="1" />
            <el-option label="同步异常" :value="2" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :icon="Search" @click="handleSearch">查询</el-button>
        </el-form-item>
      </el-form>

      <el-table v-loading="loading" :data="items" stripe border style="width: 100%">
        <el-table-column type="index" label="序号" width="60" />
        <el-table-column label="迁移机器" min-width="140" show-overflow-tooltip>
          <template #default="{ row }">{{ row.migrateMachine || row.migrateMachineIp || "-" }}</template>
        </el-table-column>
        <el-table-column prop="userName" label="操作人" min-width="90" />
        <el-table-column label="源数据" min-width="240">
          <template #default="{ row }">
            <div class="migrate-cell-block">
              <div>
                数据源：
                <template v-if="row.sourceAppId <= 0">
                  非Redis管理平台<br>
                  <span class="migrate-cell-muted">{{ row.sourceServers }}</span>
                </template>
                <template v-else>
                  Redis管理平台:
                  <el-link type="primary" :underline="false" @click="openAppDetail(row.sourceAppId)">
                    {{ row.sourceAppName || row.sourceAppId }}
                  </el-link>
                </template>
              </div>
              <div>源类型：redis-{{ row.sourceMigrateTypeDesc || "-" }}</div>
              <div>redis版本：{{ formatRedisVersion(row.redisSourceVersion) || "-" }}</div>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="目标数据" min-width="240">
          <template #default="{ row }">
            <div class="migrate-cell-block">
              <div>
                数据源：
                <template v-if="row.targetAppId <= 0">
                  非Redis管理平台<br>
                  <span class="migrate-cell-muted">{{ row.targetServers }}</span>
                </template>
                <template v-else>
                  Redis管理平台:
                  <el-link type="primary" :underline="false" @click="openAppDetail(row.targetAppId)">
                    {{ row.targetAppName || row.targetAppId }}
                  </el-link>
                </template>
              </div>
              <div>目标类型：redis-{{ row.targetMigrateTypeDesc || "-" }}</div>
              <div>redis版本：{{ formatRedisVersion(row.redisTargetVersion) || "-" }}</div>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="startTime" label="开始时间" min-width="160" />
        <el-table-column prop="endTime" label="结束时间" min-width="160" />
        <el-table-column prop="statusDesc" label="状态" min-width="90" />
        <el-table-column label="查看" width="200">
          <template #default="{ row }">
            <div class="table-actions">
              <el-button type="primary" size="small" @click="showText('迁移日志', () => getMigrateLogApi(row.id))">
                日志
              </el-button>
              <el-button type="primary" size="small" @click="showText('迁移配置', () => getMigrateConfigApi(row.id))">
                配置
              </el-button>
              <el-button
                v-if="canShowProcess(row)"
                type="primary"
                size="small"
                @click="showProcess(row)"
              >
                进度
              </el-button>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="280" fixed="right">
          <template #default="{ row }">
            <el-button v-if="canResync(row)" type="success" size="small" @click="handleResync(row)">
              重新同步
            </el-button>
            <el-button
              v-if="canStop(row)"
              type="danger"
              size="small"
              @click="handleStop(row)"
            >
              停止
            </el-button>
            <el-button v-if="canDelete(row)" type="danger" plain size="small" @click="handleDelete(row)">
              删除
            </el-button>
            <el-button v-if="canCompareKeys(row)" type="primary" plain size="small" @click="handleKeyCompare(row)">
              Key 校验
            </el-button>
          </template>
        </el-table-column>
        <template #empty>无查询相关记录!</template>
      </el-table>

      <div class="pagination-wrap">
        <el-pagination
          :current-page="query.pageNo"
          :page-size="query.pageSize"
          :total="total"
          layout="total, prev, pager, next"
          background
          @current-change="(page: number) => { query.pageNo = page; fetchList() }"
        />
      </div>
    </el-card>

    <el-drawer v-model="textDrawerVisible" :title="textDrawerTitle" size="50%">
      <pre class="migrate-text-pre">{{ (textContent?.lines ?? []).join("\n") }}</pre>
    </el-drawer>

    <el-dialog v-model="processVisible" title="迁移进度" width="720px">
      <template v-if="processData">
        <el-descriptions :column="2" border>
          <el-descriptions-item label="运行状态">{{ processData.running ? "运行中" : "已停止" }}</el-descriptions-item>
          <el-descriptions-item label="当前阶段">{{ processData.stage || processData.message || "启动中" }}</el-descriptions-item>
          <el-descriptions-item label="RDB 已接收">{{ formatBytes(processData.rdbReceivedBytes) }}</el-descriptions-item>
          <el-descriptions-item label="AOF 已接收">{{ formatBytes(processData.aofReceivedBytes) }}</el-descriptions-item>
          <el-descriptions-item label="读取命令">{{ formatCount(processData.entries?.read_count) }}</el-descriptions-item>
          <el-descriptions-item label="写入命令">{{ formatCount(processData.entries?.write_count) }}</el-descriptions-item>
        </el-descriptions>
        <div class="migrate-progress-block">
          <span>RDB 写入进度</span>
          <el-progress :percentage="Number(processData.rdbProgress || 0)" />
        </div>
        <pre v-if="processData.recentLog?.length" class="migrate-text-pre">{{ processData.recentLog.join("\n") }}</pre>
      </template>
      <pre v-else class="migrate-text-pre">{{ processText }}</pre>
      <template #footer>
        <el-button :icon="Refresh" :loading="processRefreshing" @click="refreshProcess">刷新</el-button>
        <el-button type="primary" @click="processVisible = false">关闭</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="keyCompareVisible" title="迁移 Key 数量校验" width="520px">
      <div v-loading="keyCompareLoading" class="key-compare-result">
        <template v-if="keyCompareData">
          <el-alert
            v-if="keyCompareData.running"
            title="同步仍在进行，当前结果为实时快照，仅供进度参考"
            type="warning"
            :closable="false"
            show-icon
          />
          <el-result
            :icon="keyCompareData.consistent ? 'success' : 'warning'"
            :title="keyCompareData.consistent ? 'Key 数量一致' : 'Key 数量不一致'"
          >
            <template #sub-title>
              <el-descriptions :column="1" border>
                <el-descriptions-item label="源集群 Key 数">{{ formatCount(keyCompareData.sourceKeyCount) }}</el-descriptions-item>
                <el-descriptions-item label="目标集群 Key 数">{{ formatCount(keyCompareData.targetKeyCount) }}</el-descriptions-item>
                <el-descriptions-item label="目标 - 源">{{ keyCompareData.difference > 0 ? '+' : '' }}{{ formatCount(keyCompareData.difference) }}</el-descriptions-item>
              </el-descriptions>
            </template>
          </el-result>
        </template>
      </div>
      <template #footer>
        <el-button type="primary" :icon="CircleCheck" @click="keyCompareVisible = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.migrate-progress-block {
  margin-top: 18px;
}

.table-actions {
  display: flex;
  align-items: center;
  justify-content: center;
  flex-wrap: nowrap;
  gap: 8px;
}

.table-actions :deep(.el-button + .el-button) {
  margin-left: 0;
}
</style>
