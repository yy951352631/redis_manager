<script lang="ts" setup>
import InstanceOpsContent from "@/pages/cachecloud/instance/ops/InstanceOpsContent.vue"

defineProps<{
  appId: number
}>()
</script>

<template>
  <InstanceOpsContent panel="restart" :default-app-id="appId" />
</template>
