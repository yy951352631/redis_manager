function escapeHtml(text: string) {
  return text
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
}

function parseTableRow(line: string): string[] | null {
  const trimmed = line.trim()
  if (!trimmed || trimmed.charAt(0) !== "|") return null
  const cells = trimmed.split("|")
  if (cells[0] === "") cells.shift()
  if (cells[cells.length - 1] === "") cells.pop()
  return cells.map(c => c.trim()).length ? cells.map(c => c.trim()) : null
}

function isTableSeparatorRow(cells: string[]) {
  return cells.length > 0 && cells.every(c => /^:?-{3,}:?$/.test(c))
}

function normalizeTableLines(text: string) {
  if (!text || !text.includes("|")) return text
  return text.replace(/\|\s*\|(?=\s*[-\w\u4E00-\u9FFF])/g, "|\n|")
}

function buildTableHtml(tableLines: string[]) {
  const rows: string[][] = []
  for (const line of tableLines) {
    const parsed = parseTableRow(line)
    if (parsed) rows.push(parsed)
  }
  if (rows.length < 2) return null

  const header = rows[0]
  let bodyStart = 1
  if (rows.length > 1 && isTableSeparatorRow(rows[1])) bodyStart = 2
  if (bodyStart >= rows.length) return null

  let html = "<div class=\"rp-ai-table-wrap\"><table class=\"rp-ai-table\"><thead><tr>"
  for (const h of header) html += `<th>${escapeHtml(h)}</th>`
  html += "</tr></thead><tbody>"
  for (let r = bodyStart; r < rows.length; r++) {
    if (isTableSeparatorRow(rows[r])) continue
    html += "<tr>"
    for (const c of rows[r]) html += `<td>${escapeHtml(c)}</td>`
    html += "</tr>"
  }
  html += "</tbody></table></div>"
  return html
}

function extractTables(text: string, blocks: string[]) {
  const normalized = normalizeTableLines(text)
  const lines = normalized.split("\n")
  const out: string[] = []
  let i = 0
  while (i < lines.length) {
    if (/^\s*\|/.test(lines[i])) {
      const tableLines: string[] = []
      while (i < lines.length && /^\s*\|/.test(lines[i])) {
        tableLines.push(lines[i])
        i++
      }
      const tableHtml = buildTableHtml(tableLines)
      if (tableHtml) {
        const key = `%%TABLE_${blocks.length}%%`
        blocks.push(tableHtml)
        out.push(key)
      } else {
        out.push(tableLines.join("\n"))
      }
    } else {
      out.push(lines[i])
      i++
    }
  }
  return out.join("\n")
}

/** 轻量 Markdown 渲染（对齐旧版 RpAiAssistant.renderMarkdown） */
export function renderMarkdown(text: string) {
  if (!text) return ""
  const blocks: string[] = []
  let src = text.replace(/```([\s\S]*?)```/g, (_, code: string) => {
    const key = `%%CODE_${blocks.length}%%`
    blocks.push(`<pre class="rp-ai-code"><code>${escapeHtml(code.trim())}</code></pre>`)
    return key
  })
  src = extractTables(src, blocks)

  let html = escapeHtml(src)
  html = html.replace(/\*\*(.+?)\*\*/g, "<strong>$1</strong>")
  html = html.replace(/`([^`\n]+)`/g, "<code>$1</code>")
  html = html.replace(/^#### (.+)$/gm, "<h4>$1</h4>")
  html = html.replace(/^### (.+)$/gm, "<h4>$1</h4>")
  html = html.replace(/^## (.+)$/gm, "<h3>$1</h3>")
  html = html.replace(/^# (.+)$/gm, "<h3>$1</h3>")
  html = html.replace(/^\s*[-*] (.+)$/gm, "<li>$1</li>")
  html = html.replace(/(<li>[\s\S]*?<\/li>(\n<li>[\s\S]*?<\/li>)*)/g, "<ul>$1</ul>")
  html = html.replace(/\n{2,}/g, "</p><p>")
  html = `<p>${html}</p>`
  html = html.replace(/<p>\s*<\/p>/g, "")
  html = html.replace(/<p>(<h[34]>)/g, "$1")
  html = html.replace(/(<\/h[34]>)<\/p>/g, "$1")
  html = html.replace(/<p>(<ul>)/g, "$1")
  html = html.replace(/(<\/ul>)<\/p>/g, "$1")
  html = html.replace(/<p>(<pre)/g, "$1")
  html = html.replace(/(<\/pre>)<\/p>/g, "$1")
  html = html.replace(/<p>(%%TABLE_\d+%%)/g, "$1")
  html = html.replace(/(%%TABLE_\d+%%)<\/p>/g, "$1")

  for (let i = 0; i < blocks.length; i++) {
    html = html.replace(`%%CODE_${i}%%`, blocks[i])
    html = html.replace(`%%TABLE_${i}%%`, blocks[i])
  }
  return `<div class="rp-ai-md">${html}</div>`
}
