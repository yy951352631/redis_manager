package com.shcj.cache.web.controller.api;

import com.shcj.cache.constant.AppUserTypeEnum;
import com.shcj.cache.entity.AppUser;
import com.shcj.cache.web.controller.BaseController;
import com.shcj.cache.web.controller.api.dto.AppOfflineResultDto;
import com.shcj.cache.exception.BizException;
import com.shcj.cache.web.controller.api.dto.AppAddUsersRequestDto;
import com.shcj.cache.web.controller.api.dto.AppAlertConfigUpdateRequestDto;
import com.shcj.cache.web.controller.api.dto.AppClientItemDto;
import com.shcj.cache.web.controller.api.dto.AppDetailUpdateRequestDto;
import com.shcj.cache.web.controller.api.dto.AppDetailUserUpdateRequestDto;
import com.shcj.cache.web.controller.api.dto.AppDailyDto;
import com.shcj.cache.web.controller.api.dto.AppDetailDto;
import com.shcj.cache.web.controller.api.dto.AppDetailPanelDto;
import com.shcj.cache.web.controller.api.dto.AppMachineTopologyDto;
import com.shcj.cache.web.controller.api.dto.AppLatencyDto;
import com.shcj.cache.web.controller.api.dto.AppListPageDto;
import com.shcj.cache.web.controller.api.dto.AppStatOverviewDto;
import com.shcj.cache.web.controller.api.dto.AppTopologyDto;
import com.shcj.cache.web.controller.api.dto.AppCommandClimaxDto;
import com.shcj.cache.web.controller.api.dto.ChartPointDto;
import com.shcj.cache.web.controller.api.dto.KeyAnalysisPageDto;
import com.shcj.cache.web.controller.api.dto.KeyAnalysisProgressDto;
import com.shcj.cache.web.controller.api.dto.KeyAnalysisResultDto;
import com.shcj.cache.web.controller.api.dto.KeyAnalysisStartResultDto;
import com.shcj.cache.web.service.AppDetailTabApiService;
import com.shcj.cache.web.service.AppManageApiService;
import com.shcj.cache.web.service.KeyAnalysisApiService;
import com.shcj.cache.web.vo.ApiResponse;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang.math.NumberUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;

/**
 * 集群管理 API（M3）
 */
@RestController
@RequestMapping("/api/v1/apps")
public class AppApiController extends BaseController {

    @Autowired
    private AppManageApiService appManageApiService;

    @Autowired
    private AppDetailTabApiService appDetailTabApiService;

    @Autowired
    private KeyAnalysisApiService keyAnalysisApiService;

    @GetMapping
    public ApiResponse<AppListPageDto> listApps(
            HttpServletRequest request,
            @RequestParam(value = "appStatus", required = false) Integer appStatus,
            @RequestParam(value = "appParam", required = false) String appParam,
            @RequestParam(value = "ip", required = false) String ip,
            @RequestParam(value = "pageNo", required = false, defaultValue = "1") int pageNo,
            @RequestParam(value = "pageSize", required = false, defaultValue = "20") int pageSize) {
        AppUser user = resolveApiUser(request);
        if (user == null) {
            return ApiResponse.fail(401, "未登录或登录已过期");
        }
        if (!AppUserTypeEnum.ADMIN_USER.value().equals(user.getType())) {
            return ApiResponse.fail(403, "需要管理员权限");
        }
        AppListPageDto data = appManageApiService.listApps(user, ip, appParam, appStatus, pageNo, pageSize);
        return ApiResponse.ok(data);
    }

    @GetMapping("/{appId}")
    public ApiResponse<AppDetailDto> getAppDetail(HttpServletRequest request, @PathVariable("appId") long appId) {
        AppUser user = resolveApiUser(request);
        if (user == null) {
            return ApiResponse.fail(401, "未登录或登录已过期");
        }
        if (!AppUserTypeEnum.ADMIN_USER.value().equals(user.getType())) {
            return ApiResponse.fail(403, "需要管理员权限");
        }
        AppDetailDto data = appManageApiService.getAppDetail(appId);
        if (data == null) {
            return ApiResponse.fail(404, "集群不存在");
        }
        return ApiResponse.ok(data);
    }

    @PostMapping("/{appId}/recover")
    public ApiResponse<AppOfflineResultDto> recoverApp(HttpServletRequest request, @PathVariable long appId) {
        AppUser user = resolveApiUser(request);
        if (user == null) {
            return ApiResponse.fail(401, "not logged in");
        }
        if (!AppUserTypeEnum.ADMIN_USER.value().equals(user.getType())) {
            return ApiResponse.fail(403, "admin permission required");
        }
        try {
            return ApiResponse.ok(appManageApiService.recoverApp(appId, user));
        } catch (Exception e) {
            return ApiResponse.fail(400, e.getMessage());
        }
    }

    @PostMapping("/{appId}/offline")
    public ApiResponse<AppOfflineResultDto> offlineApp(
            HttpServletRequest request,
            @PathVariable long appId,
            @RequestParam(required = false) Long appAuditId) {
        AppUser user = resolveApiUser(request);
        if (user == null) {
            return ApiResponse.fail(401, "未登录或登录已过期");
        }
        if (!AppUserTypeEnum.ADMIN_USER.value().equals(user.getType())) {
            return ApiResponse.fail(403, "需要管理员权限");
        }
        try {
            return ApiResponse.ok(appManageApiService.offlineApp(appId, user, appAuditId));
        } catch (Exception e) {
            return ApiResponse.fail(400, e.getMessage());
        }
    }

    @GetMapping("/{appId}/detail-panel")
    public ApiResponse<AppDetailPanelDto> getDetailPanel(HttpServletRequest request, @PathVariable long appId) {
        AppUser user = resolveApiUser(request);
        if (user == null) return ApiResponse.fail(401, "未登录或登录已过期");
        return ApiResponse.ok(appDetailTabApiService.getDetailPanel(appId, user));
    }

    @PutMapping("/{appId}/detail-panel/app-info")
    public ApiResponse<Void> updateAppDetail(
            HttpServletRequest request,
            @PathVariable long appId,
            @RequestBody AppDetailUpdateRequestDto body) {
        AppUser user = resolveApiUser(request);
        if (user == null) return ApiResponse.fail(401, "未登录或登录已过期");
        try {
            appDetailTabApiService.updateAppDetail(appId, body, user);
            return ApiResponse.ok();
        } catch (BizException ex) {
            return ApiResponse.fail(400, ex.getMessage());
        }
    }

    @PutMapping("/{appId}/detail-panel/alert-config")
    public ApiResponse<Void> changeAppAlertConfig(
            HttpServletRequest request,
            @PathVariable long appId,
            @RequestBody AppAlertConfigUpdateRequestDto body) {
        AppUser user = resolveApiUser(request);
        if (user == null) return ApiResponse.fail(401, "未登录或登录已过期");
        try {
            appDetailTabApiService.changeAppAlertConfig(appId, body, user);
            return ApiResponse.ok();
        } catch (BizException ex) {
            return ApiResponse.fail(400, ex.getMessage());
        }
    }

    @PostMapping("/{appId}/detail-panel/users")
    public ApiResponse<Void> addAppUsers(
            HttpServletRequest request,
            @PathVariable long appId,
            @RequestBody AppAddUsersRequestDto body) {
        AppUser user = resolveApiUser(request);
        if (user == null) return ApiResponse.fail(401, "未登录或登录已过期");
        try {
            appDetailTabApiService.addAppUsers(appId, body, user);
            return ApiResponse.ok();
        } catch (BizException ex) {
            return ApiResponse.fail(400, ex.getMessage());
        }
    }

    @DeleteMapping("/{appId}/detail-panel/users/{userId}")
    public ApiResponse<Void> deleteAppUser(
            HttpServletRequest request,
            @PathVariable long appId,
            @PathVariable long userId) {
        AppUser user = resolveApiUser(request);
        if (user == null) return ApiResponse.fail(401, "未登录或登录已过期");
        try {
            appDetailTabApiService.deleteAppUser(appId, userId, user);
            return ApiResponse.ok();
        } catch (BizException ex) {
            return ApiResponse.fail(400, ex.getMessage());
        }
    }

    @PutMapping("/{appId}/detail-panel/users/{userId}")
    public ApiResponse<Void> updateAppUser(
            HttpServletRequest request,
            @PathVariable long appId,
            @PathVariable long userId,
            @RequestBody AppDetailUserUpdateRequestDto body) {
        AppUser user = resolveApiUser(request);
        if (user == null) return ApiResponse.fail(401, "未登录或登录已过期");
        try {
            appDetailTabApiService.updateAppUser(appId, userId, body, user);
            return ApiResponse.ok();
        } catch (BizException ex) {
            return ApiResponse.fail(400, ex.getMessage());
        }
    }

    @GetMapping("/{appId}/topology")
    public ApiResponse<AppTopologyDto> getTopology(
            HttpServletRequest request,
            @PathVariable long appId,
            @RequestParam(defaultValue = "false") boolean live) {
        AppUser user = resolveApiUser(request);
        if (user == null) return ApiResponse.fail(401, "未登录或登录已过期");
        if (!AppUserTypeEnum.ADMIN_USER.value().equals(user.getType())) {
            return ApiResponse.fail(403, "需要管理员权限");
        }
        return ApiResponse.ok(appDetailTabApiService.getTopology(appId, live));
    }

    @GetMapping("/{appId}/clients")
    public ApiResponse<java.util.List<AppClientItemDto>> getClients(
            HttpServletRequest request,
            @PathVariable long appId,
            @RequestParam(defaultValue = "3") int condition,
            @RequestParam(defaultValue = "false") boolean includeDetails) {
        AppUser user = resolveApiUser(request);
        if (user == null) return ApiResponse.fail(401, "未登录或登录已过期");
        if (!AppUserTypeEnum.ADMIN_USER.value().equals(user.getType())) {
            return ApiResponse.fail(403, "需要管理员权限");
        }
        return ApiResponse.ok(appDetailTabApiService.getClientList(appId, condition, includeDetails));
    }

    @GetMapping("/{appId}/stat-overview")
    public ApiResponse<AppStatOverviewDto> getStatOverview(
            HttpServletRequest request,
            @PathVariable long appId,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            @RequestParam(defaultValue = "true") boolean includeClimax) throws Exception {
        if (resolveApiUser(request) == null) return ApiResponse.fail(401, "未登录或登录已过期");
        return ApiResponse.ok(appDetailTabApiService.getStatOverview(appId, startDate, endDate, includeClimax));
    }

    @GetMapping("/{appId}/stat-climax")
    public ApiResponse<List<AppCommandClimaxDto>> getStatClimax(
            HttpServletRequest request,
            @PathVariable long appId,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate) throws Exception {
        if (resolveApiUser(request) == null) return ApiResponse.fail(401, "未登录或登录已过期");
        return ApiResponse.ok(appDetailTabApiService.getStatClimax(appId, startDate, endDate));
    }

    @GetMapping("/{appId}/charts/app-stats")
    public ApiResponse<List<ChartPointDto>> getAppStatChart(
            HttpServletRequest request,
            @PathVariable long appId,
            @RequestParam(defaultValue = "hitPercent") String statName,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate) throws Exception {
        if (resolveApiUser(request) == null) return ApiResponse.fail(401, "未登录或登录已过期");
        return ApiResponse.ok(appDetailTabApiService.getAppStatChart(appId, statName, startDate, endDate));
    }

    @GetMapping("/{appId}/charts/app-stats/batch")
    public ApiResponse<Map<String, List<ChartPointDto>>> getAppStatChartsBatch(
            HttpServletRequest request,
            @PathVariable long appId,
            @RequestParam String statName,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate) throws Exception {
        if (resolveApiUser(request) == null) return ApiResponse.fail(401, "未登录或登录已过期");
        return ApiResponse.ok(appDetailTabApiService.getAppStatChartsBatch(appId, statName, startDate, endDate));
    }

    @GetMapping("/{appId}/charts/app-ops-stats")
    public ApiResponse<Map<String, List<ChartPointDto>>> getAppOpsStatCharts(
            HttpServletRequest request,
            @PathVariable long appId,
            @RequestParam String statName,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate) throws Exception {
        if (resolveApiUser(request) == null) return ApiResponse.fail(401, "未登录或登录已过期");
        return ApiResponse.ok(appDetailTabApiService.getAppOpsStatCharts(appId, statName, startDate, endDate));
    }

    @GetMapping("/{appId}/machine-topology")
    public ApiResponse<AppMachineTopologyDto> getMachineTopology(
            HttpServletRequest request, @PathVariable long appId) {
        if (resolveApiUser(request) == null) return ApiResponse.fail(401, "未登录或登录已过期");
        return ApiResponse.ok(appDetailTabApiService.getMachineTopology(appId));
    }

    @GetMapping("/{appId}/charts/command-names")
    public ApiResponse<List<String>> getCommandNames(
            HttpServletRequest request,
            @PathVariable long appId,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate) throws Exception {
        if (resolveApiUser(request) == null) return ApiResponse.fail(401, "未登录或登录已过期");
        return ApiResponse.ok(appDetailTabApiService.getCommandNameList(appId, startDate, endDate));
    }

    @GetMapping("/{appId}/charts/commands/batch")
    public ApiResponse<Map<String, List<ChartPointDto>>> getCommandChartsBatch(
            HttpServletRequest request,
            @PathVariable long appId,
            @RequestParam String commands,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate) throws Exception {
        if (resolveApiUser(request) == null) return ApiResponse.fail(401, "未登录或登录已过期");
        return ApiResponse.ok(appDetailTabApiService.getCommandChartsBatch(appId, commands, startDate, endDate));
    }

    @GetMapping("/{appId}/charts/commands")
    public ApiResponse<List<ChartPointDto>> getCommandChart(
            HttpServletRequest request,
            @PathVariable long appId,
            @RequestParam(required = false) String commandName,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate) throws Exception {
        if (resolveApiUser(request) == null) return ApiResponse.fail(401, "未登录或登录已过期");
        return ApiResponse.ok(appDetailTabApiService.getCommandChart(appId, commandName, startDate, endDate));
    }

    @GetMapping("/{appId}/charts/top5-commands")
    public ApiResponse<List<ChartPointDto>> getTop5Commands(
            HttpServletRequest request,
            @PathVariable long appId,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate) throws Exception {
        if (resolveApiUser(request) == null) return ApiResponse.fail(401, "未登录或登录已过期");
        return ApiResponse.ok(appDetailTabApiService.getTop5Commands(appId, startDate, endDate));
    }

    @GetMapping("/{appId}/daily")
    public ApiResponse<AppDailyDto> getDaily(
            HttpServletRequest request,
            @PathVariable long appId,
            @RequestParam(required = false) String dailyDate) throws Exception {
        if (resolveApiUser(request) == null) return ApiResponse.fail(401, "未登录或登录已过期");
        return ApiResponse.ok(appDetailTabApiService.getDaily(appId, dailyDate));
    }

    @PostMapping("/{appId}/commands/execute")
    public ApiResponse<Map<String, String>> executeCommand(
            HttpServletRequest request,
            @PathVariable long appId,
            @RequestBody Map<String, Object> body) {
        AppUser user = resolveApiUser(request);
        if (user == null) return ApiResponse.fail(401, "未登录或登录已过期");
        String result = appDetailTabApiService.executeAppCommand(
                appId,
                String.valueOf(body.get("command")),
                user);
        return ApiResponse.ok(java.util.Collections.singletonMap("result", result));
    }

    @GetMapping("/{appId}/latency")
    public ApiResponse<AppLatencyDto> getLatency(
            HttpServletRequest request,
            @PathVariable long appId,
            @RequestParam(required = false) String searchDate,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            @RequestParam(required = false, defaultValue = "0") long minCostMillis) throws Exception {
        if (resolveApiUser(request) == null) return ApiResponse.fail(401, "未登录或登录已过期");
        return ApiResponse.ok(appDetailTabApiService.getLatency(appId, searchDate, startDate, endDate, minCostMillis));
    }

    @GetMapping("/{appId}/slow-logs/cleanable")
    public ApiResponse<Map<String, Object>> countCleanableSlowLogs(
            HttpServletRequest request,
            @PathVariable long appId,
            @RequestParam(defaultValue = "7") int keepDays) {
        if (resolveApiUser(request) == null) return ApiResponse.fail(401, "未登录或登录已过期");
        try {
            int count = appDetailTabApiService.countCleanableSlowLogs(appId, keepDays);
            Map<String, Object> data = new java.util.HashMap<>();
            data.put("count", count);
            return ApiResponse.ok(data);
        } catch (Exception e) {
            return ApiResponse.fail(400, e.getMessage());
        }
    }

    @DeleteMapping("/{appId}/slow-logs")
    public ApiResponse<Map<String, Object>> cleanSlowLogs(
            HttpServletRequest request,
            @PathVariable long appId,
            @RequestParam(defaultValue = "7") int keepDays) {
        AppUser user = resolveApiUser(request);
        if (user == null) return ApiResponse.fail(401, "未登录或登录已过期");
        try {
            int deleted = appDetailTabApiService.cleanSlowLogs(appId, keepDays, user);
            Map<String, Object> data = new java.util.HashMap<>();
            data.put("deleted", deleted);
            return ApiResponse.ok(data);
        } catch (Exception e) {
            return ApiResponse.fail(400, e.getMessage());
        }
    }

    @DeleteMapping("/{appId}")
    public ApiResponse<Void> permanentlyDeleteOfflineApp(HttpServletRequest request, @PathVariable long appId) {
        AppUser user = resolveApiUser(request);
        if (user == null) return ApiResponse.fail(401, "未登录或登录已过期");
        if (!AppUserTypeEnum.ADMIN_USER.value().equals(user.getType())) {
            return ApiResponse.fail(403, "需要管理员权限");
        }
        try {
            appManageApiService.permanentlyDeleteOfflineApp(appId);
            return ApiResponse.ok();
        } catch (Exception e) {
            return ApiResponse.fail(400, e.getMessage());
        }
    }

    @GetMapping("/{appId}/key-analysis")
    public ApiResponse<KeyAnalysisPageDto> getKeyAnalysisPage(
            HttpServletRequest request,
            @PathVariable long appId) {
        ApiResponse<KeyAnalysisPageDto> denied = requireAdminKeyAnalysis(request);
        if (denied != null) return denied;
        return ApiResponse.ok(keyAnalysisApiService.getPage(appId));
    }

    @PostMapping("/{appId}/key-analysis/start")
    public ApiResponse<KeyAnalysisStartResultDto> startKeyAnalysis(
            HttpServletRequest request,
            @PathVariable long appId,
            @RequestBody Map<String, Object> body) {
        ApiResponse<KeyAnalysisStartResultDto> denied = requireAdminKeyAnalysis(request);
        if (denied != null) return denied;
        AppUser user = resolveApiUser(request);
        try {
            String reason = body.get("reason") != null ? String.valueOf(body.get("reason")) : null;
            String nodeInfo = body.get("nodeInfo") != null ? String.valueOf(body.get("nodeInfo")) : null;
            long bigKeyStringBytes = NumberUtils.toLong(String.valueOf(body.get("bigKeyStringBytes")));
            long bigKeyCollectionElements = NumberUtils.toLong(String.valueOf(body.get("bigKeyCollectionElements")));
            return ApiResponse.ok(keyAnalysisApiService.start(appId, user, reason, nodeInfo,
                    bigKeyStringBytes, bigKeyCollectionElements));
        } catch (Exception e) {
            return ApiResponse.fail(400, e.getMessage());
        }
    }

    @GetMapping("/{appId}/key-analysis/progress")
    public ApiResponse<KeyAnalysisProgressDto> getKeyAnalysisProgress(
            HttpServletRequest request,
            @PathVariable long appId,
            @RequestParam long taskId) {
        ApiResponse<KeyAnalysisProgressDto> denied = requireAdminKeyAnalysis(request);
        if (denied != null) return denied;
        try {
            return ApiResponse.ok(keyAnalysisApiService.getProgress(taskId));
        } catch (Exception e) {
            return ApiResponse.fail(400, e.getMessage());
        }
    }

    @GetMapping("/{appId}/key-analysis/{auditId}/result")
    public ApiResponse<KeyAnalysisResultDto> getKeyAnalysisResult(
            HttpServletRequest request,
            @PathVariable long appId,
            @PathVariable long auditId) {
        ApiResponse<KeyAnalysisResultDto> denied = requireAdminKeyAnalysis(request);
        if (denied != null) return denied;
        return ApiResponse.ok(keyAnalysisApiService.getResult(appId, auditId));
    }

    @DeleteMapping("/{appId}/key-analysis/{auditId}")
    public ApiResponse<Void> deleteKeyAnalysisRecord(HttpServletRequest request, @PathVariable long appId,
            @PathVariable long auditId) {
        ApiResponse<Void> denied = requireAdminKeyAnalysis(request);
        if (denied != null) return denied;
        keyAnalysisApiService.deleteRecord(appId, auditId);
        return ApiResponse.ok(null);
    }

    private <T> ApiResponse<T> requireAdminKeyAnalysis(HttpServletRequest request) {
        AppUser user = resolveApiUser(request);
        if (user == null) {
            return ApiResponse.fail(401, "未登录或登录已过期");
        }
        if (!AppUserTypeEnum.ADMIN_USER.value().equals(user.getType())) {
            return ApiResponse.fail(403, "需要管理员权限");
        }
        return null;
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
