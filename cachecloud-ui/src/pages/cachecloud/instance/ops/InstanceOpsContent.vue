<script lang="ts" setup>
import type { CommandCheckDetailRow, CommandCheckRecord, ConfigCheckDetailRow, ConfigCheckRecord, RestartRecord } from "@/api/cachecloud"
import { Search } from "@element-plus/icons-vue"
import {
  getCommandCheckDetailApi,
  getCommandCheckListApi,
  getConfigCheckDetailApi,
  getConfigCheckListApi,
  getInstanceOpsOptionsApi,
  getRestartRecordListApi,
  runCommandCheckApi,
  runConfigCheckApi,
  stopRestartApi
} from "@/api/cachecloud"
import { formatRedisVersion } from "@/common/utils/redis-version"

const props = defineProps<{
  panel: "config" | "command" | "restart"
  /** 集群详情内嵌时默认按集群编码筛选重启记录 */
  defaultAppId?: number
}>()

const router = useRouter()

const optionsLoading = ref(false)
const configLoading = ref(false)
const commandLoading = ref(false)
const restartLoading = ref(false)
const runningCheck = ref(false)
const runningCommandCheck = ref(false)
const detailLoading = ref(false)
const commandDetailLoading = ref(false)
const detailVisible = ref(false)
const commandDetailVisible = ref(false)
const loaded = ref(false)

const redisVersions = ref<{ id: number, name: string }[]>([])
const compareTypes = ref<{ type: number, label: string }[]>([])
const configRecords = ref<ConfigCheckRecord[]>([])
const commandRecords = ref<CommandCheckRecord[]>([])
const detailRows = ref<ConfigCheckDetailRow[]>([])
const commandDetailRows = ref<CommandCheckDetailRow[]>([])
const restartRecords = ref<RestartRecord[]>([])
const restartTotal = ref(0)

const checkForm = reactive({
  versionId: 0,
  configName: "",
  compareType: 1,
  expectValue: ""
})

const commandForm = reactive({
  machineIps: "",
  podIp: "",
  command: "bgsave"
})

const restartQuery = reactive({
  appId: "",
  pageNo: 1,
  pageSize: 10
})

function statusTagType(status?: number) {
  if (status === 2) return "success"
  if (status === 3) return "danger"
  if (status === 1 || status === 5) return "warning"
  if (status === 4) return "info"
  return "info"
}

function rowClassName({ row }: { row: RestartRecord }) {
  if (row.status === 3) return "row-fail"
  if (row.status === 4) return "row-info"
  if (row.status === 1 || row.status === 5) return "row-running"
  if (row.status === 6) return "row-stopped"
  return ""
}

async function fetchOptions() {
  optionsLoading.value = true
  try {
    const { data } = await getInstanceOpsOptionsApi()
    redisVersions.value = data.redisVersions ?? []
    compareTypes.value = data.compareTypes ?? []
    if (compareTypes.value.length && !checkForm.compareType) {
      checkForm.compareType = compareTypes.value[0].type
    }
  } finally {
    optionsLoading.value = false
  }
}

async function fetchConfigRecords() {
  configLoading.value = true
  try {
    const { data } = await getConfigCheckListApi()
    configRecords.value = data ?? []
  } finally {
    configLoading.value = false
  }
}

async function fetchCommandRecords() {
  commandLoading.value = true
  try {
    const { data } = await getCommandCheckListApi()
    commandRecords.value = data ?? []
  } finally {
    commandLoading.value = false
  }
}

async function handleRunCheck() {
  if (!checkForm.configName.trim()) {
    ElMessage.warning("请填写配置名")
    return
  }
  runningCheck.value = true
  try {
    await runConfigCheckApi({
      versionId: checkForm.versionId || undefined,
      configName: checkForm.configName.trim(),
      compareType: checkForm.compareType,
      expectValue: checkForm.expectValue
    })
    ElMessage.success("检测已执行，正在刷新结果")
    await fetchConfigRecords()
  } finally {
    runningCheck.value = false
  }
}

async function showDetail(uuid: string) {
  detailVisible.value = true
  detailLoading.value = true
  try {
    const { data } = await getConfigCheckDetailApi(uuid)
    detailRows.value = data.rows ?? []
  } finally {
    detailLoading.value = false
  }
}

async function showCommandDetail(uuid: string) {
  commandDetailVisible.value = true
  commandDetailLoading.value = true
  try {
    const { data } = await getCommandCheckDetailApi(uuid)
    commandDetailRows.value = data?.rows ?? []
  } finally {
    commandDetailLoading.value = false
  }
}

async function handleRunCommandCheck() {
  if (!commandForm.command.trim()) {
    ElMessage.warning("请选择命令")
    return
  }
  runningCommandCheck.value = true
  try {
    await runCommandCheckApi({
      machineIps: commandForm.machineIps.trim() || undefined,
      podIp: commandForm.podIp.trim() || undefined,
      command: commandForm.command
    })
    ElMessage.success("检测已执行，正在刷新结果")
    await fetchCommandRecords()
  } finally {
    runningCommandCheck.value = false
  }
}

async function fetchRestartRecords() {
  restartLoading.value = true
  try {
    const appId = restartQuery.appId.trim()
    const { data } = await getRestartRecordListApi({
      appId: appId || undefined,
      pageNo: restartQuery.pageNo,
      pageSize: restartQuery.pageSize
    })
    restartRecords.value = data.items ?? []
    restartTotal.value = data.totalCount ?? 0
  } finally {
    restartLoading.value = false
  }
}

function handleRestartSearch() {
  restartQuery.pageNo = 1
  fetchRestartRecords()
}

function handleRestartPageChange(page: number) {
  restartQuery.pageNo = page
  fetchRestartRecords()
}

function handleRestartSizeChange(size: number) {
  restartQuery.pageSize = size
  restartQuery.pageNo = 1
  fetchRestartRecords()
}

async function handleStopRestart(appId?: number) {
  if (!appId) return
  try {
    await ElMessageBox.confirm("确认停止重启任务吗？", "提示", { type: "warning" })
    const { data } = await stopRestartApi(appId)
    ElMessage.success(data || "操作成功")
    fetchRestartRecords()
  } catch {
    // cancelled
  }
}

function openInstanceLog(instanceId: number) {
  router.push({ path: `/external/redis/detail/${instanceId}` })
}

function goAppDetail(appId: number) {
  router.push({ path: `/app/detail/${appId}`, query: { tab: "app_stat" } })
}

async function loadPanel() {
  if (props.panel === "config") {
    await Promise.all([fetchOptions(), fetchConfigRecords()])
  } else if (props.panel === "command") {
    await fetchCommandRecords()
  } else if (props.panel === "restart") {
    if (props.defaultAppId && props.defaultAppId > 0) {
      restartQuery.appId = String(props.defaultAppId)
    }
    await fetchRestartRecords()
  }
  loaded.value = true
}

watch(() => props.panel, () => {
  if (!loaded.value) {
    loadPanel()
  }
}, { immediate: true })
</script>

<template>
  <div class="instance-ops-content">
    <template v-if="panel === 'config'">
      <el-form :inline="true" class="check-form" @submit.prevent="handleRunCheck">
        <el-form-item label="redis版本">
          <el-select v-model="checkForm.versionId" style="width: 180px" :loading="optionsLoading">
            <el-option label="所有版本" :value="0" />
            <el-option
              v-for="item in redisVersions"
              :key="item.id"
              :label="formatRedisVersion(item.name)"
              :value="item.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="配置项">
          <el-input v-model="checkForm.configName" placeholder="配置名" style="width: 160px" />
        </el-form-item>
        <el-form-item label="比较">
          <el-select v-model="checkForm.compareType" style="width: 120px">
            <el-option
              v-for="item in compareTypes"
              :key="item.type"
              :label="item.label"
              :value="item.type"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="比较值">
          <el-input v-model="checkForm.expectValue" style="width: 120px" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="runningCheck" @click="handleRunCheck">
            开始检测
          </el-button>
        </el-form-item>
      </el-form>

      <el-table v-loading="configLoading" :data="configRecords" stripe border>
        <el-table-column label="redis版本" min-width="110">
          <template #default="{ row }">
            {{ formatRedisVersion(row.versionName) || "-" }}
          </template>
        </el-table-column>
        <el-table-column prop="userName" label="操作人" width="100" />
        <el-table-column prop="createTime" label="检测时间" width="170" />
        <el-table-column label="检测条件" min-width="220">
          <template #default="{ row }">
            {{ row.configName }} {{ row.compareTypeLabel }} {{ row.expectValue }}
          </template>
        </el-table-column>
        <el-table-column label="是否异常" width="100" align="center">
          <template #default="{ row }">
            <el-tag :type="row.success ? 'success' : 'danger'" size="small">
              {{ row.success ? "否" : "是" }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="异常查看" width="110" align="center">
          <template #default="{ row }">
            <el-button
              v-if="!row.success"
              type="warning"
              size="small"
              @click="showDetail(row.key)"
            >
              查看详情
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </template>

    <template v-else-if="panel === 'command'">
      <el-form :inline="true" class="check-form" @submit.prevent="handleRunCommandCheck">
        <el-form-item label="宿主机 IP">
          <el-input v-model="commandForm.machineIps" placeholder="可选，逗号分隔" style="width: 200px" />
        </el-form-item>
        <el-form-item label="Pod IP">
          <el-input v-model="commandForm.podIp" placeholder="可选" style="width: 160px" />
        </el-form-item>
        <el-form-item label="命令">
          <el-select v-model="commandForm.command" style="width: 160px">
            <el-option label="bgsave" value="bgsave" />
            <el-option label="bgrewriteaof" value="bgrewriteaof" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="runningCommandCheck" @click="handleRunCommandCheck">
            开始检测
          </el-button>
        </el-form-item>
      </el-form>

      <el-table v-loading="commandLoading" :data="commandRecords" stripe border>
        <el-table-column prop="userName" label="操作人" width="100" />
        <el-table-column prop="createTime" label="检测时间" width="170" />
        <el-table-column prop="command" label="命令" width="120" />
        <el-table-column label="范围" min-width="200">
          <template #default="{ row }">
            <span v-if="row.machineIps">机器: {{ row.machineIps }}</span>
            <span v-else-if="row.podIp">Pod: {{ row.podIp }}</span>
            <span v-else class="text-muted">全部节点</span>
          </template>
        </el-table-column>
        <el-table-column label="是否异常" width="100" align="center">
          <template #default="{ row }">
            <el-tag :type="row.success ? 'success' : 'danger'" size="small">
              {{ row.success ? "否" : "是" }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="异常查看" width="110" align="center">
          <template #default="{ row }">
            <el-button
              v-if="!row.success"
              type="warning"
              size="small"
              @click="showCommandDetail(row.key)"
            >
              查看详情
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </template>

    <template v-else>
      <el-form :inline="true" class="restart-form" @submit.prevent="handleRestartSearch">
        <el-form-item label="集群编码">
          <el-input v-model="restartQuery.appId" placeholder="集群编码" style="width: 160px" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :icon="Search" @click="handleRestartSearch">
            查询
          </el-button>
        </el-form-item>
      </el-form>

      <el-table
        v-loading="restartLoading"
        :data="restartRecords"
        stripe
        border
        :row-class-name="rowClassName"
      >
        <el-table-column prop="id" label="记录ID" width="90" />
        <el-table-column prop="userName" label="操作人" width="100" />
        <el-table-column prop="appId" label="集群编码" width="90" />
        <el-table-column prop="appName" label="集群名称" min-width="120" show-overflow-tooltip />
        <el-table-column label="涉及节点" min-width="180">
          <template #default="{ row }">
            <div v-for="inst in row.instances" :key="inst.instanceId" class="instance-line">
              <el-link type="primary" :underline="false" @click="openInstanceLog(inst.instanceId)">
                {{ inst.instanceId }}
              </el-link>
              <span class="text-muted">({{ inst.hostPort }})</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="operateTypeLabel" label="重启类型" width="150" />
        <el-table-column label="日志" min-width="200">
          <template #default="{ row }">
            <div v-for="(log, idx) in row.logs" :key="idx" class="log-line" v-html="log" />
          </template>
        </el-table-column>
        <el-table-column prop="startTime" label="开始时间" width="170" />
        <el-table-column prop="endTime" label="结束时间" width="170" />
        <el-table-column label="状态" width="130">
          <template #default="{ row }">
            <el-tag :type="statusTagType(row.status)" size="small">
              {{ row.statusLabel }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="100" fixed="right">
          <template #default="{ row }">
            <el-button
              v-if="row.stoppable"
              type="danger"
              size="small"
              @click="handleStopRestart(row.appId)"
            >
              停止
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="pagination-wrap">
        <el-pagination
          :current-page="restartQuery.pageNo"
          :page-size="restartQuery.pageSize"
          :total="restartTotal"
          :page-sizes="[10, 20, 50]"
          layout="total, sizes, prev, pager, next"
          background
          @current-change="handleRestartPageChange"
          @size-change="handleRestartSizeChange"
        />
      </div>
    </template>

    <el-drawer v-model="detailVisible" title="配置检测异常详情" size="72%">
      <el-table v-loading="detailLoading" :data="detailRows" stripe border>
        <el-table-column prop="instanceId" label="节点 ID" width="90">
          <template #default="{ row }">
            <el-link type="primary" :underline="false" @click="openInstanceLog(row.instanceId)">
              {{ row.instanceId }}
            </el-link>
          </template>
        </el-table-column>
        <el-table-column prop="hostPort" label="节点" width="160" />
        <el-table-column label="版本" min-width="100">
          <template #default="{ row }">
            {{ formatRedisVersion(row.versionName) || "-" }}
          </template>
        </el-table-column>
        <el-table-column label="集群编码" width="90">
          <template #default="{ row }">
            <el-link type="primary" :underline="false" @click="goAppDetail(row.appId)">
              {{ row.appId }}
            </el-link>
          </template>
        </el-table-column>
        <el-table-column prop="appName" label="集群名称" min-width="120" />
        <el-table-column prop="createTime" label="检测时间" width="170" />
        <el-table-column prop="configName" label="配置项" width="120" />
        <el-table-column label="异常信息" min-width="220">
          <template #default="{ row }">
            <div>期望：{{ row.compareTypeLabel }} {{ row.expectValue }}</div>
            <div>实际：{{ row.realValue }}</div>
          </template>
        </el-table-column>
      </el-table>
    </el-drawer>

    <el-drawer v-model="commandDetailVisible" title="命令检测异常详情" size="60%">
      <el-table v-loading="commandDetailLoading" :data="commandDetailRows" stripe border>
        <el-table-column prop="instanceId" label="节点 ID" width="90">
          <template #default="{ row }">
            <el-link type="primary" :underline="false" @click="openInstanceLog(row.instanceId)">
              {{ row.instanceId }}
            </el-link>
          </template>
        </el-table-column>
        <el-table-column prop="hostPort" label="节点" width="160" />
        <el-table-column prop="createTime" label="检测时间" width="170" />
        <el-table-column prop="command" label="命令" width="120" />
        <el-table-column prop="message" label="异常信息" min-width="220" />
      </el-table>
    </el-drawer>
  </div>
</template>

<style lang="scss" scoped>
.check-form,
.restart-form {
  margin-bottom: 16px;
}

.instance-line,
.log-line {
  font-size: 12px;
  line-height: 1.6;
}

.text-muted {
  color: var(--el-text-color-secondary);
}

.pagination-wrap {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;
}

:deep(.row-fail) {
  color: var(--el-color-danger);
}

:deep(.row-info) {
  color: var(--el-color-primary);
}

:deep(.row-running) {
  color: #e5680f;
}

:deep(.row-stopped) {
  color: #c3680f;
}
</style>
