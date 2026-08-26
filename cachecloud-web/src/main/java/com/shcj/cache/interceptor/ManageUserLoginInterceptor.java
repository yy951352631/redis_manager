package com.shcj.cache.interceptor;

import com.shcj.cache.constant.AppUserTypeEnum;
import com.shcj.cache.entity.AppUser;
import com.shcj.cache.web.service.UserLoginStatusService;
import com.shcj.cache.web.service.UserService;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * 管理端登录验证
 *
 * @author leifu
 */
public class ManageUserLoginInterceptor implements HandlerInterceptor {
    @Autowired
    private UserService userService;
    @Autowired
    private UserLoginStatusService userLoginStatusService;

    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response, Object handler) throws Exception {

        String userName = userLoginStatusService.getUserNameFromLoginStatus(request);
        //未登录
        if (StringUtils.isBlank(userName)) {
            InterceptorResponse.unauthorized(response, "登录态已失效，请重新登录");
            return false;
        }
        AppUser user = userService.getByName(userName);
        //新用户
        if (user == null || user.getType() == -1) {
            InterceptorResponse.forbidden(response, "用户不存在或未开通 CacheCloud 权限");
            return false;
        }
        //必须是管理员
        if (!AppUserTypeEnum.ADMIN_USER.value().equals(user.getType())) {
            InterceptorResponse.forbidden(response, "需要管理员权限");
            return false;
        }
        request.setAttribute("userInfo", user);
        request.setAttribute("uri", request.getRequestURI());
        return true;

    }


}