import type { InjectionKey, Ref } from "vue"

/**
 * 再次点击「当前已激活」的 tab 时递增的计数器。
 *
 * el-tabs 的 tab-change 只在切换时触发，重复点击同一个 tab 不会有任何事件；
 * 而 tab 内容被 keep-alive 缓存着，像键值分析这种带「列表 / 结果」两级视图的 tab
 * 会一直停在结果页，看上去就是「点了没反应」。
 *
 * 父级用 tab-click 捕捉重复点击并递增这个计数，需要回到一级视图的 tab 自己订阅即可，
 * 不关心的 tab 无需任何改动。
 */
export const TAB_RECLICK: InjectionKey<Ref<number>> = Symbol("app-detail-tab-reclick")
