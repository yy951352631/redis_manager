import type { MetricChartSlot } from "@/pages/cachecloud/components/metric-chart-slots"
import { describe, expect, it } from "vitest"
import { mergeChartOrder, METRIC_CHART_SLOTS } from "@/pages/cachecloud/components/metric-chart-slots"

function slot(key: string): MetricChartSlot {
  return { key, title: key, source: "app", statNames: [key], seriesNames: [key] }
}

const slots = [slot("a"), slot("b"), slot("c")]
const keysOf = (list: MetricChartSlot[]) => list.map(item => item.key)

describe("mergeChartOrder", () => {
  it("没有存储偏好时按默认定义顺序", () => {
    expect(keysOf(mergeChartOrder(slots, null))).toEqual(["a", "b", "c"])
    expect(keysOf(mergeChartOrder(slots, []))).toEqual(["a", "b", "c"])
  })

  it("按存储顺序重排", () => {
    expect(keysOf(mergeChartOrder(slots, ["c", "a", "b"]))).toEqual(["c", "a", "b"])
  })

  it("存储中已不存在的键被丢弃，不产生幽灵卡片", () => {
    expect(keysOf(mergeChartOrder(slots, ["c", "removed", "a", "b"]))).toEqual(["c", "a", "b"])
  })

  it("存储后新增的槽位补到末尾，且保持默认定义中的相对次序", () => {
    expect(keysOf(mergeChartOrder(slots, ["c"]))).toEqual(["c", "a", "b"])
    expect(keysOf(mergeChartOrder(slots, ["b"]))).toEqual(["b", "a", "c"])
  })

  it("不修改传入的数组", () => {
    const original = [...slots]
    mergeChartOrder(slots, ["c", "b", "a"])
    expect(slots).toEqual(original)
  })

  it("任何存储顺序都不会丢图或重复", () => {
    const stored = ["memory", "qps", "commands"]
    const merged = mergeChartOrder(METRIC_CHART_SLOTS, stored)
    expect(merged).toHaveLength(METRIC_CHART_SLOTS.length)
    expect(new Set(keysOf(merged)).size).toBe(METRIC_CHART_SLOTS.length)
    expect(keysOf(merged).slice(0, 3)).toEqual(stored)
  })
})

describe("mETRIC_CHART_SLOTS", () => {
  it("槽位键唯一——localStorage 的排序依赖键唯一性", () => {
    const keys = keysOf(METRIC_CHART_SLOTS)
    expect(new Set(keys).size).toBe(keys.length)
  })

  it("每条序列都有对应的名称", () => {
    for (const item of METRIC_CHART_SLOTS) {
      expect(item.seriesNames.length).toBe(item.statNames.length)
    }
  })
})
