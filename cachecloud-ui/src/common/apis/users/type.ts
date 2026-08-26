export interface CurrentUserProfile {
  id?: number
  username: string
  chName?: string
  email?: string
  mobile?: string
  weChat?: string
  company?: string
  purpose?: string
  type?: number
  isAlert?: number
  roles: string[]
}

export interface UserProfileUpdateRequest {
  chName: string
  email: string
  mobile: string
  weChat?: string
  company?: string
  isAlert?: number
  purpose?: string
}

export type CurrentUserResponseData = ApiResponseData<CurrentUserProfile>
