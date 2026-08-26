<script lang="ts" setup>
import type { WikiContent } from "@/api/cachecloud"
import { getWikiClientAccessApi } from "@/api/cachecloud"

const loading = ref(false)
const content = ref<WikiContent | null>(null)

async function fetchData() {
  loading.value = true
  try {
    const { data } = await getWikiClientAccessApi()
    content.value = data
  } finally {
    loading.value = false
  }
}

onMounted(fetchData)
</script>

<template>
  <div v-loading="loading" class="client-demo-tab">
    <el-empty v-if="!loading && !content?.html" description="暂无接入文档" />
    <div v-else class="markdown-body" v-html="content?.html" />
  </div>
</template>

<style scoped>
.client-demo-tab {
  min-height: 200px;
}
.markdown-body {
  line-height: 1.6;
  font-size: 14px;
}
.markdown-body :deep(table) {
  border-collapse: collapse;
  width: 100%;
  margin: 12px 0;
}
.markdown-body :deep(th),
.markdown-body :deep(td) {
  border: 1px solid var(--el-border-color);
  padding: 6px 10px;
}
.markdown-body :deep(pre) {
  background: var(--el-fill-color-light);
  padding: 12px;
  overflow: auto;
  border-radius: 4px;
}
.markdown-body :deep(code) {
  font-family: ui-monospace, monospace;
  font-size: 13px;
}
</style>
