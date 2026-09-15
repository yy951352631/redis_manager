<script lang="ts" setup>
import { executeAppCommandApi } from "@/api/cachecloud"
import "@/common/assets/styles/app-tab.scss"

const props = defineProps<{ appId: number }>()

interface ConsoleLine {
  type: "prompt" | "value" | "error" | "welcome"
  text: string
}

const command = ref("")
const loading = ref(false)
const lines = ref<ConsoleLine[]>([
  { type: "welcome", text: "欢迎使用 Redis 只读命令控制台，可输入 info、cluster info 等只读命令。" }
])
const consoleRef = useTemplateRef<HTMLDivElement>("consoleRef")

async function runCommand() {
  const line = command.value.trim()
  if (!line) return
  lines.value.push({ type: "prompt", text: `集群编码:${props.appId}> ${line}` })
  command.value = ""
  loading.value = true
  try {
    const { data } = await executeAppCommandApi(props.appId, { command: line })
    const text = data?.result?.trim() ? data.result : "无返回结果"
    lines.value.push({
      type: text === "无返回结果" ? "error" : "value",
      text
    })
  } catch {
    lines.value.push({ type: "error", text: "命令执行失败，请检查集群连接或稍后重试。" })
  } finally {
    loading.value = false
    nextTick(() => {
      consoleRef.value?.scrollTo({ top: consoleRef.value.scrollHeight, behavior: "smooth" })
    })
  }
}

function onKeydown(e: KeyboardEvent) {
  if (e.key === "Enter") {
    e.preventDefault()
    runCommand()
  }
}
</script>

<template>
  <div class="app-command-page">
    <div ref="consoleRef" class="app-command-console">
      <div
        v-for="(line, i) in lines"
        :key="i"
        class="app-command-line"
        :class="`is-${line.type}`"
      >
        {{ line.text }}
      </div>
      <div class="app-command-input-row">
        <span class="app-command-prompt">集群编码:{{ appId }}&gt;</span>
        <input
          v-model="command"
          class="app-command-input"
          :disabled="loading"
          autofocus
          @keydown="onKeydown"
        >
      </div>
    </div>
  </div>
</template>

<style scoped>
.app-command-page {
  width: 100%;
}
.app-command-console {
  background: #1e1e1e;
  color: #d4d4d4;
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: 13px;
  line-height: 1.5;
  padding: 12px;
  min-height: 400px;
  max-height: 520px;
  overflow: auto;
  border-radius: 4px;
}
.app-command-line {
  white-space: pre-wrap;
  word-break: break-all;
  margin-bottom: 4px;
}
.app-command-line.is-prompt {
  color: #9cdcfe;
}
.app-command-line.is-value {
  color: #ce9178;
}
.app-command-line.is-error {
  color: #f48771;
}
.app-command-line.is-welcome {
  color: #6a9955;
  margin-bottom: 12px;
}
.app-command-input-row {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 8px;
}
.app-command-prompt {
  color: #9cdcfe;
  flex-shrink: 0;
}
.app-command-input {
  flex: 1;
  background: transparent;
  border: none;
  outline: none;
  color: #d4d4d4;
  font: inherit;
}
</style>
