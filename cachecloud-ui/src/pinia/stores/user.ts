import { getCurrentUserApi } from "@@/apis/users"
import { setToken as _setToken, getToken, removeToken } from "@@/utils/local-storage"
import { logoutApi } from "@/pages/login/apis"
import { pinia } from "@/pinia"
import { resetRouter } from "@/router"
import { routerConfig } from "@/router/config"
import { useSettingsStore } from "./settings"
import { useTagsViewStore } from "./tags-view"

export const useUserStore = defineStore("user", () => {
  const token = ref<string>(getToken() || "")

  const roles = ref<string[]>([])

  const username = ref<string>("")

  const tagsViewStore = useTagsViewStore()

  const settingsStore = useSettingsStore()

  // 浏览器只保存非敏感登录标记，真实会话令牌由 HttpOnly Cookie 承载。
  const setSessionActive = () => {
    _setToken("active")
    token.value = "active"
  }

  // 获取用户详情
  const getInfo = async () => {
    const { data } = await getCurrentUserApi()
    username.value = data.username
    // 验证返回的 roles 是否为一个非空数组，否则塞入一个没有任何作用的默认角色，防止路由守卫逻辑进入无限循环
    roles.value = data.roles?.length > 0 ? data.roles : routerConfig.defaultRoles
  }

  // 登出
  const logout = async () => {
    try {
      await logoutApi()
    } catch {
      // 忽略网络错误，仍清理本地状态
    }
    removeToken()
    token.value = ""
    roles.value = []
    resetRouter()
    resetTagsView()
  }

  // 重置 Token
  const resetToken = () => {
    removeToken()
    token.value = ""
    roles.value = []
  }

  // 重置 Visited Views 和 Cached Views
  const resetTagsView = () => {
    if (!settingsStore.cacheTagsView) {
      tagsViewStore.delAllVisitedViews()
      tagsViewStore.delAllCachedViews()
    }
  }

  return { token, roles, username, setSessionActive, getInfo, logout, resetToken }
})

/**
 * @description 在 SPA 应用中可用于在 pinia 实例被激活前使用 store
 * @description 在 SSR 应用中可用于在 setup 外使用 store
 */
export function useUserStoreOutside() {
  return useUserStore(pinia)
}
