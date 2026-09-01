const SYSTEM_NAME = "cachecloud-ui"

/** 缓存数据时用到的 Key */
export class CacheKey {
  static readonly TOKEN = `${SYSTEM_NAME}-token-key`
  static readonly CONFIG_LAYOUT = `${SYSTEM_NAME}-config-layout-key`
  static readonly SIDEBAR_STATUS = `${SYSTEM_NAME}-sidebar-status-key`
  static readonly ACTIVE_THEME_NAME = `${SYSTEM_NAME}-active-theme-name-key`
  static readonly VISITED_VIEWS = `${SYSTEM_NAME}-visited-views-key`
  static readonly CACHED_VIEWS = `${SYSTEM_NAME}-cached-views-key`
  /**
   * 图表排序偏好。
   *
   * 带版本号：默认顺序整体调整过一次，存量用户本地存着旧顺序，
   * 不换 key 的话新默认永远不会生效（mergeChartOrder 以存储的为准）。
   */
  static readonly METRIC_CHART_ORDER = `${SYSTEM_NAME}-metric-chart-order-key-v2`
}
