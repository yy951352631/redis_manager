import type * as Auth from "./type"
import { request } from "@/http/axios"

/** CacheCloud 登录 */
export function loginApi(data: Auth.LoginRequestData) {
  return request<Auth.LoginResponseData>({
    url: "auth/login",
    method: "post",
    data: {
      username: data.username,
      password: data.password,
      isAdmin: data.isAdmin ?? true
    }
  })
}

/** CacheCloud 登出 */
export function logoutApi() {
  return request<ApiResponseData<null>>({
    url: "auth/logout",
    method: "post"
  })
}
