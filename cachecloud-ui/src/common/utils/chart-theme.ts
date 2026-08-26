import type * as EchartsModule from "echarts"

const THEME_NAME = "cachecloud-grafana-light"

const theme = {
  color: ["#3274d9", "#73bf69", "#f2cc0c", "#ff9830", "#e02f44", "#8f3bb8", "#5794f2", "#56a64b"],
  backgroundColor: "transparent",
  textStyle: {
    color: "#374151",
    fontFamily: "Inter, system-ui, -apple-system, BlinkMacSystemFont, Segoe UI, sans-serif"
  },
  title: {
    textStyle: { color: "#1f2937", fontSize: 14, fontWeight: 600 },
    subtextStyle: { color: "#6b7280", fontSize: 12 }
  },
  line: {
    symbol: "circle",
    symbolSize: 4,
    smooth: false,
    lineStyle: { width: 2 },
    itemStyle: { borderWidth: 1 }
  },
  bar: { barMaxWidth: 36, itemStyle: { borderRadius: [3, 3, 0, 0] } },
  categoryAxis: {
    axisLine: { show: true, lineStyle: { color: "#d1d5db" } },
    axisTick: { show: false },
    axisLabel: { color: "#6b7280", fontSize: 11, hideOverlap: true },
    splitLine: { show: false }
  },
  valueAxis: {
    axisLine: { show: false },
    axisTick: { show: false },
    axisLabel: { color: "#6b7280", fontSize: 11, hideOverlap: true },
    splitLine: { show: true, lineStyle: { color: "#e5e7eb", width: 1, type: "dashed" } },
    splitArea: { show: false }
  },
  timeAxis: {
    axisLine: { show: true, lineStyle: { color: "#d1d5db" } },
    axisTick: { show: false },
    axisLabel: { color: "#6b7280", fontSize: 11, hideOverlap: true },
    splitLine: { show: true, lineStyle: { color: "#eef0f3", width: 1, type: "dashed" } }
  },
  legend: {
    textStyle: { color: "#4b5563", fontSize: 11 },
    itemWidth: 14,
    itemHeight: 8,
    itemGap: 14,
    pageTextStyle: { color: "#6b7280" }
  },
  tooltip: {
    backgroundColor: "rgba(17, 24, 39, 0.96)",
    borderColor: "#374151",
    borderWidth: 1,
    padding: [8, 10],
    textStyle: { color: "#f9fafb", fontSize: 12 },
    axisPointer: { lineStyle: { color: "#9ca3af", width: 1 }, crossStyle: { color: "#9ca3af" } }
  }
}

export function initGrafanaChart(echarts: typeof EchartsModule, element: HTMLElement) {
  echarts.registerTheme(THEME_NAME, theme)
  return echarts.init(element, THEME_NAME)
}
