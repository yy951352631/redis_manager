package com.shcj.cache.web.controller.api;

import com.shcj.cache.constant.AppUserTypeEnum;
import com.shcj.cache.entity.AppUser;
import com.shcj.cache.web.controller.BaseController;
import com.shcj.cache.web.controller.api.dto.ServerStatAppsDto;
import com.shcj.cache.web.service.AppStatApiService;
import com.shcj.cache.web.vo.ApiResponse;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;

/**
 * server 统计 API（M2）
 */
@RestController
@RequestMapping("/api/v1/stats/server")
public class AppStatApiController extends BaseController {

    @Autowired
    private AppStatApiService appStatApiService;

    @GetMapping("/apps")
    public ApiResponse<ServerStatAppsDto> listServerApps(
            HttpServletRequest request,
            @RequestParam(value = "searchDate", required = false) String searchDate,
            @RequestParam(value = "appId", required = false) Long appId) {
        AppUser user = resolveApiUser(request);
        if (user == null) {
            return ApiResponse.fail(401, "未登录或登录已过期");
        }
        if (!AppUserTypeEnum.ADMIN_USER.value().equals(user.getType())) {
            return ApiResponse.fail(403, "需要管理员权限");
        }
        return ApiResponse.ok(appStatApiService.listServerStats(appId, searchDate));
    }

    @PostMapping("/send-daily-email")
    public ApiResponse<Void> sendDailyEmail(
            HttpServletRequest request,
            @RequestParam(value = "searchDate", required = false) String searchDate) {
        AppUser user = resolveApiUser(request);
        if (user == null) {
            return ApiResponse.fail(401, "未登录或登录已过期");
        }
        if (!AppUserTypeEnum.ADMIN_USER.value().equals(user.getType())) {
            return ApiResponse.fail(403, "需要管理员权限");
        }
        appStatApiService.sendDailyEmail(searchDate);
        return ApiResponse.ok();
    }

    @PostMapping("/topology-update")
    public ApiResponse<Void> updateTopology(HttpServletRequest request) {
        AppUser user = resolveApiUser(request);
        if (user == null) {
            return ApiResponse.fail(401, "未登录或登录已过期");
        }
        if (!AppUserTypeEnum.ADMIN_USER.value().equals(user.getType())) {
            return ApiResponse.fail(403, "需要管理员权限");
        }
        try {
            appStatApiService.updateTopologyExam();
            return ApiResponse.ok();
        } catch (Exception ex) {
            return ApiResponse.fail(500, "拓扑检测更新失败");
        }
    }

    private AppUser resolveApiUser(HttpServletRequest request) {
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
}
