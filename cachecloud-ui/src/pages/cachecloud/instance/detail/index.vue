<script lang="ts" setup>
import type { InstanceDetail } from "@/api/cachecloud"
import { getInstanceDetailApi } from "@/api/cachecloud"
import { useTabCache } from "@/common/composables/useTabCache"
import { ArrowLeft } from "@element-plus/icons-vue"
import InstanceCommandChartTab from "@/pages/cachecloud/instance/detail/tabs/InstanceCommandChartTab.vue"
import InstanceCommandTab from "@/pages/cachecloud/instance/detail/tabs/InstanceCommandTab.vue"
import InstanceConfigTab from "@/pages/cachecloud/instance/detail/tabs/InstanceConfigTab.vue"
import InstanceClientTab from "@/pages/cachecloud/instance/detail/tabs/InstanceClientTab.vue"
import InstanceHealthTab from "@/pages/cachecloud/instance/detail/tabs/InstanceHealthTab.vue"
import InstanceStatTab from "@/pages/cachecloud/instance/detail/tabs/InstanceStatTab.vue"
import "@/common/assets/styles/app-tab.scss"

/** 兼容旧 tab 参数 */
const TAB_ALIASES: Record<string, string> = {
  instance_config: "instance_configSelect",
  instance_client: "instance_clientList"
}

const OPS_TAB_REDIRECTS: Record<string, string> = {
  instance_ops_config: "app_ops_config",
  instance_ops_command: "app_ops_command",
  instance_ops_restart: "app_ops_restart",
  config: "app_ops_config",
  command: "app_ops_command",
  restart: "app_ops_restart"
}

const route = useRoute()
const router = useRouter()

const instanceId = computed(() => Number(route.params.instanceId))
const resolvedInstanceId = computed(() => detail.value?.instanceId ?? instanceId.value)
const loading = ref(true)
const detail = ref<InstanceDetail | null>(null)
const activeTab = ref("instance_stat")

const appId = computed(() => Number(route.query.appId || detail.value?.appId || 0))
const fromNode = computed(() => route.query.from === "node")
const nodeManageContext = computed(() => fromNode.value || !!detail.value?.nodeManageContext)

const backLabel = computed(() => {
  if (nodeManageContext.value) return "返回列表"
  if (appId.value) return "返回集群"
  return "返回列表"
})

const { onTabChange: markTabVisited, isVisited, resetVisited } = useTabCache(() => activeTab.value)

const tabComponents: Record<string, Component> = {
  instance_stat: InstanceStatTab,
  instance_configSelect: InstanceConfigTab,
  instance_clientList: InstanceClientTab,
  instance_advancedAnalysis: InstanceCommandChartTab,
  instance_command: InstanceCommandTab,
  instance_health: InstanceHealthTab
}

const activeTabComponent = computed(() => {
  const key = activeTab.value
  if (!key || !isVisited(key)) return null
  return tabComponents[key] ?? null
})

const activeTabProps = computed(() => {
  const key = activeTab.value
  if (key === "instance_configSelect") {
    return {
      instanceId: resolvedInstanceId.value,
      appId: appId.value,
      hostPort: detail.value?.hostPort
    }
  }
  if (key === "instance_health") {
    return {
      instanceId: resolvedInstanceId.value,
      hostPort: detail.value?.hostPort
    }
  }
  return { instanceId: resolvedInstanceId.value }
})

const activeTabListeners = computed(() => {
  const key = activeTab.value
  if (key === "instance_stat") {
    return { openApp: goAppDetail }
  }
  return {}
})

function normalizeTabKey(raw?: string) {
  if (!raw) return undefined
  return TAB_ALIASES[raw] ?? raw
}

function resolveQueryTab() {
  const raw = (route.query.tab ?? route.query.tabTag) as string | undefined
  return normalizeTabKey(raw)
}

function redirectOpsTabToApp(rawTab?: string) {
  const mapped = rawTab ? OPS_TAB_REDIRECTS[rawTab] : undefined
  if (!mapped || !appId.value) return false
  router.replace({
    path: `/app/detail/${appId.value}`,
    query: { tab: mapped }
  })
  return true
}

async function fetchDetail() {
  loading.value = true
  try {
    const rawTab = (route.query.tab ?? route.query.tabTag) as string | undefined
    if (redirectOpsTabToApp(rawTab)) {
      loading.value = false
      return
    }

    const { data } = await getInstanceDetailApi(instanceId.value, {
      appId: appId.value || undefined,
      fromNode: fromNode.value
    })
    detail.value = data
    const queryTab = resolveQueryTab()
    const valid = data?.tabs.some(t => t.key === queryTab)
    activeTab.value = valid ? queryTab! : (data?.defaultTab || "instance_stat")
    markTabVisited(activeTab.value)
  } finally {
    loading.value = false
  }
}

function syncTab(tab: string) {
  const query: Record<string, string> = {}
  Object.entries(route.query).forEach(([k, v]) => {
    if (v != null && k !== "tab" && k !== "tabTag") query[k] = String(v)
  })
  query.tab = tab
  router.replace({ path: route.path, query })
}

function handleTabChange(tab: string | number | boolean) {
  const key = String(tab)
  activeTab.value = key
  markTabVisited(key)
  syncTab(key)
}

function goBack() {
  if (nodeManageContext.value) {
    router.push("/external/redis/list")
    return
  }
  if (appId.value) {
    router.push(`/app/detail/${appId.value}`)
    return
  }
  router.push("/app/list")
}

function goAppDetail(targetAppId?: number) {
  const id = typeof targetAppId === "number" && targetAppId > 0 ? targetAppId : appId.value
  if (id) router.push({ path: `/app/detail/${id}`, query: { tab: "app_topology" } })
}

watch(() => route.params.instanceId, () => {
  resetVisited()
  fetchDetail()
}, { immediate: true })

watch(() => resolveQueryTab(), (tab) => {
  if (!tab || !detail.value) return
  if (!detail.value.tabs.some(t => t.key === tab)) return
  if (tab === activeTab.value) return
  activeTab.value = tab
  markTabVisited(tab)
})
</script>

<template>
  <div v-loading="loading" class="instance-detail-page">
    <template v-if="detail">
      <div
        class="instance-page-header"
        :data-page-title="`节点详情 ${detail.hostPort} (ID: ${detail.instanceId})`"
      >
        <el-button :icon="ArrowLeft" link @click="goBack">{{ backLabel }}</el-button>
        <div class="instance-page-header__meta">
          <el-tag size="small" type="info">{{ detail.hostPort }}</el-tag>
          <el-tag size="small" effect="plain">ID: {{ detail.instanceId }}</el-tag>
        </div>
      </div>

      <el-card shadow="never" class="app-detail-tabs-card instance-detail-panel">
        <el-tabs v-model="activeTab" class="instance-detail-panel__tabs" @tab-change="handleTabChange">
          <el-tab-pane
            v-for="tab in detail.tabs"
            :key="tab.key"
            :label="tab.label"
            :name="tab.key"
          />
        </el-tabs>
        <div class="instance-detail-panel__body">
          <keep-alive :max="detail.tabs.length">
            <component
              :is="activeTabComponent"
              v-if="activeTabComponent"
              :key="`${activeTab}-${resolvedInstanceId}`"
              v-bind="activeTabProps"
              v-on="activeTabListeners"
            />
          </keep-alive>
        </div>
      </el-card>
    </template>
  </div>
</template>

<style lang="scss" scoped>
.instance-detail-page {
  padding: 16px;
}

.instance-page-header {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px 12px;
  margin-bottom: 12px;
}

.instance-page-header__meta {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
}

.instance-detail-panel {
  :deep(.el-card__body) {
    padding: 0;
  }
}

.instance-detail-panel__tabs {
  :deep(.el-tabs__header) {
    padding-left: 20px;
    padding-right: 16px;
    margin-bottom: 0;
  }

  :deep(.el-tabs__item) {
    height: 38px;
    line-height: 38px;
  }
}

.instance-detail-panel__body {
  padding: 14px 20px 18px;
  min-height: 280px;
}
</style>
