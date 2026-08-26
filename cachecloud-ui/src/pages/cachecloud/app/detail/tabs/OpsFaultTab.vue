<script lang="ts" setup>
import type { AppDetail } from "@/api/cachecloud"
import FaultDiagnosticPanel from "@/pages/cachecloud/app/components/FaultDiagnosticPanel.vue"
import "@/common/assets/styles/app-ops.scss"

const props = defineProps<{
  appId: number
  detail?: AppDetail | null
}>()

const route = useRoute()
</script>

<template>
  <FaultDiagnosticPanel
    :app-id="props.appId"
    :cluster-no="detail?.clusterNo"
    :app-name="detail?.appName"
    :app-type-desc="detail?.typeDesc"
    :auto-run="route.query.autoRun === '1'"
  />
</template>
