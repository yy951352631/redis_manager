import type { WatchSource } from "vue"

/**
 * 查询条件变化后自动执行查询。
 *
 * 用值比较而非引用比较：日期选择器每次都会产出新数组，而查询接口又常把服务端归一化后的
 * 区间写回 v-model，引用比较会把这种"内容没变的新对象"当成一次新变更，导致自查自。
 *
 * 内容确实变了的回写（例如服务端把区间截断到 7 天）用 `silent` 包住，
 * 否则会多打一次没必要的请求。
 */
export function useAutoQuery(
  source: WatchSource | WatchSource[],
  run: () => unknown
) {
  let suppressed = false

  watch(source, (next, prev) => {
    if (suppressed) return
    if (JSON.stringify(next) === JSON.stringify(prev)) return
    run()
  })

  /** 包住"程序自己改查询条件"的赋值，使其不触发自动查询 */
  function silent(assign: () => void) {
    suppressed = true
    try {
      assign()
    } finally {
      void nextTick(() => {
        suppressed = false
      })
    }
  }

  return { silent }
}
