<script lang="ts" setup>
import { useAiAssistant } from "@@/composables/useAiAssistant"
import { ChatDotRound } from "@element-plus/icons-vue"

const { openDrawer } = useAiAssistant()
</script>

<template>
  <button type="button" class="rp-ai-assistant-toggle" title="Redis 运维助手" @click="openDrawer">
    <el-icon><ChatDotRound /></el-icon>
    <span>AI 助手</span>
  </button>
</template>

<style scoped>
.rp-ai-assistant-toggle {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 0 10px;
  height: 100%;
  border: none;
  background: transparent;
  color: inherit;
  cursor: pointer;
  font-size: var(--rp-font-size-base);
}
.rp-ai-assistant-toggle:hover {
  color: var(--el-color-primary);
}
</style>
