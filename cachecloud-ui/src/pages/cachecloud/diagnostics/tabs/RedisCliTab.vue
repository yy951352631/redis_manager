<script lang="ts" setup>
import type { DiagnosticAppOption, DiagnosticInstance } from "@/api/cachecloud"
import { executeDiagnosticCommandApi, getDiagnosticInstancesApi } from "@/api/cachecloud"
import { formatClusterNo } from "@/common/utils/cluster-no"
import "@/common/assets/styles/diagnostics.scss"

defineProps<{ apps: DiagnosticAppOption[] }>()

interface ConsoleLine {
  type: "prompt" | "value" | "error" | "welcome"
  text: string
}

const selectedAppId = ref<number | undefined>()
const instances = ref<DiagnosticInstance[]>([])
const selectedNode = ref("")
const timeout = ref("")
const command = ref("")
const loading = ref(false)
const CLI_HELP = `CacheCloud redis-cli 使用说明:
1. 在下方 redis-cli > 后输入命令，按 Enter 执行
2. 不要带 redis-cli 前缀，示例: info、info all、dbsize、cluster info
3. 输入 help 或 --help 查看完整命令列表`

const lines = ref<ConsoleLine[]>([
  { type: "welcome", text: CLI_HELP }
])
const outputRef = useTemplateRef<HTMLDivElement>("outputRef")
const inputRef = useTemplateRef<HTMLInputElement>("inputRef")

async function onAppChange(appId: number) {
  selectedAppId.value = appId
  selectedNode.value = ""
  const { data } = await getDiagnosticInstancesApi(appId)
  instances.value = data ?? []
  if (instances.value.length) {
    selectedNode.value = instances.value[0].hostPort
  }
  focusInput()
}

function focusInput() {
  nextTick(() => inputRef.value?.focus())
}

async function runCommand() {
  const line = command.value.trim()
  if (!line) return

  const isHelp = line.toLowerCase() === "help" || line.toLowerCase() === "--help"
  if (isHelp) {
    lines.value.push({ type: "prompt", text: `redis-cli > ${line}` })
    command.value = ""
    lines.value.push({
      type: "value",
      text: [
        "CacheCloud redis-cli 使用说明:",
        "1. 直接输入 Redis 命令，不要带 redis-cli 前缀",
        "2. 示例: info | info all | dbsize | cluster info | cluster nodes | get mykey",
        "",
        "常用只读命令:",
        "select, debug, exists, object, ttl, type, scan, get, getbit, getrange, mget,",
        "setrange, strlen, hexists, hget, hgetall, hkeys, hlen, hmget, hvals, hscan,",
        "lindex, llen, lrange, scard, sismember, sscan, srandmember, zcard, zcount,",
        "zrange, zrangebyscore, zrank, zrevrange, zscore, zscan, dbsize, info, time,",
        "lastsave, memory",
        "",
        "本工具为管理员工具，除只读命令外还可执行写操作，请谨慎使用。"
      ].join("\n")
    })
    scrollToBottom()
    focusInput()
    return
  }

  if (!selectedAppId.value) {
    ElMessage.warning("请选择集群")
    focusInput()
    return
  }
  if (!selectedNode.value) {
    ElMessage.warning("请选择节点")
    focusInput()
    return
  }
  lines.value.push({ type: "prompt", text: `redis-cli > ${line}` })
  command.value = ""
  loading.value = true
  try {
    const timeoutMs = timeout.value ? Number(timeout.value) : undefined
    const { data } = await executeDiagnosticCommandApi({
      appId: selectedAppId.value,
      node: selectedNode.value,
      command: line,
      timeout: timeoutMs
    })
    let text = data?.result ?? ""
    if (text === "redis_cloud_inner_error") {
      text = "命令执行失败：请确认 CacheCloud 可网络直连 Redis，或本机已安装 redis-cli"
    }
    if (!text) text = "(无输出)"
    lines.value.push({ type: "value", text })
  } catch {
    lines.value.push({ type: "error", text: "请求失败，请检查登录状态或查看服务端日志" })
  } finally {
    loading.value = false
    scrollToBottom()
    focusInput()
  }
}

function scrollToBottom() {
  nextTick(() => {
    const el = outputRef.value
    if (el) el.scrollTop = el.scrollHeight
  })
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
  <div class="manage-diagnostic-tab-page">
    <div class="diag-cli-toolbar">
      <div class="diag-cli-field">
        <span class="diag-cli-label">集群</span>
        <el-select
          :model-value="selectedAppId"
          placeholder="选择集群"
          filterable
          style="width: 260px"
          @change="onAppChange"
        >
          <el-option
            v-for="app in apps"
            :key="app.appId"
            :label="`【${formatClusterNo(app.clusterNo, app.appId)}】${app.appName}`"
            :value="app.appId"
          />
        </el-select>
      </div>
      <div class="diag-cli-field">
        <span class="diag-cli-label">节点</span>
        <el-select v-model="selectedNode" placeholder="选择节点" filterable style="width: 220px">
          <el-option v-for="n in instances" :key="n.hostPort" :label="n.hostPort" :value="n.hostPort" />
        </el-select>
      </div>
      <div class="diag-cli-field">
        <span class="diag-cli-label">超时(ms)</span>
        <el-input v-model="timeout" placeholder="默认 30000" style="width: 140px" />
      </div>
    </div>

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
        <div v-if="loading" class="diag-cli-line is-welcome">
          执行中...
        </div>
      </div>
      <div class="diag-cli-input-row" @click="focusInput">
        <span class="diag-cli-prompt">redis-cli &gt;</span>
        <input
          ref="inputRef"
          v-model="command"
          class="diag-cli-input"
          :disabled="loading"
          placeholder="输入命令后按 Enter"
          @keydown="onKeydown"
        >
      </div>
    </div>
  </div>
</template>
