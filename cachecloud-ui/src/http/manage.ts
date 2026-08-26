import type { AxiosRequestConfig } from "axios"
import { getToken } from "@@/utils/local-storage"
import axios from "axios"

/** 旧版 JSP/AJAX 接口统一响应（status === 1 表示成功） */
export interface AjaxResult<T = unknown> {
  status: number
  message?: string
  data?: T
}

/** 请求 /manage/* 等旧接口（不走 /api/v1 的 code 约定） */
export async function manageRequest<T>(config: AxiosRequestConfig): Promise<T> {
  const token = getToken()
  let res
  try {
    res = await axios<AjaxResult<T>>({
      withCredentials: true,
      timeout: 60000,
      headers: {
        Authorization: token ? `Bearer ${token}` : undefined
      },
      ...config
    })
  } catch (e) {
    // 未登录/无权限时后端返回 401/403 + AjaxResult，取出后端文案而非 axios 的通用报错
    const message = axios.isAxiosError(e) ? (e.response?.data as AjaxResult | undefined)?.message : undefined
    throw new Error(message || (e instanceof Error ? e.message : "请求失败"))
  }
  const body = res.data
  if (!body || body.status !== 1) {
    throw new Error(body?.message || "请求失败")
  }
  return body.data as T
}

/** application/x-www-form-urlencoded POST */
export function toFormBody(data: Record<string, string | number | undefined>) {
  const params = new URLSearchParams()
  for (const [k, v] of Object.entries(data)) {
    if (v !== undefined && v !== null && v !== "") params.append(k, String(v))
  }
  return params
}
