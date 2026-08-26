<script lang="ts" setup>
import AppInstanceListPanel from "@/pages/cachecloud/app/components/AppInstanceListPanel.vue"
import "@/common/assets/styles/app-tab.scss"

withDefaults(defineProps<{
  appId: number
  /** 节点详情内嵌拓扑仅展示，不拉运维接口 */
  opsMode?: boolean
  /** 节点详情内嵌时传入，用于高亮当前节点 */
  currentInstanceId?: number
}>(), {
  opsMode: true,
  currentInstanceId: 0
})
</script>

<template>
  <AppInstanceListPanel
    :app-id="appId"
    :ops-mode="opsMode"
    :current-instance-id="currentInstanceId"
  />
</template>
