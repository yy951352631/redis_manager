package com.shcj.cache.web.controller.api;

import com.shcj.cache.web.controller.api.dto.*;
import com.shcj.cache.web.service.InstanceOpsApiService;
import com.shcj.cache.web.vo.ApiResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.util.List;

/**
 * 实例运维 API（M8）
 */
@RestController
@RequestMapping("/api/v1/instance-ops")
public class InstanceOpsApiController extends AbstractAdminApiController {

    @Autowired
    private InstanceOpsApiService instanceOpsApiService;

    @GetMapping("/options")
    public ApiResponse<InstanceOpsOptionsDto> options(HttpServletRequest request) {
        ApiResponse<InstanceOpsOptionsDto> denied = requireAdmin(request);
        if (denied != null) return denied;
        return ApiResponse.ok(instanceOpsApiService.getOptions());
    }

    @GetMapping("/config-checks")
    public ApiResponse<List<ConfigCheckRecordDto>> listConfigChecks(HttpServletRequest request) {
        ApiResponse<List<ConfigCheckRecordDto>> denied = requireAdmin(request);
        if (denied != null) return denied;
        return ApiResponse.ok(instanceOpsApiService.listConfigChecks());
    }

    @PostMapping("/config-checks")
    public ApiResponse<String> runConfigCheck(HttpServletRequest request,
                                              @RequestBody ConfigCheckRequestDto body) {
        ApiResponse<String> denied = requireAdmin(request);
        if (denied != null) return denied;
        try {
            return ApiResponse.ok(instanceOpsApiService.runConfigCheck(resolveApiUser(request), body));
        } catch (IllegalArgumentException ex) {
            return ApiResponse.fail(400, ex.getMessage());
        } catch (IllegalStateException ex) {
            return ApiResponse.fail(500, ex.getMessage());
        }
    }

    @GetMapping("/config-checks/{uuid}")
    public ApiResponse<ConfigCheckDetailDto> getConfigCheckDetail(HttpServletRequest request,
                                                                  @PathVariable("uuid") String uuid) {
        ApiResponse<ConfigCheckDetailDto> denied = requireAdmin(request);
        if (denied != null) return denied;
        return ApiResponse.ok(instanceOpsApiService.getConfigCheckDetail(uuid));
    }

    @GetMapping("/command-checks")
    public ApiResponse<List<CommandCheckRecordDto>> listCommandChecks(HttpServletRequest request) {
        ApiResponse<List<CommandCheckRecordDto>> denied = requireAdmin(request);
        if (denied != null) return denied;
        return ApiResponse.ok(instanceOpsApiService.listCommandChecks());
    }

    @PostMapping("/command-checks")
    public ApiResponse<String> runCommandCheck(HttpServletRequest request,
                                               @RequestBody CommandCheckRequestDto body) {
        ApiResponse<String> denied = requireAdmin(request);
        if (denied != null) return denied;
        try {
            return ApiResponse.ok(instanceOpsApiService.runCommandCheck(resolveApiUser(request), body));
        } catch (IllegalArgumentException ex) {
            return ApiResponse.fail(400, ex.getMessage());
        } catch (IllegalStateException ex) {
            return ApiResponse.fail(500, ex.getMessage());
        }
    }

    @GetMapping("/command-checks/{uuid}")
    public ApiResponse<CommandCheckDetailDto> getCommandCheckDetail(HttpServletRequest request,
                                                                    @PathVariable("uuid") String uuid) {
        ApiResponse<CommandCheckDetailDto> denied = requireAdmin(request);
        if (denied != null) return denied;
        return ApiResponse.ok(instanceOpsApiService.getCommandCheckDetail(uuid));
    }

    @GetMapping("/restart-records")
    public ApiResponse<RestartRecordPageDto> listRestartRecords(
            HttpServletRequest request,
            @RequestParam(value = "appId", required = false) Long appId,
            @RequestParam(value = "pageNo", required = false, defaultValue = "1") int pageNo,
            @RequestParam(value = "pageSize", required = false, defaultValue = "10") int pageSize) {
        ApiResponse<RestartRecordPageDto> denied = requireAdmin(request);
        if (denied != null) return denied;
        return ApiResponse.ok(instanceOpsApiService.listRestartRecords(appId, pageNo, pageSize));
    }

    @PostMapping("/restart-records/{appId}/stop")
    public ApiResponse<String> stopRestart(HttpServletRequest request, @PathVariable("appId") Long appId) {
        ApiResponse<String> denied = requireAdmin(request);
        if (denied != null) return denied;
        try {
            return ApiResponse.ok(instanceOpsApiService.stopRestart(appId));
        } catch (IllegalArgumentException ex) {
            return ApiResponse.fail(400, ex.getMessage());
        } catch (IllegalStateException ex) {
            return ApiResponse.fail(500, ex.getMessage());
        }
    }
}
