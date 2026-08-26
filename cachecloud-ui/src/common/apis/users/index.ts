import type * as Users from "./type"
import { request } from "@/http/axios"

/** 获取当前登录用户详情 */
export function getCurrentUserApi() {
  return request<Users.CurrentUserResponseData>({
    url: "users/me",
    method: "get"
  })
}

/** 更新当前登录用户资料 */
export function updateCurrentUserProfileApi(data: Users.UserProfileUpdateRequest) {
  return request<ApiResponseData<void>>({
    url: "users/me",
    method: "put",
    data
  })
}
