package com.shcj.cache.interceptor;

import com.shcj.cache.constant.AppUserTypeEnum;
import com.shcj.cache.entity.AppToUser;
import com.shcj.cache.entity.AppUser;
import com.shcj.cache.entity.InstanceInfo;
import com.shcj.cache.stats.instance.InstanceStatsCenter;
import com.shcj.cache.web.service.AppService;
import com.shcj.cache.web.service.UserLoginStatusService;
import com.shcj.cache.web.service.UserService;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.math.NumberUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.List;

/**
 * 应用和实例权限验证
 *
 * @author leifu
 * @Date 2014年10月29日
 * @Time 下午3:18:00
 */
public class AppAndInstanceAuthorityInterceptor implements HandlerInterceptor {
    private Logger logger = LoggerFactory.getLogger(AppAndInstanceAuthorityInterceptor.class);
    @Autowired
    private AppService appService;
    @Autowired
    private UserService userService;
    @Autowired
    private InstanceStatsCenter instanceStatsCenter;
    @Autowired
    private UserLoginStatusService userLoginStatusService;

    @Override
    public boolean preHandle(HttpServletRequest request,
            HttpServletResponse response, Object handler) throws Exception {
        // 1. 获取用户
        String userName = userLoginStatusService.getUserNameFromLoginStatus(request);
        //未登录
        if (StringUtils.isBlank(userName)) {
            InterceptorResponse.unauthorized(response, "登录态已失效，请重新登录");
            return false;
        }
        AppUser user = userService.getByName(userName);
        if (user == null || user.getType() == -1) {
            InterceptorResponse.forbidden(response, "用户不存在或未开通 CacheCloud 权限");
            return false;
        }

        // 2. 管理员直接跳过
        if (AppUserTypeEnum.ADMIN_USER.value().equals(user.getType())) {
            return true;
        }

        // 3. 应用id
        String appId = request.getParameter("appId");
        if (StringUtils.isNotBlank(appId) && !hasUserAppPower(response, user, NumberUtils.toLong(appId))) {
            return false;
        }

        // 4. 实例权限检测(其实也是应用)
        String instanceId = request.getParameter("instanceId");
        if (StringUtils.isNotBlank(instanceId)) {
            InstanceInfo instanceInfo = instanceStatsCenter.getInstanceInfo(Long.parseLong(instanceId));
            if (instanceInfo != null && !hasUserAppPower(response, user, instanceInfo.getAppId())) {
                return false;
            }
        }

        return true;
    }

    /**
     * 检查用户应用的权限，无权限时直接以 403 结束请求
     *
     * @return true 表示允许继续处理
     */
    private boolean hasUserAppPower(HttpServletResponse response, AppUser user, Long appId) {
        // 应用下的用户
        List<AppToUser> appToUsers = appService.getAppToUserList(appId);
        if (CollectionUtils.isEmpty(appToUsers)) {
            return true;
        }
        for (AppToUser tempAppToUser : appToUsers) {
            if (user.getId().equals(tempAppToUser.getUserId())) {
                return true;
            }
        }
        logger.warn("user {} has no power on appId {}", user.getName(), appId);
        InterceptorResponse.forbidden(response, "无该应用的访问权限, appId=" + appId);
        return false;
    }


}