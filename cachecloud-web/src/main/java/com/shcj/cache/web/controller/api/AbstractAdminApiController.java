package com.shcj.cache.web.controller.api;

import com.shcj.cache.constant.AppUserTypeEnum;
import com.shcj.cache.entity.AppUser;
import com.shcj.cache.web.controller.BaseController;
import com.shcj.cache.web.vo.ApiResponse;
import org.apache.commons.lang3.StringUtils;

import javax.servlet.http.HttpServletRequest;

abstract class AbstractAdminApiController extends BaseController {

    protected AppUser resolveApiUser(HttpServletRequest request) {
        String userName = userLoginStatusService.getUserNameFromLoginStatus(request);
        if (StringUtils.isBlank(userName)) {
            String authHeader = request.getHeader("Authorization");
            if (StringUtils.isNotBlank(authHeader) && authHeader.startsWith("Bearer ")) {
                userName = authHeader.substring(7).trim();
            }
        }
        if (StringUtils.isBlank(userName)) {
            return null;
        }
        AppUser user = userService.getByName(userName);
        if (user == null || AppUserTypeEnum.NO_USER.value().equals(user.getType())) {
            return null;
        }
        return user;
    }

    /**
     * 只校验登录，不区分角色。用于所有平台用户都可查看的只读接口。
     */
    protected <T> ApiResponse<T> requireLogin(HttpServletRequest request) {
        if (resolveApiUser(request) == null) {
            return ApiResponse.fail(401, "未登录或登录已过期");
        }
        return null;
    }

    protected <T> ApiResponse<T> requireAdmin(HttpServletRequest request) {
        AppUser user = resolveApiUser(request);
        if (user == null) {
            return ApiResponse.fail(401, "未登录或登录已过期");
        }
        if (!AppUserTypeEnum.ADMIN_USER.value().equals(user.getType())) {
            return ApiResponse.fail(403, "需要管理员权限");
        }
        return null;
    }
}
