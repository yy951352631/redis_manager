<script lang="ts" setup>
import type { AiChatMessage } from "@@/composables/useAiAssistant"
import { useAiAssistant } from "@@/composables/useAiAssistant"
import { renderMarkdown } from "@@/utils/markdown"
import { aiChatApi, getAiStatusApi } from "@/api/cachecloud/ai"
import { ChatDotRound, Close } from "@element-plus/icons-vue"
import "@/common/assets/styles/ai-assistant.scss"

const route = useRoute()
const { state, closeDrawer } = useAiAssistant()

const messages = ref<AiChatMessage[]>([])
const input = ref("")
const sending = ref(false)
const loaded = ref(false)

const welcomeHtml = `
  <div class="rp-ai-welcome">
    <strong>你好，我是 Redis 运维助手</strong>
    可问我：内存飙高、命中率低、慢查询、哨兵/集群拓扑、纳管排查等。在集群/节点页面会自动带上平台数据。
  </div>
`

watch(() => state.visible, async (open) => {
  if (!open) return
  if (!loaded.value) {
    try {
      const status = await getAiStatusApi()
      state.enabled = status.enabled
    } catch {
      state.enabled = false
    }
    loaded.value = true
  }
  if (state.resetChat) {
    messages.value = []
    if (state.diagnosticReportId) {
      messages.value.push({
        role: "assistant",
        content: `已载入故障诊断报告（${state.diagnosticReportId.substring(0, 8)}…）。可直接提问，或点击发送。`
      })
    }
    state.resetChat = false
  }
  if (state.prefill) {
    input.value = state.prefill
    if (state.autoSend && state.prefill) {
      await nextTick()
      await sendMessage()
    }
    state.prefill = ""
    state.autoSend = false
  }
})

function buildPageContext() {
  const parts = [route.path]
  if (route.fullPath !== route.path) parts.push(route.fullPath)
  const pageTitle = document.querySelector("[data-page-title]")?.getAttribute("data-page-title")
    || document.querySelector(".page-title")?.textContent?.trim()
  if (pageTitle) parts.push(pageTitle)
  return state.pageContext || parts.join(" | ")
}

async function sendMessage() {
  if (sending.value || !state.enabled) return
  const text = input.value.trim()
  if (!text) return

  messages.value.push({ role: "user", content: text })
  input.value = ""
  sending.value = true
  const loadingIdx = messages.value.length
  messages.value.push({ role: "assistant", content: "思考中..." })

  try {
    const routeAppId = Number(route.params.appId)
    const appId = state.appId ?? (Number.isNaN(routeAppId) ? undefined : routeAppId)
    const routeInstanceId = Number(route.params.instanceId)
    const instanceId = state.instanceId ?? (Number.isNaN(routeInstanceId) ? undefined : routeInstanceId)
    const { reply } = await aiChatApi({
      message: text,
      history: JSON.stringify(messages.value.slice(0, -1).filter(m => m.content !== "思考中...")),
      pageContext: buildPageContext(),
      appId: appId && appId > 0 ? appId : undefined,
      instanceId: instanceId && instanceId > 0 ? instanceId : undefined,
      diagnosticReportId: state.diagnosticReportId || undefined,
      diagnosticReportJson: state.diagnosticReportJson || undefined
    })
    messages.value[loadingIdx] = { role: "assistant", content: reply }
  } catch (e: unknown) {
    messages.value[loadingIdx] = {
      role: "assistant",
      content: e instanceof Error ? e.message : "请求失败，请稍后重试"
    }
  } finally {
    sending.value = false
  }
}

function onKeydown(e: KeyboardEvent) {
  if (e.key === "Enter" && !e.shiftKey) {
    e.preventDefault()
    sendMessage()
  }
}
</script>

<template>
  <Teleport to="body">
    <div v-show="state.visible" class="rp-ai-drawer-mask is-open" @click="closeDrawer" />
    <aside class="rp-ai-drawer" :class="{ 'is-open': state.visible }" aria-label="AI Assistant">
      <div class="rp-ai-drawer__header">
        <div class="rp-ai-drawer__title">Redis 运维助手</div>
        <button type="button" class="rp-ai-drawer__close" aria-label="关闭" @click="closeDrawer">
          <el-icon><Close /></el-icon>
        </button>
      </div>
      <div v-if="!state.enabled" class="rp-ai-disabled">
        AI 助手未启用，请在后端配置 cachecloud.ai
      </div>
      <div ref="msgListRef" class="rp-ai-drawer__messages">
        <div v-if="!messages.length" v-html="welcomeHtml" />
        <div
          v-for="(msg, idx) in messages"
          :key="idx"
          class="rp-ai-msg"
          :class="[`rp-ai-msg--${msg.role}`, { 'rp-ai-msg--loading': msg.content === '思考中...' }]"
        >
          <div class="rp-ai-msg__bubble">
            <div v-if="msg.role === 'assistant' && msg.content !== '思考中...'" v-html="renderMarkdown(msg.content)" />
            <template v-else>{{ msg.content }}</template>
          </div>
        </div>
      </div>
      <div class="rp-ai-drawer__input">
        <textarea
          v-model="input"
          placeholder="输入问题，Enter 发送，Shift+Enter 换行"
          :disabled="!state.enabled || sending"
          @keydown="onKeydown"
        />
        <div class="rp-ai-drawer__actions">
          <span class="rp-ai-drawer__hint">请勿粘贴密码等敏感信息</span>
          <el-button
            type="primary"
            size="small"
            :icon="ChatDotRound"
            :loading="sending"
            :disabled="!state.enabled"
            @click="sendMessage"
          >
            发送
          </el-button>
        </div>
      </div>
    </aside>
  </Teleport>
</template>
