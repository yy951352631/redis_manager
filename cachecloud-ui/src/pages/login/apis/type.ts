export interface LoginRequestData {
  /** 用户名 */
  username: string
  /** 密码 */
  password: string
  /** 是否以管理端身份登录 */
  isAdmin?: boolean
}

export type LoginResponseData = ApiResponseData<{
  token: string
  username: string
  roles: string[]
}>
