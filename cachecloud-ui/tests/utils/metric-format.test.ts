import { formatMetricTime, formatMetricValue, formatMetricValueWithUnit } from "@@/utils/metric-format"
import { describe, expect, it } from "vitest"

describe("formatMetricValue", () => {
  it("整数原样输出并加千分位", () => {
    expect(formatMetricValue(0)).toBe("0")
    expect(formatMetricValue(42)).toBe("42")
    expect(formatMetricValue(12345678)).toBe("12,345,678")
    expect(formatMetricValue(-1000)).toBe("-1,000")
  })

  it("有小数的保留两位", () => {
    expect(formatMetricValue(3.5714285714285716)).toBe("3.57")
    expect(formatMetricValue(1.005)).toBe("1.01")
    expect(formatMetricValue(-2.348)).toBe("-2.35")
    expect(formatMetricValue(1234.5)).toBe("1,234.50")
  })

  it("两位小数会舍成 0.00 的极小非零值改用两位有效数字", () => {
    expect(formatMetricValue(0.00012)).toBe("0.00012")
    expect(formatMetricValue(0.004)).toBe("0.004")
    // 0.005 已经能被两位小数表达，走常规分支
    expect(formatMetricValue(0.005)).toBe("0.01")
  })

  it("空值与非法值统一显示为短横线", () => {
    expect(formatMetricValue(null)).toBe("-")
    expect(formatMetricValue(undefined)).toBe("-")
    expect(formatMetricValue("")).toBe("-")
    expect(formatMetricValue("abc")).toBe("-")
    expect(formatMetricValue(Number.NaN)).toBe("-")
  })

  it("字符串数字按数值处理", () => {
    expect(formatMetricValue("3.14159")).toBe("3.14")
    expect(formatMetricValue("100")).toBe("100")
  })
})

describe("formatMetricValueWithUnit", () => {
  it("百分号紧贴数值，其他单位用空格分隔", () => {
    expect(formatMetricValueWithUnit(12.345, "%")).toBe("12.35%")
    expect(formatMetricValueWithUnit(2048, "MB")).toBe("2,048 MB")
    expect(formatMetricValueWithUnit(1.5)).toBe("1.50")
    expect(formatMetricValueWithUnit(null, "MB")).toBe("-")
  })
})

describe("formatMetricTime", () => {
  it("输出 MM-DD HH:mm:ss，各段补零", () => {
    const stamp = new Date(2026, 0, 5, 9, 8, 7).getTime()
    expect(formatMetricTime(stamp)).toBe("01-05 09:08:07")
  })
})
