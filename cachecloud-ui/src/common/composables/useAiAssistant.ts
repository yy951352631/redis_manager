import { reactive } from "vue"

export interface AiChatMessage {
  role: "user" | "assistant"
  content: string
}

export interface AiAssistantOpenContext {
  appId?: number
  instanceId?: number
  diagnosticReportId?: string
  diagnosticReportJson?: string
  prefill?: string
  autoSend?: boolean
  pageContext?: string
}

const state = reactive({
  visible: false,
  enabled: true,
  appId: undefined as number | undefined,
  instanceId: undefined as number | undefined,
  diagnosticReportId: "",
  diagnosticReportJson: "",
  prefill: "",
  autoSend: false,
  pageContext: "",
  resetChat: false
})

export function useAiAssistant() {
  function openDrawer() {
    state.visible = true
  }

  function closeDrawer() {
    state.visible = false
  }

  function openWithContext(ctx: AiAssistantOpenContext) {
    state.appId = ctx.appId
    state.instanceId = ctx.instanceId
    state.diagnosticReportId = ctx.diagnosticReportId || ""
    state.diagnosticReportJson = ctx.diagnosticReportJson || ""
    state.prefill = ctx.prefill || ""
    state.autoSend = !!ctx.autoSend
    state.pageContext = ctx.pageContext || ""
    state.resetChat = !!ctx.diagnosticReportId
    state.visible = true
  }

  function setEnabled(enabled: boolean) {
    state.enabled = enabled
  }

  return { state, openDrawer, closeDrawer, openWithContext, setEnabled }
}
