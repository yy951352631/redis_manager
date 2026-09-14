/**
 * 把一段 DOM 截图后切分成 A4 多页 PDF。
 *
 * 抽取自键值分析报告与健康检查两处各自维护的同一份实现——第三个导出入口出现时
 * 再复制一遍，就意味着分页、留白、缩放的修复要改三个地方。
 */

export interface PdfExportOptions {
  /** 输出文件名，不含扩展名 */
  fileName: string
  /** 截图缩放，越大越清晰但内存占用越高 */
  scale?: number
  /** 页边距(mm) */
  margin?: number
  /** JPEG 质量 0~1 */
  quality?: number
  /** 按匹配到的子元素逐页输出，适用于需要稳定分页的报告 */
  pageSelector?: string
}

/**
 * @param element 要导出的容器；必须在文档视口内且可见——
 *   opacity:0 或移出视口会让 html2canvas 截出空白页
 */
export async function exportElementToPdf(element: HTMLElement, options: PdfExportOptions) {
  const { fileName, scale = 1.5, margin = 7, quality = 0.94, pageSelector } = options

  const [{ default: html2canvas }, { jsPDF: JsPdf }] = await Promise.all([
    import("html2canvas"),
    import("jspdf")
  ])

  const pdf = new JsPdf({ orientation: "portrait", unit: "mm", format: "a4" })
  const pageWidth = pdf.internal.pageSize.getWidth()
  const pageHeight = pdf.internal.pageSize.getHeight()
  const usableWidth = pageWidth - margin * 2
  const usableHeight = pageHeight - margin * 2

  const capture = (target: HTMLElement) => html2canvas(target, {
    scale,
    useCORS: true,
    backgroundColor: "#ffffff",
    logging: false,
    width: target.scrollWidth,
    height: target.scrollHeight,
    windowWidth: target.scrollWidth,
    windowHeight: target.scrollHeight
  })

  if (pageSelector) {
    const pages = Array.from(element.querySelectorAll<HTMLElement>(pageSelector))
    if (!pages.length) throw new Error("未找到 PDF 分页内容")
    for (let index = 0; index < pages.length; index++) {
      const pageCanvas = await capture(pages[index])
      if (index > 0) pdf.addPage()
      const renderedHeight = Math.min(usableHeight, (pageCanvas.height * usableWidth) / pageCanvas.width)
      pdf.addImage(pageCanvas.toDataURL("image/jpeg", quality), "JPEG", margin, margin, usableWidth, renderedHeight)
    }
    pdf.save(`${fileName}.pdf`)
    return
  }

  const canvas = await capture(element)
  // 按可用宽高比换算出每页能容纳多少源像素，再逐页裁切
  const pagePixelHeight = Math.max(1, Math.floor((canvas.width * usableHeight) / usableWidth))

  let sourceY = 0
  let pageIndex = 0
  while (sourceY < canvas.height) {
    const sliceHeight = Math.min(pagePixelHeight, canvas.height - sourceY)
    const pageCanvas = document.createElement("canvas")
    pageCanvas.width = canvas.width
    pageCanvas.height = sliceHeight
    const context = pageCanvas.getContext("2d")
    if (!context) throw new Error("无法创建 PDF 页面画布")
    // 先铺白底：源图透明区域在 JPEG 里会变成黑块
    context.fillStyle = "#ffffff"
    context.fillRect(0, 0, pageCanvas.width, pageCanvas.height)
    context.drawImage(canvas, 0, sourceY, canvas.width, sliceHeight, 0, 0, canvas.width, sliceHeight)
    if (pageIndex > 0) pdf.addPage()
    const renderedHeight = (sliceHeight * usableWidth) / canvas.width
    pdf.addImage(pageCanvas.toDataURL("image/jpeg", quality), "JPEG", margin, margin, usableWidth, renderedHeight)
    sourceY += sliceHeight
    pageIndex++
  }

  pdf.save(`${fileName}.pdf`)
}
