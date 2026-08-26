<script lang="ts" setup>
import { executeInstanceCommandApi } from "@/api/cachecloud"
import "@/common/assets/styles/diagnostics.scss"

const props = defineProps<{ instanceId: number }>()

interface ConsoleLine {
  type: "prompt" | "value" | "error" | "welcome"
  text: string
}

const CLI_WELCOME = `Redis 只读控制台使用说明:
1. 在下方输入框输入命令，按 Enter 执行
2. 不要带 redis-cli 前缀，示例: info、info all、dbsize、cluster info
3. 仅支持只读命令，写操作将被拒绝`

const command = ref("")
const running = ref(false)
const lines = ref<ConsoleLine[]>([
  { type: "welcome", text: CLI_WELCOME }
])
const outputRef = useTemplateRef<HTMLDivElement>("outputRef")
const inputRef = useTemplateRef<HTMLInputElement>("inputRef")

function focusInput() {
  nextTick(() => inputRef.value?.focus())
}

function scrollToBottom() {
  nextTick(() => {
    const el = outputRef.value
    if (el) el.scrollTop = el.scrollHeight
  })
}

async function runCommand() {
  const line = command.value.trim()
  if (!line) return

  lines.value.push({ type: "prompt", text: `${props.instanceId} > ${line}` })
  command.value = ""
  running.value = true
  try {
    const { data } = await executeInstanceCommandApi(props.instanceId, line)
    const text = data?.result?.trim() ? data.result : "(无输出)"
    lines.value.push({ type: "value", text })
  } catch (e: unknown) {
    lines.value.push({
      type: "error",
      text: e instanceof Error ? e.message : "执行失败"
    })
  } finally {
    running.value = false
    scrollToBottom()
    focusInput()
  }
}

function onKeydown(e: KeyboardEvent) {
  if (e.key === "Enter") {
    e.preventDefault()
    runCommand()
  }
}

onMounted(focusInput)
onActivated(focusInput)
</script>

<template>
  <div class="app-tab-embed instance-command-cli">
    <div class="diag-cli-console-wrap">
      <div
        ref="outputRef"
        class="diag-cli-console-output"
        @click="focusInput"
      >
        <div
          v-for="(line, i) in lines"
          :key="i"
          class="diag-cli-line"
          :class="`is-${line.type}`"
        >
          {{ line.text }}
        </div>
        <div v-if="running" class="diag-cli-line is-welcome">执行中...</div>
      </div>
      <div class="diag-cli-input-row" @click="focusInput">
        <span class="diag-cli-prompt">{{ instanceId }} &gt;</span>
        <input
          ref="inputRef"
          v-model="command"
          class="diag-cli-input"
          :disabled="running"
          placeholder="输入命令后按 Enter"
          @keydown="onKeydown"
        >
      </div>
    </div>
  </div>
</template>

<style lang="scss" scoped>
.instance-command-cli {
  width: 100%;
}

.diag-cli-console-wrap {
  min-height: 520px;
  max-height: 720px;
}
</style>
