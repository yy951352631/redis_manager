/** 声明 vite 环境变量的类型（如果未声明则默认是 any） */
interface ImportMetaEnv {
  readonly VITE_APP_TITLE: string
  readonly VITE_BASE_URL: string
  readonly VITE_CACHECLOUD_BACKEND: string
  readonly VITE_ROUTER_HISTORY: "hash" | "html5"
  readonly VITE_PUBLIC_PATH: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}

declare module "html2pdf.js" {
  interface Html2PdfWorker {
    set: (options: Record<string, unknown>) => Html2PdfWorker
    from: (element: HTMLElement | string) => Html2PdfWorker
    save: () => Promise<void>
  }

  interface Html2Pdf {
    (): Html2PdfWorker
  }

  const html2pdf: Html2Pdf
  export default html2pdf
}

declare module "html2canvas" {
  interface Html2CanvasOptions {
    scale?: number
    useCORS?: boolean
    backgroundColor?: string | null
    logging?: boolean
    width?: number
    height?: number
    windowWidth?: number
    windowHeight?: number
    scrollX?: number
    scrollY?: number
  }
  function html2canvas(element: HTMLElement, options?: Html2CanvasOptions): Promise<HTMLCanvasElement>
  export default html2canvas
}

declare module "jspdf" {
  export class jsPDF {
    constructor(options?: {
      orientation?: "portrait" | "landscape"
      unit?: "mm" | "pt" | "px" | "in"
      format?: string | number[]
    })
    internal: { pageSize: { getWidth: () => number, getHeight: () => number } }
    addImage: (
      imageData: string,
      format: string,
      x: number,
      y: number,
      w: number,
      h: number
    ) => void

    addPage: () => void
    save: (filename: string) => void
  }
}
