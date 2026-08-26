import { useAutoQuery } from "@@/composables/useAutoQuery"
import { describe, expect, it, vi } from "vitest"
import { nextTick, reactive, ref } from "vue"

describe("useAutoQuery", () => {
  it("时间区间变化时触发查询", async () => {
    const range = ref<[string, string]>(["2026-08-01 00:00:00", "2026-08-02 00:00:00"])
    const run = vi.fn()
    useAutoQuery(range, run)

    range.value = ["2026-08-03 00:00:00", "2026-08-04 00:00:00"]
    await nextTick()
    expect(run).toHaveBeenCalledTimes(1)
  })

  it("内容相同的新数组不触发——服务端回写归一化区间时会产生这种赋值", async () => {
    const range = ref<[string, string]>(["2026-08-01 00:00:00", "2026-08-02 00:00:00"])
    const run = vi.fn()
    useAutoQuery(range, run)

    range.value = ["2026-08-01 00:00:00", "2026-08-02 00:00:00"]
    await nextTick()
    expect(run).not.toHaveBeenCalled()
  })

  it("silent 包住的赋值不触发，即使内容确实变了", async () => {
    const range = ref<[string, string]>(["2026-08-01 00:00:00", "2026-08-02 00:00:00"])
    const run = vi.fn()
    const { silent } = useAutoQuery(range, run)

    silent(() => {
      range.value = ["2026-08-10 00:00:00", "2026-08-11 00:00:00"]
    })
    await nextTick()
    expect(run).not.toHaveBeenCalled()
  })

  it("silent 之后恢复自动查询，不会永久静音", async () => {
    const range = ref<[string, string]>(["2026-08-01 00:00:00", "2026-08-02 00:00:00"])
    const run = vi.fn()
    const { silent } = useAutoQuery(range, run)

    silent(() => {
      range.value = ["2026-08-10 00:00:00", "2026-08-11 00:00:00"]
    })
    await nextTick()
    await nextTick()

    range.value = ["2026-08-20 00:00:00", "2026-08-21 00:00:00"]
    await nextTick()
    expect(run).toHaveBeenCalledTimes(1)
  })

  it("支持 getter 形式的数据源——服务端统计页的 query.searchDate 是响应式对象属性", async () => {
    const query = reactive({ searchDate: "2026-08-01" })
    const run = vi.fn()
    useAutoQuery(() => query.searchDate, run)

    query.searchDate = "2026-08-02"
    await nextTick()
    expect(run).toHaveBeenCalledTimes(1)
  })

  it("清空为 null 也算变化（审计日志的重置场景）", async () => {
    const range = ref<[string, string] | null>(["2026-08-01 00:00:00", "2026-08-02 00:00:00"])
    const run = vi.fn()
    useAutoQuery(range, run)

    range.value = null
    await nextTick()
    expect(run).toHaveBeenCalledTimes(1)
  })
})
