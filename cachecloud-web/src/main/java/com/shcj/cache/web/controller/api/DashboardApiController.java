package com.shcj.cache.web.controller.api;

import com.shcj.cache.constant.AppUserTypeEnum;
import com.shcj.cache.entity.AppUser;
import com.shcj.cache.web.controller.api.dto.DashboardDetailsDto;
import com.shcj.cache.web.controller.api.dto.DashboardOpsDto;
import com.shcj.cache.web.controller.api.dto.DashboardDto;
import com.shcj.cache.web.controller.api.dto.DashboardOverviewBundleDto;
import com.shcj.cache.web.service.DashboardService;
import com.shcj.cache.web.vo.ApiResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;

/**
 * 全局统计大盘 API（M1）
 */
@RestController
@RequestMapping("/api/v1/dashboard")
public class DashboardApiController extends AbstractAdminApiController {

    @Autowired
    private DashboardService dashboardService;

    @Autowired
    private com.shcj.cache.web.service.DashboardOpsService dashboardOpsService;

    private static final org.slf4j.Logger logger =
            org.slf4j.LoggerFactory.getLogger(DashboardApiController.class);

    @GetMapping("/overview")
    public ApiResponse<DashboardOverviewBundleDto> overview(HttpServletRequest request) {
        AppUser user = resolveApiUser(request);
        if (user == null) {
            return ApiResponse.fail(401, "未登录或登录已过期");
        }
        if (!AppUserTypeEnum.ADMIN_USER.value().equals(user.getType())) {
            return ApiResponse.fail(403, "需要管理员权限");
        }
        return ApiResponse.ok(dashboardService.buildOverviewBundle());
    }

    /** 运维主面板：态势 + 归并告警 + 哨兵计数 + Top-N 榜，一次返回 */
    @GetMapping("/ops")
    public ApiResponse<DashboardOpsDto> ops(HttpServletRequest request) {
        if (resolveApiUser(request) == null) {
            return ApiResponse.fail(401, "未登录或登录已过期");
        }
        try {
            return ApiResponse.ok(dashboardOpsService.build());
        } catch (Exception e) {
            logger.error("build dashboard ops failed: {}", e.getMessage(), e);
            return ApiResponse.fail(500, "主面板数据加载失败：" + e.getMessage());
        }
    }

    @GetMapping("/details")
    public ApiResponse<DashboardDetailsDto> details(HttpServletRequest request) {
        AppUser user = resolveApiUser(request);
        if (user == null) {
            return ApiResponse.fail(401, "未登录或登录已过期");
        }
        if (!AppUserTypeEnum.ADMIN_USER.value().equals(user.getType())) {
            return ApiResponse.fail(403, "需要管理员权限");
        }
        return ApiResponse.ok(dashboardService.buildDetailsBundle());
    }

    @GetMapping
    public ApiResponse<DashboardDto> dashboard(HttpServletRequest request) {
        AppUser user = resolveApiUser(request);
        if (user == null) {
            return ApiResponse.fail(401, "未登录或登录已过期");
        }
        if (!AppUserTypeEnum.ADMIN_USER.value().equals(user.getType())) {
            return ApiResponse.fail(403, "需要管理员权限");
        }
        return ApiResponse.ok(dashboardService.buildDashboard());
    }

}
