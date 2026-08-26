import { manageRequest, toFormBody } from "@/http/manage"

export interface AiStatus {
  enabled: boolean
}

export interface AiChatReply {
  reply: string
}

export interface AiDiagnoseReportResult {
  summary: string
  reportId: string
}

export function getAiStatusApi() {
  return manageRequest<AiStatus>({ url: "/manage/ai/status", method: "get" })
}

export function aiChatApi(data: {
  message: string
  history?: string
  pageContext?: string
  appId?: number
  instanceId?: number
  diagnosticReportId?: string
  diagnosticReportJson?: string
}) {
  return manageRequest<AiChatReply>({
    url: "/manage/ai/chat.json",
    method: "post",
    headers: { "Content-Type": "application/x-www-form-urlencoded; charset=UTF-8" },
    data: toFormBody(data)
  })
}

export function aiDiagnoseReportApi(reportId: string, reportJson: string) {
  return manageRequest<AiDiagnoseReportResult>({
    url: "/manage/ai/diagnose-report.json",
    method: "post",
    headers: { "Content-Type": "application/x-www-form-urlencoded; charset=UTF-8" },
    data: toFormBody({ reportId, reportJson })
  })
}

export function getFaultDiagnosticReportApi(reportId: string) {
  return manageRequest<import("./type").FaultDiagnosticReport>({
    url: "/manage/diagnostic/fault/report.json",
    method: "get",
    params: { reportId }
  })
}
