<script lang="ts" setup>
import type { AppDetail, AppDetailTab } from "@/api/cachecloud"
import { ArrowLeft } from "@element-plus/icons-vue"
import { getAppDetailApi } from "@/api/cachecloud"
import { useHostCapability } from "@/common/composables/useHostCapability"
import { useTabCache } from "@/common/composables/useTabCache"
import { formatClusterNo } from "@/common/utils/cluster-no"
import { sortDetailTabs } from "./detail-tab-order"
import { TAB_RECLICK } from "./tab-reclick"
import PlaceholderTab from "./tabs/PlaceholderTab.vue"
import "@/common/assets/styles/app-tab.scss"

function lazyTab(loader: () => Promise<{ default: Component }>) {
  return defineAsyncComponent({
    loader,
    delay: 120,
    timeout: 60000
  })
}

const tabComponents: Record<string, Component> = {
  app_stat: lazyTab(() => import("./tabs/StatTab.vue")),
  app_topology: lazyTab(() => import("./tabs/TopologyTab.vue")),
  app_detail: lazyTab(() => import("./tabs/AppInfoTab.vue")),
  app_clientList: lazyTab(() => import("./tabs/ClientListTab.vue")),
  app_command_analysis: lazyTab(() => import("./tabs/CommandAnalysisTab.vue")),
  app_latency: lazyTab(() => import("./tabs/LatencyTab.vue")),
  app_top_pic: lazyTab(() => import("./tabs/MachineTopologyTab.vue")),
  app_daily: lazyTab(() => import("./tabs/DailyTab.vue")),
  app_key_analysis: lazyTab(() => import("./tabs/KeyAnalysisTab.vue")),
  app_alert_record: lazyTab(() => import("./tabs/AppAlertRecordTab.vue")),
  app_ops_topology: lazyTab(() => import("./tabs/OpsTopologyTab.vue")),
  app_ops_config_consistency: lazyTab(() => import("./tabs/ConfigConsistencyTab.vue")),
  app_ops_fault: lazyTab(() => import("./tabs/OpsFaultTab.vue")),
  app_ops_config: lazyTab(() => import("./tabs/AppOpsConfigTab.vue")),
  app_ops_command: lazyTab(() => import("./tabs/AppOpsCommandTab.vue")),
  app_ops_restart: lazyTab(() => import("./tabs/AppOpsRestartTab.vue"))
}

const KEEP_ALIVE_MAX = 5

const route = useRoute()
const router = useRouter()

const loading = ref(true)
const detail = ref<AppDetail | null>(null)
const activeTab = ref("")

const appId = computed(() => Number(route.params.appId))

const hostCap = useHostCapability(() => appId.value)

const OPS_TABS: AppDetailTab[] = [
  { key: "app_ops_topology", label: "集群拓扑诊断" },
  { key: "app_ops_config_consistency", label: "配置一致性校验" },
  { key: "app_ops_fault", label: "故障诊断" }
]

const INSTANCE_OPS_TABS: AppDetailTab[] = [
  { key: "app_ops_config", label: "配置检测" },
  { key: "app_ops_command", label: "命令检测" },
  { key: "app_ops_restart", label: "重启记录" }
]

const OPS_TAB_ALIASES: Record<string, string> = {
  topology: "app_ops_topology",
  configConsistency: "app_ops_config_consistency",
  config_consistency: "app_ops_config_consistency",
  fault: "app_ops_fault",
  config: "app_ops_config",
  command: "app_ops_command",
  restart: "app_ops_restart",
  instance_ops_config: "app_ops_config",
  instance_ops_command: "app_ops_command",
  instance_ops_restart: "app_ops_restart"
}

function normalizeTabKey(tab?: string) {
  if (!tab) return tab
  return OPS_TAB_ALIASES[tab] || tab
}

function mergeOpsTabs(tabs: AppDetailTab[]) {
  const result = [...tabs]
  for (const opsTab of OPS_TABS) {
    if (!result.some(t => t.key === opsTab.key)) {
      result.push(opsTab)
    }
  }
  return result
}

const displayTabs = computed(() => {
  let tabs = sortDetailTabs(mergeOpsTabs(detail.value?.tabs ?? []))
  if (hostCap.showMachineTab.value) {
    for (const opsTab of INSTANCE_OPS_TABS) {
      if (!tabs.some(t => t.key === opsTab.key)) {
        tabs.push(opsTab)
      }
    }
  } else {
    tabs = tabs.filter(t => !INSTANCE_OPS_TABS.some(ops => ops.key === t.key))
  }
  return sortDetailTabs(tabs)
})

const { onTabChange: markTabVisited, isVisited, resetVisited } = useTabCache(() => activeTab.value)

const activeTabMeta = computed(() => displayTabs.value.find(t => t.key === activeTab.value))

const tabReclickSeq = ref(0)
provide(TAB_RECLICK, tabReclickSeq)

const activeTabComponent = computed(() => {
  const key = activeTab.value
  if (!key || !isVisited(key)) return null
  return tabComponents[key] ?? PlaceholderTab
})

function syncTabToRoute(tabKey: string) {
  router.replace({ path: route.path, query: { ...route.query, tab: tabKey } })
}

/**
 * el-tabs 先 emit tabClick 再 setCurrentName，因此这里读到的 activeTab 仍是点击前的值：
 * 两者相等就说明用户点的是当前这个 tab，属于「重复点击」。
 */
function handleTabClick(pane: { paneName?: string | number }) {
  if (pane?.paneName !== undefined && String(pane.paneName) === activeTab.value) {
    tabReclickSeq.value++
  }
}

function handleTabChange(tabKey: string | number | boolean) {
  const key = String(tabKey)
  activeTab.value = key
  markTabVisited(key)
  syncTabToRoute(key)
}

function goBack() {
  router.push("/app/list")
}

async function fetchDetail() {
  loading.value = true
  try {
    const { data } = await getAppDetailApi(appId.value)
    detail.value = data
    const queryTab = normalizeTabKey(route.query.tab as string | undefined)
    const tabKeys = displayTabs.value.map(t => t.key)
    const validTab = queryTab && tabKeys.includes(queryTab)
    activeTab.value = validTab ? queryTab! : (data.defaultTab || tabKeys[0] || "")
    markTabVisited(activeTab.value)
    if (queryTab && queryTab !== route.query.tab) {
      syncTabToRoute(activeTab.value)
    }
  } catch {
    detail.value = null
  } finally {
    loading.value = false
  }
  void hostCap.fetchCapability().then(() => {
    if (!detail.value) return
    const queryTab = normalizeTabKey(route.query.tab as string | undefined)
    if (!queryTab || !displayTabs.value.some(t => t.key === queryTab)) return
    if (activeTab.value === queryTab) return
    activeTab.value = queryTab
    markTabVisited(queryTab)
  })
}

watch(() => route.params.appId, () => {
  resetVisited()
  fetchDetail()
}, { immediate: true })

watch(displayTabs, (tabs) => {
  if (!tabs.length || tabs.some(t => t.key === activeTab.value)) return
  const fallback = tabs[0]?.key
  if (!fallback) return
  activeTab.value = fallback
  markTabVisited(fallback)
  syncTabToRoute(fallback)
})

watch(() => route.query.tab as string | undefined, (tab) => {
  if (!tab || !detail.value) return
  const key = normalizeTabKey(tab)
  if (!key || !displayTabs.value.some(t => t.key === key)) return
  if (key === activeTab.value) return
  activeTab.value = key
  markTabVisited(key)
  if (key !== tab) {
    syncTabToRoute(key)
  }
})
</script>

<template>
  <div v-loading="loading" class="app-detail-page">
    <template v-if="detail">
      <div
        class="page-header"
        :data-page-title="`${detail.appName} (集群编码: ${formatClusterNo(detail.clusterNo, detail.appId)})`"
      >
        <el-button :icon="ArrowLeft" link @click="goBack">返回列表</el-button>
        <div class="page-header__meta">
          <span class="page-header__name">{{ detail.appName }}</span>
          <span
            class="page-header__tag"
            :class="{
              'page-header__tag--ok': detail.runtimeStatus === 2,
              'page-header__tag--danger': detail.runtimeStatus === 3,
            }"
          >{{ detail.runtimeStatusLabel }}</span>
        </div>
      </div>

      <el-card shadow="never" class="app-detail-tabs-card app-detail-panel">
        <el-tabs v-model="activeTab" class="app-detail-panel__tabs" @tab-change="handleTabChange" @tab-click="handleTabClick">
          <el-tab-pane
            v-for="tab in displayTabs"
            :key="tab.key"
            :label="tab.label"
            :name="tab.key"
          />
        </el-tabs>
        <div class="app-detail-panel__body">
          <keep-alive :max="KEEP_ALIVE_MAX">
            <component
              :is="activeTabComponent"
              v-if="activeTabComponent && detail"
              :key="activeTab"
              :app-id="detail.appId"
              :detail="detail"
              :label="activeTabMeta?.label || ''"
            />
          </keep-alive>
        </div>
      </el-card>
    </template>

    <el-empty v-else-if="!loading" description="集群不存在或加载失败">
      <el-button type="primary" @click="goBack">返回列表</el-button>
    </el-empty>
  </div>
</template>

<style lang="scss" scoped>
.app-detail-page {
  padding: 16px;
}

.page-header {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px 12px;
  margin-bottom: 10px;
  min-height: 32px;
}

.page-header__meta {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px 8px;
  min-width: 0;
  flex: 1;
}

.page-header__name {
  font-size: 15px;
  font-weight: 600;
  color: #1e293b;
  max-width: 240px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.page-header__tag {
  display: inline-flex;
  align-items: center;
  padding: 0 7px;
  height: 22px;
  border-radius: 4px;
  font-size: 12px;
  color: #475569;
  background: #f1f5f9;
  border: 1px solid #e2e8f0;
}

.page-header__tag--ok {
  color: #047857;
  background: #ecfdf5;
  border-color: #a7f3d0;
}

.page-header__tag--danger {
  color: #b91c1c;
  background: #fef2f2;
  border-color: #fecaca;
}

.app-detail-panel {
  :deep(.el-card__body) {
    padding: 0;
  }
}

.app-detail-panel__tabs {
  :deep(.el-tabs__header) {
    padding-left: 16px;
    padding-right: 16px;
    margin-bottom: 0;
  }

  :deep(.el-tabs__item) {
    height: 40px;
    line-height: 40px;
    font-size: 14px;
  }

  :deep(.el-tabs__nav-wrap::after) {
    height: 1px;
  }
}

.app-detail-panel__body {
  padding: 14px 20px 18px;
  min-height: 280px;
}
</style>
