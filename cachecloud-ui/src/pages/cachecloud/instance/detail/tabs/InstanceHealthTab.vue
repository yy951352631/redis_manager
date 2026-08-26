<script lang="ts" setup>
import HealthCheckPanel from "@/pages/cachecloud/diagnostics/components/HealthCheckPanel.vue"

const props = defineProps<{
  instanceId: number
  hostPort?: string
}>()

const initialServers = computed(() => (props.hostPort || "").trim())
</script>

<template>
  <div class="app-tab-embed">
    <HealthCheckPanel
      title="健康检查"
      :initial-servers="initialServers"
      :show-input="false"
      :auto-run="!!initialServers"
    />
  </div>
</template>
