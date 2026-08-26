/**
 * Tab 懒加载 + 访问后缓存：仅首次激活时挂载，切换回来保留状态。
 */
export function useTabCache(getActiveTab: () => string) {
  const visitedTabs = ref(new Set<string>())

  function visitTab(tab: string) {
    if (!tab) return
    visitedTabs.value.add(tab)
  }

  function onTabChange(tab: string | number | boolean) {
    visitTab(String(tab))
  }

  function isVisited(tab: string) {
    return visitedTabs.value.has(tab)
  }

  function resetVisited() {
    visitedTabs.value = new Set()
  }

  watch(getActiveTab, (tab) => visitTab(tab), { immediate: true })

  return { visitedTabs, visitTab, onTabChange, isVisited, resetVisited }
}
