<script lang="ts" setup>
import {
  Connection,
  EditPen,
  CircleCheck,
  CircleClose,
  Warning,
  WarningFilled,
  FolderOpened
} from "@element-plus/icons-vue"
import { runOnlineVerifyApi } from "@/api/cachecloud"
import type { OnlineHealthCheckItem, OnlineHealthCheckResult, OnlineHealthTarget } from "@/api/cachecloud/type"
import "@/common/assets/styles/diagnostics.scss"
import "@/common/assets/styles/fault-diagnostic.scss"

const props = withDefaults(defineProps<{
  /** 初始服务器列表，如 ip:port */
  initialServers?: string
  /** 进入后是否自动执行一次检查 */
  autoRun?: boolean
  /** 是否显示输入区（节点详情可隐藏或只读展示） */
  showInput?: boolean
  /** 面板标题 */
  title?: string
}>(), {
  initialServers: "",
  autoRun: false,
  showInput: true,
  title: "在线验证"
})

const servers = ref(props.initialServers || "")
const running = ref(false)
const healthResult = ref<OnlineHealthCheckResult | null>(null)
const rawOpen = reactive<Record<string, boolean>>({})
const resultText = computed(() => healthResult.value?.result ?? "")
const hasAutoRun = ref(false)

interface DisplayCheck {
  key: string
  name: string
  level: "PASS" | "FAIL"
  summary: string
  detail: string
  raw: string
  current: string
  expect: string
  description: string
}

interface DisplayGroup {
  name: string
  items: DisplayCheck[]
}

function escapeHtml(str: string) {
  return str
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
}

function modeTagType(mode?: string) {
  if (mode === "cluster") return "danger"
  if (mode === "sentinel") return "warning"
  if (mode === "standalone") return "success"
  return "info"
}

function errorCount(target: OnlineHealthTarget) {
  return (target.checks || []).filter(c => c.level === "Error").length
}

function infoCount(target: OnlineHealthTarget) {
  return (target.checks || []).filter(c => c.level === "Info").length
}

function resolveGroup(name: string): string {
  if (name.includes("哨兵")) return "哨兵"
  if (name.includes("内存")) return "内存"
  if (name.includes("RDB") || name.includes("AOF") || name.includes("aof")) return "持久化"
  if (name.includes("集群") || name.includes("槽位") || name.includes("失败节点")) return "集群"
  if (name.includes("从节点") || name.includes("主节点")) return "复制"
  if (
    name.includes("连接")
    || name.includes("拒绝")
    || name.includes("淘汰")
    || name.includes("fork")
  ) {
    return "连接与性能"
  }
  return "其他"
}

function toDisplayCheck(item: OnlineHealthCheckItem, targetHost: string, index: number): DisplayCheck {
  const pass = item.level !== "Error"
  return {
    key: `${targetHost}-${item.name}-${index}`,
    name: item.name,
    level: pass ? "PASS" : "FAIL",
    summary: pass
      ? `${item.name}正常（当前 ${item.current || "-"}）`
      : `${item.name}异常（当前 ${item.current || "-"}，预期 ${item.expect || "-"}）`,
    detail: [
      item.current ? `当前值: ${item.current}` : "",
      item.expect ? `预期值: ${item.expect}` : "",
      item.description ? `说明: ${item.description}` : ""
    ].filter(Boolean).join("\n"),
    raw: [
      `检查项: ${item.name}`,
      `状态: ${pass ? "PASS" : "FAIL"}`,
      `当前值: ${item.current || "-"}`,
      `预期值: ${item.expect || "-"}`,
      `说明: ${item.description || "-"}`
    ].join("\n"),
    current: item.current || "-",
    expect: item.expect || "-",
    description: item.description || "-"
  }
}

function groupedChecks(target: OnlineHealthTarget): DisplayGroup[] {
  const map = new Map<string, DisplayCheck[]>()
  const order: string[] = []
  ;(target.checks || []).forEach((item, index) => {
    const group = resolveGroup(item.name)
    if (!map.has(group)) {
      map.set(group, [])
      order.push(group)
    }
    map.get(group)!.push(toDisplayCheck(item, target.host, index))
  })
  // Error 组内靠前
  for (const items of map.values()) {
    items.sort((a, b) => Number(b.level === "FAIL") - Number(a.level === "FAIL"))
  }
  return order.map(name => ({ name, items: map.get(name)! }))
}

function levelClass(level: string) {
  return `app-fault-check--${level.toLowerCase()}`
}

function getLevelIcon(level: string) {
  return level === "FAIL" ? Warning : CircleCheck
}

function toggleRaw(key: string) {
  rawOpen[key] = !rawOpen[key]
}

async function runHealthCheck() {
  const text = servers.value.trim()
  if (!text) {
    ElMessage.warning("请输入服务器信息")
    return
  }
  running.value = true
  healthResult.value = null
  Object.keys(rawOpen).forEach(k => delete rawOpen[k])
  try {
    const { data } = await runOnlineVerifyApi({ servers: text, verifyType: "health" })
    healthResult.value = data ?? null
  } catch (e: unknown) {
    healthResult.value = {
      result: e instanceof Error ? e.message : "请求失败，请稍后重试",
      targets: []
    }
  } finally {
    running.value = false
  }
}

function clearResult() {
  healthResult.value = null
  Object.keys(rawOpen).forEach(k => delete rawOpen[k])
}

function formatNow() {
  const d = new Date()
  const pad = (n: number) => String(n).padStart(2, "0")
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
}

function buildPdfTargetHtml(target: OnlineHealthTarget): string {
  const fail = errorCount(target)
  const pass = infoCount(target)
  const statusLabel = target.connectError
    ? "连接失败"
    : target.allOk
      ? "全部通过"
      : `${fail} 项异常 / ${pass} 项正常`
  const statusClass = target.connectError || !target.allOk ? "fail" : "pass"

  let body = ""
  if (target.connectError) {
    body = `<div class="error-box">${escapeHtml(target.connectError)}</div>`
  } else {
    body = groupedChecks(target).map(group => {
      const rows = group.items.map(item => `<tr class="${item.level === "FAIL" ? "row-fail" : "row-pass"}">
  <td><span class="badge ${item.level === "FAIL" ? "badge-fail" : "badge-pass"}">${item.level}</span></td>
  <td class="name">${escapeHtml(item.name)}</td>
  <td>${escapeHtml(item.current)}</td>
  <td>${escapeHtml(item.expect)}</td>
  <td>${escapeHtml(item.description)}</td>
</tr>`).join("")
      return `<div class="group">
  <div class="group-title">${escapeHtml(group.name)}<span>${group.items.length} 项</span></div>
  <table>
    <thead>
      <tr><th width="70">状态</th><th>检查项</th><th>当前值</th><th>预期值</th><th>说明</th></tr>
    </thead>
    <tbody>${rows}</tbody>
  </table>
</div>`
    }).join("")
  }

  const metaBits = [
    target.modeLabel || target.mode || "未知",
    target.managed ? `纳管${target.appName ? ` · ${target.appName}` : ""}` : ""
  ].filter(Boolean).map(t => `<span class="chip">${escapeHtml(t)}</span>`).join("")

  return `<section class="target">
  <div class="target-head">
    <div>
      <div class="host">${escapeHtml(target.host)}</div>
      <div class="chips">${metaBits}</div>
    </div>
    <div class="status status-${statusClass}">${escapeHtml(statusLabel)}</div>
  </div>
  ${body}
</section>`
}

async function downloadPdf() {
  const targets = healthResult.value?.targets || []
  if (!targets.length && !resultText.value.trim()) {
    ElMessage.warning("暂无验证结果")
    return
  }

  const stamp = formatNow()
  const fileStamp = stamp.replace(/[:\s]/g, "-")
  const totalFail = targets.reduce((n, t) => n + errorCount(t), 0)
  const totalPass = targets.reduce((n, t) => n + infoCount(t), 0)
  const connectFail = targets.filter(t => t.connectError).length
  const overview = targets.length
    ? `节点 ${targets.length} 个 · PASS ${totalPass} · FAIL ${totalFail}${connectFail ? ` · 连接失败 ${connectFail}` : ""}`
    : "无结构化结果"

  const content = targets.length
    ? targets.map(buildPdfTargetHtml).join("")
    : `<pre class="fallback">${escapeHtml(resultText.value)}</pre>`

  const reportHtml = `
<style>
.health-pdf-report{box-sizing:border-box;width:794px;padding:24px 28px 32px;color:#0f172a;background:#fff;font-family:"PingFang SC","Microsoft YaHei","Helvetica Neue",Arial,sans-serif;font-size:12px;line-height:1.55}
.health-pdf-report *{box-sizing:border-box}
.health-pdf-report .report-title{margin:0 0 6px;font-size:22px;font-weight:700}
.health-pdf-report .report-meta{margin:0 0 18px;color:#64748b;font-size:12px}
.health-pdf-report .overview{display:flex;gap:10px;flex-wrap:wrap;margin-bottom:18px;padding:12px 14px;border:1px solid #dbeafe;border-radius:8px;background:#eff6ff}
.health-pdf-report .overview-item{min-width:120px;padding:8px 10px;border-radius:6px;background:#fff;border:1px solid #e2e8f0}
.health-pdf-report .overview-item b{display:block;font-size:16px;margin-top:2px}
.health-pdf-report .overview-item span{color:#64748b;font-size:11px}
.health-pdf-report .overview-item.pass b{color:#15803d}
.health-pdf-report .overview-item.fail b{color:#b91c1c}
.health-pdf-report .target{margin-bottom:18px;border:1px solid #e2e8f0;border-radius:8px;overflow:hidden;background:#fff}
.health-pdf-report .target-head{display:flex;justify-content:space-between;align-items:center;gap:12px;padding:12px 14px;background:#f8fafc;border-bottom:1px solid #e2e8f0}
.health-pdf-report .host{font-size:15px;font-weight:700;font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace}
.health-pdf-report .chips{margin-top:6px;display:flex;gap:6px;flex-wrap:wrap}
.health-pdf-report .chip{display:inline-block;padding:2px 8px;border-radius:999px;border:1px solid #cbd5e1;background:#fff;color:#475569;font-size:11px}
.health-pdf-report .status{flex-shrink:0;padding:6px 12px;border-radius:999px;font-weight:700;white-space:nowrap}
.health-pdf-report .status-pass{background:#dcfce7;color:#166534}
.health-pdf-report .status-fail{background:#fee2e2;color:#991b1b}
.health-pdf-report .group{padding:12px 14px 14px}
.health-pdf-report .group+.group{border-top:1px dashed #e2e8f0}
.health-pdf-report .group-title{display:flex;justify-content:space-between;align-items:center;margin-bottom:8px;font-size:13px;font-weight:700;color:#334155}
.health-pdf-report .group-title span{color:#94a3b8;font-weight:600;font-size:11px}
.health-pdf-report table{width:100%;border-collapse:collapse;table-layout:fixed}
.health-pdf-report th,.health-pdf-report td{border:1px solid #e2e8f0;padding:8px 10px;vertical-align:top;word-break:break-word}
.health-pdf-report th{background:#f1f5f9;color:#475569;font-size:11px;text-align:left}
.health-pdf-report td.name{font-weight:700}
.health-pdf-report .badge{display:inline-block;min-width:42px;text-align:center;padding:2px 6px;border-radius:3px;font-size:10px;font-weight:700}
.health-pdf-report .badge-pass{background:#dcfce7;color:#166534}
.health-pdf-report .badge-fail{background:#fee2e2;color:#991b1b}
.health-pdf-report .row-fail{background:#fff7f7}
.health-pdf-report .error-box{margin:14px;padding:12px 14px;border-radius:6px;background:#fef2f2;border:1px solid #fecaca;color:#b91c1c;font-weight:600}
.health-pdf-report .fallback{white-space:pre-wrap;word-break:break-word;padding:14px;background:#f8fafc;border:1px solid #e2e8f0;border-radius:8px}
.health-pdf-report .sign{margin-top:28px;text-align:right;color:#64748b;font-size:12px}
.health-pdf-report .sign strong{color:#334155;font-size:13px}
</style>
<div class="health-pdf-report">
  <h1 class="report-title">Redis 健康检查报告</h1>
  <p class="report-meta">生成时间：${escapeHtml(stamp)}　｜　输入：${escapeHtml(servers.value.trim() || "-")}</p>
  <div class="overview">
    <div class="overview-item"><span>汇总</span><b>${escapeHtml(overview)}</b></div>
    <div class="overview-item pass"><span>PASS</span><b>${totalPass}</b></div>
    <div class="overview-item fail"><span>FAIL</span><b>${totalFail}</b></div>
  </div>
  ${content}
  <div class="sign"><strong>redis管理平台</strong><br/>健康检查报告</div>
</div>`

  const loading = ElMessage({ message: "正在生成 PDF…", type: "info", duration: 0 })
  // 挂到当前文档视口内截图：opacity:0 / 移出视口常导致 html2canvas 空白页
  const host = document.createElement("div")
  host.setAttribute("data-health-pdf-host", "1")
  Object.assign(host.style, {
    position: "fixed",
    left: "0",
    top: "0",
    width: "794px",
    margin: "0",
    padding: "0",
    background: "#ffffff",
    opacity: "0.011",
    pointerEvents: "none",
    zIndex: "2147483646",
    overflow: "visible"
  })
  host.innerHTML = reportHtml
  document.body.appendChild(host)

  try {
    await nextTick()
    await new Promise<void>(r => requestAnimationFrame(() => requestAnimationFrame(() => r())))

    const reportEl = host.querySelector(".health-pdf-report") as HTMLElement | null
    if (!reportEl || reportEl.scrollHeight < 20) {
      throw new Error("报告内容为空，无法生成 PDF")
    }

    const [{ default: html2canvas }, { jsPDF }] = await Promise.all([
      import("html2canvas"),
      import("jspdf")
    ])

    const canvas = await html2canvas(reportEl, {
      scale: 2,
      useCORS: true,
      backgroundColor: "#ffffff",
      logging: false,
      width: reportEl.scrollWidth,
      height: reportEl.scrollHeight,
      windowWidth: reportEl.scrollWidth,
      windowHeight: reportEl.scrollHeight,
      scrollX: 0,
      scrollY: 0
    })

    if (!canvas.width || !canvas.height) {
      throw new Error("截图失败，请稍后重试")
    }

    const pdf = new jsPDF({ orientation: "portrait", unit: "mm", format: "a4" })
    const pageWidth = pdf.internal.pageSize.getWidth()
    const pageHeight = pdf.internal.pageSize.getHeight()
    const margin = 8
    const usableWidth = pageWidth - margin * 2
    const usableHeight = pageHeight - margin * 2
    const imgWidth = usableWidth
    const imgHeight = (canvas.height * imgWidth) / canvas.width
    const imgData = canvas.toDataURL("image/jpeg", 0.95)

    let heightLeft = imgHeight
    let position = margin
    pdf.addImage(imgData, "JPEG", margin, position, imgWidth, imgHeight)
    heightLeft -= usableHeight
    while (heightLeft > 1) {
      position = margin - (imgHeight - heightLeft)
      pdf.addPage()
      pdf.addImage(imgData, "JPEG", margin, position, imgWidth, imgHeight)
      heightLeft -= usableHeight
    }
    pdf.save(`Redis健康检查报告_${fileStamp}.pdf`)
    ElMessage.success("PDF 已下载")
  } catch (e: unknown) {
    console.error(e)
    ElMessage.error(e instanceof Error ? e.message : "PDF 生成失败，请稍后重试")
  } finally {
    loading.close()
    host.remove()
  }
}

watch(() => props.initialServers, (val) => {
  if (val && !servers.value.trim()) {
    servers.value = val
  }
})

onMounted(() => {
  if (props.initialServers) {
    servers.value = props.initialServers
  }
  if (props.autoRun && servers.value.trim() && !hasAutoRun.value) {
    hasAutoRun.value = true
    runHealthCheck()
  }
})
</script>

<template>
  <div class="manage-diagnostic-online-verify">
    <div v-if="showInput" class="manage-diagnostic-panel manage-diagnostic-online-verify__input-panel">
      <h4 class="manage-diagnostic-panel__title">
        <el-icon><Connection /></el-icon>
        {{ title }}
      </h4>
      <p class="manage-diagnostic-online-verify__hint">
        请输入服务器信息（每行一个）。已纳管节点可只填 <code>ip:port</code>，自动使用平台密码；未纳管请填 <code>ip:port:password</code>。
      </p>
      <el-input
        v-model="servers"
        type="textarea"
        :rows="6"
        class="manage-diagnostic-online-verify__textarea"
        placeholder="例如：&#10;10.0.0.1:6379&#10;10.0.0.2:6379:yourPassword"
      />
      <div class="manage-diagnostic-online-verify__actions">
        <el-button type="primary" :loading="running" @click="runHealthCheck">
          健康检查
        </el-button>
        <el-button @click="clearResult">清除信息</el-button>
        <el-button type="primary" plain :disabled="!resultText" @click="downloadPdf">下载PDF</el-button>
      </div>
    </div>

    <div class="manage-diagnostic-panel manage-diagnostic-online-verify__result-panel">
      <h4 class="manage-diagnostic-panel__title">
        <el-icon><EditPen /></el-icon>
        验证结果
      </h4>

      <div v-if="running" class="health-check-empty">检查中...</div>
      <div v-else-if="!healthResult" class="health-check-empty">
        <template v-if="showInput">暂无结果，请先执行健康检查</template>
        <template v-else>
          <span>暂无结果</span>
          <el-button type="primary" size="small" :loading="running" @click="runHealthCheck">开始检查</el-button>
        </template>
      </div>
      <div v-else-if="!healthResult.targets?.length" class="health-check-empty health-check-empty--error">
        {{ healthResult.result || "无有效结果" }}
      </div>

      <div v-else class="health-check-list app-fault-diagnostic-page">
        <div
          v-for="(target, idx) in healthResult.targets"
          :key="`${target.host}-${idx}`"
          class="health-check-card"
          :class="{ 'is-error': !target.allOk || !!target.connectError }"
        >
          <div class="health-check-card__header">
            <div class="health-check-card__title">
              <span class="health-check-card__host">{{ target.host }}</span>
              <el-tag size="small" :type="modeTagType(target.mode)" effect="plain">
                {{ target.modeLabel || target.mode || "未知" }}
              </el-tag>
              <el-tag v-if="target.managed" size="small" type="primary" effect="plain">
                纳管{{ target.appName ? ` · ${target.appName}` : "" }}
              </el-tag>
            </div>
            <div class="health-check-card__summary">
              <template v-if="target.connectError">
                <el-tag type="danger" effect="dark" class="health-check-status-tag">
                  <span class="health-check-status-tag__inner">
                    <el-icon><CircleClose /></el-icon>
                    <span>连接失败</span>
                  </span>
                </el-tag>
              </template>
              <template v-else-if="target.allOk">
                <el-tag type="success" effect="dark" class="health-check-status-tag">
                  <span class="health-check-status-tag__inner">
                    <el-icon><CircleCheck /></el-icon>
                    <span>全部通过</span>
                  </span>
                </el-tag>
              </template>
              <template v-else>
                <el-tag type="danger" effect="dark" class="health-check-status-tag">
                  <span class="health-check-status-tag__inner">
                    <el-icon><WarningFilled /></el-icon>
                    <span>{{ errorCount(target) }} 项异常</span>
                  </span>
                </el-tag>
                <el-tag type="success" effect="plain">{{ infoCount(target) }} 项正常</el-tag>
              </template>
              <el-button
                v-if="idx === 0 && !showInput"
                type="primary"
                size="small"
                :loading="running"
                class="health-check-pdf-btn"
                @click="runHealthCheck"
              >
                重新检查
              </el-button>
              <el-button
                v-if="idx === 0 && !showInput"
                type="primary"
                plain
                size="small"
                class="health-check-pdf-btn"
                @click="downloadPdf"
              >
                下载PDF
              </el-button>
            </div>
          </div>

          <div class="health-check-card__body">
            <el-alert
              v-if="target.connectError"
              type="error"
              :closable="false"
              show-icon
              :title="target.connectError"
              class="health-check-card__alert"
            />

            <template v-else>
              <div
                v-for="group in groupedChecks(target)"
                :key="`${target.host}-${group.name}`"
                class="app-fault-diagnostic-group"
              >
                <div class="app-fault-diagnostic-group__title">
                  <el-icon><FolderOpened /></el-icon>
                  {{ group.name }}
                  <span class="app-fault-diagnostic-group__count">{{ group.items.length }} 项</span>
                </div>
                <ul class="app-fault-diagnostic-check-list">
                  <li
                    v-for="check in group.items"
                    :key="check.key"
                    class="app-fault-diagnostic-check"
                    :class="levelClass(check.level)"
                  >
                    <div class="app-fault-diagnostic-check__icon">
                      <el-icon>
                        <component :is="getLevelIcon(check.level)" />
                      </el-icon>
                    </div>
                    <div class="app-fault-diagnostic-check__main">
                      <div class="app-fault-diagnostic-check__head">
                        <span
                          class="app-fault-diagnostic-check__level-tag"
                          :class="`app-fault-diagnostic-check__level-tag--${check.level.toLowerCase()}`"
                        >
                          {{ check.level }}
                        </span>
                        <strong class="app-fault-diagnostic-check__name">{{ check.name }}</strong>
                      </div>
                      <div class="app-fault-diagnostic-check__summary">{{ check.summary }}</div>
                      <div v-if="check.detail" class="app-fault-diagnostic-check__detail">{{ check.detail }}</div>
                      <button
                        type="button"
                        class="app-fault-diagnostic-check__raw-toggle"
                        :class="{ 'is-open': rawOpen[check.key] }"
                        @click="toggleRaw(check.key)"
                      >
                        原始输出
                      </button>
                      <pre
                        v-show="rawOpen[check.key]"
                        class="app-fault-diagnostic-check__raw"
                      >{{ check.raw }}</pre>
                    </div>
                  </li>
                </ul>
              </div>
            </template>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>
