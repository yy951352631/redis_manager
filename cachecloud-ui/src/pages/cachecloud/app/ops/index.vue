<script lang="ts" setup>
import type { AppDetail, AppOpsMachineItem, AppPasswordInfo } from "@/api/cachecloud"
import {
  checkAppPasswordApi,
  getAppDetailApi,
  getAppOpsMachinesApi,
  getAppPasswordApi,
  updateAppPasswordApi
} from "@/api/cachecloud"
import { formatClusterNo } from "@/common/utils/cluster-no"
import { ArrowLeft, Hide, View } from "@element-plus/icons-vue"
import FaultDiagnosticPanel from "@/pages/cachecloud/app/components/FaultDiagnosticPanel.vue"
import OpsTopologyTab from "@/pages/cachecloud/app/detail/tabs/OpsTopologyTab.vue"
import { useHostCapability } from "@/common/composables/useHostCapability"
import "@/common/assets/styles/app-ops.scss"

const route = useRoute()
const router = useRouter()
const appId = computed(() => Number(route.params.appId))

const DEFAULT_TAB = "topology"
const activeTab = ref(DEFAULT_TAB)
const loading = ref(false)
const appDetail = ref<AppDetail | null>(null)
const machines = ref<AppOpsMachineItem[]>([])
const passwordInfo = ref<AppPasswordInfo | null>(null)
const newPassword = ref("")
const passwordVisible = ref(false)

const hostCap = useHostCapability(() => appId.value)

const showMachineTab = computed(() => hostCap.showMachineTab.value)

const validTabs = computed(() => {
  const tabs = ["topology", "fault", "password"]
  if (showMachineTab.value) tabs.push("machine")
  return tabs
})

const appTypeDesc = computed(() => appDetail.value?.typeDesc)

async function fetchAppDetail() {
  const { data } = await getAppDetailApi(appId.value)
  appDetail.value = data
}

async function fetchMachines() {
  const { data } = await getAppOpsMachinesApi(appId.value)
  machines.value = data ?? []
}

async function fetchPassword() {
  const { data } = await getAppPasswordApi(appId.value)
  passwordInfo.value = data
  newPassword.value = data?.customPassword || data?.appPassword || ""
}

function resolveInitialTab() {
  const tab = String(route.query.tab || DEFAULT_TAB)
  if (tab === "instance") {
    router.replace({ path: `/app/detail/${appId.value}`, query: { tab: "app_topology" } })
    return null
  }
  return validTabs.value.includes(tab) ? tab : DEFAULT_TAB
}

async function handleTabChange(tab: string | number | boolean) {
  const key = String(tab)
  activeTab.value = key
  router.replace({ path: route.path, query: { ...route.query, tab: key } })
  if (key === "machine" && !machines.value.length) await fetchMachines()
  if (key === "password" && !passwordInfo.value) await fetchPassword()
}

async function handleUpdatePassword() {
  if (!newPassword.value) {
    ElMessage.warning("请输入密码")
    return
  }
  const { data } = await updateAppPasswordApi(appId.value, newPassword.value)
  if (!data?.success) {
    ElMessage.error("密码更新失败")
    return
  }
  ElMessage.success("密码已更新")
  fetchPassword()
}

async function handleCheckPassword() {
  const { data } = await checkAppPasswordApi(appId.value)
  if (data?.success) {
    ElMessage.success("集群密码有效且一致")
  } else {
    ElMessage.error("集群密码无效或不一致")
  }
}

function goAppList() {
  router.push("/app/list")
}

function goAppDetail() {
  router.push({ path: `/app/detail/${appId.value}`, query: { tab: "app_topology" } })
}

function machineMemLabel(m: AppOpsMachineItem) {
  const usedGb = m.memoryTotal && m.memoryFree
    ? ((m.memoryTotal - m.memoryFree) / 1024 / 1024 / 1024).toFixed(2)
    : (m.usedMemory / 1024).toFixed(2)
  const totalGb = m.memoryTotal
    ? (m.memoryTotal / 1024 / 1024 / 1024).toFixed(2)
    : (m.machineMemory / 1024).toFixed(2)
  return `${usedGb}G Used/${totalGb}G Total`
}

watch(() => route.params.appId, async () => {
  machines.value = []
  passwordInfo.value = null
  appDetail.value = null
  loading.value = true
  try {
    await fetchAppDetail()
    await hostCap.fetchCapability()
    await fetchMachines()
    const tab = resolveInitialTab()
    if (!tab) return
    activeTab.value = tab
    if (route.query.tab !== tab) {
      router.replace({ path: route.path, query: { ...route.query, tab } })
    }
    if (tab === "password") await fetchPassword()
  } finally {
    loading.value = false
  }
}, { immediate: true })
</script>

<template>
  <div v-loading="loading" class="app-ops-page">
    <div
      class="page-header"
      :data-page-title="`集群运维 ${appDetail?.appName || appId} (集群编码: ${formatClusterNo(appDetail?.clusterNo, appId)})`"
    >
      <div class="page-header__actions">
        <el-button :icon="ArrowLeft" link @click="goAppList">返回列表</el-button>
        <el-button type="primary" size="small" @click="goAppDetail">节点列表</el-button>
      </div>
    </div>

    <el-card shadow="never">
      <el-tabs v-model="activeTab" @tab-change="handleTabChange">
        <el-tab-pane label="集群拓扑诊断" name="topology" lazy>
          <OpsTopologyTab v-if="appDetail" :app-id="appId" :detail="appDetail" />
        </el-tab-pane>

        <el-tab-pane label="故障诊断" name="fault" lazy>
          <FaultDiagnosticPanel
            :app-id="appId"
            :cluster-no="appDetail?.clusterNo"
            :app-name="appDetail?.appName"
            :app-type-desc="appTypeDesc"
            :auto-run="route.query.autoRun === '1'"
          />
        </el-tab-pane>

        <el-tab-pane label="集群密码修改" name="password" lazy>
          <div class="app-password-panel">
            <div class="app-password-panel__intro">
              <h4 class="app-password-panel__title">Redis 密码</h4>
              <p class="app-password-panel__hint">
                平台使用该密码连接 Redis 进行采集与运维。修改后建议点击「校验」确认各节点密码一致。
              </p>
            </div>

            <div class="app-password-panel__form">
              <label class="app-password-panel__label" for="app-ops-password">密码</label>
              <div class="app-password-input-wrap">
                <el-input
                  id="app-ops-password"
                  v-model="newPassword"
                  :type="passwordVisible ? 'text' : 'password'"
                  class="app-password-input"
                  placeholder="请输入 Redis 密码"
                  autocomplete="off"
                />
                <button
                  type="button"
                  class="app-password-toggle"
                  :title="passwordVisible ? '隐藏密码' : '显示密码'"
                  :aria-label="passwordVisible ? '隐藏密码' : '显示密码'"
                  @click="passwordVisible = !passwordVisible"
                >
                  <el-icon><component :is="passwordVisible ? Hide : View" /></el-icon>
                </button>
              </div>
              <div class="app-password-panel__actions">
                <el-button type="primary" @click="handleUpdatePassword">更新</el-button>
                <el-button @click="handleCheckPassword">校验</el-button>
              </div>
            </div>
          </div>
        </el-tab-pane>

        <el-tab-pane v-if="showMachineTab" label="集群机器列表" name="machine" lazy>
          <el-table :data="machines" stripe border size="small">
            <el-table-column prop="ip" label="ip" width="140" />
            <el-table-column label="内存使用率" min-width="200">
              <template #default="{ row }">
                <div class="app-ops-machine-bar">
                  <div
                    class="app-ops-machine-bar__inner"
                    :class="Number(row.memoryUsageRatio) >= 80 ? 'is-danger' : 'is-success'"
                    :style="{ width: `${Math.min(100, Number(row.memoryUsageRatio) || 0)}%` }"
                  >
                    {{ machineMemLabel(row) }}
                  </div>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="已分配内存" min-width="200">
              <template #default="{ row }">
                <div class="app-ops-machine-bar">
                  <div
                    class="app-ops-machine-bar__inner"
                    :class="Number(row.memoryAllocatedRatio) >= 80 ? 'is-danger' : 'is-success'"
                    :style="{ width: `${Math.min(100, Number(row.memoryAllocatedRatio) || 0)}%` }"
                  >
                    {{ row.memoryAllocated ? `${(row.memoryAllocated / 1024).toFixed(2)}G Used` : "-" }}
                  </div>
                </div>
              </template>
            </el-table-column>
            <el-table-column prop="cpuUsage" label="CPU使用率" width="100" />
            <el-table-column prop="traffic" label="网络流量" width="100" />
            <el-table-column prop="load" label="机器负载" width="100" />
            <el-table-column prop="modifyTime" label="最后统计时间" width="150" />
            <el-table-column label="是否虚机" width="120">
              <template #default="{ row }">
                <template v-if="row.virtual">
                  是<br>物理机:{{ row.realIp || "-" }}
                </template>
                <span v-else>否</span>
              </template>
            </el-table-column>
            <el-table-column prop="roomName" label="机房" width="100" />
          </el-table>
        </el-tab-pane>
      </el-tabs>
    </el-card>
  </div>
</template>
