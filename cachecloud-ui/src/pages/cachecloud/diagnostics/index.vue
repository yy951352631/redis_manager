<script lang="ts" setup>
import type { Component } from "vue"
import type { TaskTabConfig } from "./tabs/DiagnosticTaskTab.vue"
import type { DiagnosticAppOption } from "@/api/cachecloud"
import { getDiagnosticAppsApi } from "@/api/cachecloud"
import DiagnosticTaskTab from "./tabs/DiagnosticTaskTab.vue"
import OnlineVerifyTab from "./tabs/OnlineVerifyTab.vue"
import RedisCliTab from "./tabs/RedisCliTab.vue"
import OfflineAnalysisTab from "./tabs/OfflineAnalysisTab.vue"
import BenchmarkTab from "./tabs/BenchmarkTab.vue"
import "@/common/assets/styles/app-tab.scss"
import "@/common/assets/styles/diagnostics.scss"

const route = useRoute()
const router = useRouter()

interface DiagnosticTabDef {
  key: string
  label: string
  component: Component
  taskConfig?: TaskTabConfig
}

const TASK_TAB_CONFIGS: Record<string, TaskTabConfig> = {
  scan: { submitTitle: "提交scan查询", listTitle: "scan查询任务列表", submitLabel: "提交检测", fields: "scan" },
  deleteKey: { submitTitle: "预览将要删除的key", listTitle: "key清理任务列表", submitLabel: "提交预览", fields: "deleteKey" },
  slotAnalysis: { submitTitle: "提交slot分析", listTitle: "slot分析任务列表", submitLabel: "slot分析", fields: "slotAnalysis" }
}

const tabs: DiagnosticTabDef[] = [
  { key: "onlineVerify", label: "在线验证", component: OnlineVerifyTab },
  { key: "redis-cli", label: "redis-cli工具", component: RedisCliTab },
  { key: "scan", label: "scan查询", component: DiagnosticTaskTab, taskConfig: TASK_TAB_CONFIGS.scan },
  { key: "deleteKey", label: "key清理", component: DiagnosticTaskTab, taskConfig: TASK_TAB_CONFIGS.deleteKey },
  { key: "slotAnalysis", label: "集群slot分析", component: DiagnosticTaskTab, taskConfig: TASK_TAB_CONFIGS.slotAnalysis },
  { key: "offlineAnalysis", label: "离线数据分析", component: OfflineAnalysisTab },
  { key: "benchmark", label: "压测工具", component: BenchmarkTab }
]

const activeTab = ref("onlineVerify")
const apps = ref<DiagnosticAppOption[]>([])

function syncTab(tab: string) {
  const query: Record<string, string> = {}
  Object.entries(route.query).forEach(([k, v]) => {
    if (v != null && k !== "tab" && k !== "tabTag") query[k] = String(v)
  })
  query.tabTag = tab
  router.replace({ path: route.path, query })
}

function handleTabChange(tab: string | number | boolean) {
  activeTab.value = String(tab)
  syncTab(activeTab.value)
}

function resolveTabProps(tab: DiagnosticTabDef) {
  // 这两个 tab 自成一体，不需要集群列表
  if (tab.key === "onlineVerify" || tab.key === "offlineAnalysis") return {}
  if (tab.taskConfig) {
    return { tabTag: tab.key, apps: apps.value, config: tab.taskConfig, active: activeTab.value === tab.key }
  }
  return { apps: apps.value }
}

onMounted(async () => {
  const tab = (route.query.tabTag as string) || (route.query.tab as string) || "onlineVerify"
  if (tabs.some(t => t.key === tab)) activeTab.value = tab
  const { data } = await getDiagnosticAppsApi()
  apps.value = data ?? []
})
</script>

<template>
  <div class="diagnostics-page">
    <el-card shadow="never" class="app-detail-tabs-card manage-diagnostic-tool">
      <el-tabs v-model="activeTab" @tab-change="handleTabChange">
        <el-tab-pane v-for="tab in tabs" :key="tab.key" :label="tab.label" :name="tab.key" lazy>
          <component :is="tab.component" v-bind="resolveTabProps(tab)" />
        </el-tab-pane>
      </el-tabs>
    </el-card>
  </div>
</template>

<style lang="scss" scoped>
.diagnostics-page {
  padding: 16px;
}
</style>
