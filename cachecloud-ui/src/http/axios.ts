import type { AxiosInstance, AxiosRequestConfig } from "axios"
import { getToken } from "@@/utils/local-storage"
import axios from "axios"
import { get, merge } from "lodash-es"
import { useUserStore } from "@/pinia/stores/user"

/** 401 未授权：登录失败仅提示；已登录态过期则清 token，不整页刷新 */
function handleUnauthorized(message?: string) {
  const hadToken = !!getToken()
  if (hadToken) {
    useUserStore().resetToken()
  }
  ElMessage.error(message || "未授权")
  return Promise.reject(new Error(message || "未授权"))
}

/** 创建请求实例 */
function createInstance() {
  // 创建一个 axios 实例命名为 instance
  const instance = axios.create()
  // 请求拦截器
  instance.interceptors.request.use(
    // 发送之前
    config => config,
    // 发送失败
    error => Promise.reject(error)
  )
  // 响应拦截器（可根据具体业务作出相应的调整）
  instance.interceptors.response.use(
    (response) => {
      // apiData 是 api 返回的数据
      const apiData = response.data
      // 二进制数据则直接返回
      const responseType = response.config.responseType
      if (responseType === "blob" || responseType === "arraybuffer") return apiData
      // 这个 code 是和后端约定的业务 code
      const code = apiData.code
      // 如果没有 code, 代表这不是项目后端开发的 api
      if (code === undefined) {
        ElMessage.error("非本系统的接口")
        return Promise.reject(new Error("非本系统的接口"))
      }
      switch (code) {
        case 0:
          // 本系统采用 code === 0 来表示没有业务错误
          return apiData
        case 401:
          return handleUnauthorized(apiData.message)
        default: {
          // 不是正确的 code：拦截器统一提示，reject 带上真实文案供业务侧使用（勿再弹一次）
          const msg = apiData.message || "请求失败"
          ElMessage.error(msg)
          return Promise.reject(new Error(msg))
        }
      }
    },
    (error) => {
      // status 是 HTTP 状态码
      const status = get(error, "response.status")
      const message = get(error, "response.data.message")
      switch (status) {
        case 400:
          error.message = "请求错误"
          break
        case 401:
          error.message = message || "未授权"
          return handleUnauthorized(error.message)
        case 403:
          error.message = message || "拒绝访问"
          break
        case 404:
          error.message = "请求地址出错"
          break
        case 408:
          error.message = "请求超时"
          break
        case 500:
          error.message = "服务器内部错误"
          break
        case 501:
          error.message = "服务未实现"
          break
        case 502:
          error.message = "网关错误"
          break
        case 503:
          error.message = "服务不可用"
          break
        case 504:
          error.message = "网关超时"
          break
        case 505:
          error.message = "HTTP 版本不受支持"
          break
      }
      ElMessage.error(error.message)
      return Promise.reject(error)
    }
  )
  return instance
}

/** 创建请求方法 */
function createRequest(instance: AxiosInstance) {
  return <T>(config: AxiosRequestConfig): Promise<T> => {
    // FormData 必须由浏览器自己写 Content-Type，它要在里面带上 multipart 的 boundary。
    // 这里如果照旧塞 application/json，请求体是 FormData 但头部声明是 JSON，
    // 后端解析不出 multipart，报「Current request is not a multipart request」。
    const isFormData = typeof FormData !== "undefined" && config.data instanceof FormData
    // 默认配置
    const defaultConfig: AxiosRequestConfig = {
      // 接口地址
      baseURL: import.meta.env.VITE_BASE_URL,
      // 请求头
      headers: {
        "Content-Type": isFormData ? undefined : "application/json"
      },
      // 请求体
      data: {},
      // 请求超时
      timeout: 30000,
      // 携带 Cookie（CacheCloud 会话鉴权）
      withCredentials: true
    }
    // 将默认配置 defaultConfig 和传入的自定义配置 config 进行合并成为 mergeConfig
    const mergeConfig = merge(defaultConfig, config)
    return instance(mergeConfig)
  }
}

/** 用于请求的节点 */
const instance = createInstance()

/** 用于请求的方法 */
export const request = createRequest(instance)
