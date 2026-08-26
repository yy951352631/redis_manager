<script lang="ts" setup>
defineProps<{ appId: number, label: string }>()
</script>

<template>
  <el-empty :description="`${label}：图表类 Tab 将通过 REST API + ECharts 逐步迁移`" />
</template>
