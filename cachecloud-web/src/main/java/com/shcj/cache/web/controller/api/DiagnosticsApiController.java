package com.shcj.cache.web.controller.api;

import com.shcj.cache.entity.AppUser;
import com.shcj.cache.web.controller.api.dto.*;
import com.shcj.cache.web.service.DiagnosticsApiService;
import com.shcj.cache.web.vo.ApiResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/diagnostics")
public class DiagnosticsApiController extends AbstractAdminApiController {

    @Autowired
    private DiagnosticsApiService diagnosticsApiService;

    @GetMapping("/apps")
    public ApiResponse<List<DiagnosticAppOptionDto>> listApps(HttpServletRequest request) {
        ApiResponse<List<DiagnosticAppOptionDto>> denied = requireAdmin(request);
        if (denied != null) return denied;
        return ApiResponse.ok(diagnosticsApiService.listOnlineApps());
    }

    @GetMapping("/apps/{appId}/instances")
    public ApiResponse<List<DiagnosticInstanceDto>> getInstances(
            HttpServletRequest request, @PathVariable long appId) {
        ApiResponse<List<DiagnosticInstanceDto>> denied = requireAdmin(request);
        if (denied != null) return denied;
        return ApiResponse.ok(diagnosticsApiService.getAppInstances(appId));
    }

    @GetMapping("/tasks")
    public ApiResponse<List<DiagnosticTaskDto>> listTasks(
            HttpServletRequest request,
            @RequestParam(required = false) Long appId,
            @RequestParam(required = false) String tabTag,
            @RequestParam(required = false) Long parentTaskId,
            @RequestParam(required = false) Long auditId,
            @RequestParam(required = false) Integer status) {
        ApiResponse<List<DiagnosticTaskDto>> denied = requireAdmin(request);
        if (denied != null) return denied;
        return ApiResponse.ok(diagnosticsApiService.listTasks(appId, tabTag, parentTaskId, auditId, status));
    }

    @PostMapping("/tasks")
    public ApiResponse<Map<String, Object>> submit(
            HttpServletRequest request, @RequestBody DiagnosticSubmitRequestDto body) {
        ApiResponse<Map<String, Object>> denied = requireAdmin(request);
        if (denied != null) return denied;
        try {
            AppUser user = resolveApiUser(request);
            long taskId = diagnosticsApiService.submit(user, body);
            return ApiResponse.ok(java.util.Collections.singletonMap("taskId", taskId));
        } catch (com.shcj.cache.exception.BizException ex) {
            return ApiResponse.fail(400, ex.getMessage());
        } catch (NumberFormatException ex) {
            return ApiResponse.fail(400, "参数格式错误，请检查数量、字节数等字段");
        }
    }

    @GetMapping("/tasks/data")
    public ApiResponse<DiagnosticResultDto> getTaskData(
            HttpServletRequest request,
            @RequestParam String redisKey,
            @RequestParam int type,
            @RequestParam(defaultValue = "false") boolean err) {
        ApiResponse<DiagnosticResultDto> denied = requireAdmin(request);
        if (denied != null) return denied;
        return ApiResponse.ok(diagnosticsApiService.getTaskData(redisKey, type, err));
    }

    @DeleteMapping("/tasks/{recordId}")
    public ApiResponse<Void> deleteTaskRecord(HttpServletRequest request, @PathVariable long recordId) {
        ApiResponse<Void> denied = requireAdmin(request);
        if (denied != null) return denied;
        try {
            diagnosticsApiService.deleteTaskRecord(recordId);
            return ApiResponse.ok();
        } catch (com.shcj.cache.exception.BizException ex) {
            return ApiResponse.fail(400, ex.getMessage());
        }
    }

    @PostMapping("/tasks/{recordId}/delete-keys")
    public ApiResponse<Map<String, Object>> confirmDelete(
            HttpServletRequest request, @PathVariable long recordId) {
        ApiResponse<Map<String, Object>> denied = requireAdmin(request);
        if (denied != null) return denied;
        try {
            long taskId = diagnosticsApiService.confirmDelete(recordId);
            return ApiResponse.ok(java.util.Collections.singletonMap("taskId", taskId));
        } catch (com.shcj.cache.exception.BizException ex) {
            return ApiResponse.fail(400, ex.getMessage());
        }
    }

    @PostMapping("/online-verify")
    public ApiResponse<OnlineHealthCheckResultDto> onlineVerify(
            HttpServletRequest request,
            @RequestBody Map<String, String> body) {
        ApiResponse<OnlineHealthCheckResultDto> denied = requireAdmin(request);
        if (denied != null) return denied;
        return ApiResponse.ok(diagnosticsApiService.runOnlineVerify(body.get("servers"), body.get("verifyType")));
    }

    @PostMapping("/command")
    public ApiResponse<Map<String, String>> executeCommand(
            HttpServletRequest request,
            @RequestBody Map<String, Object> body) {
        ApiResponse<Map<String, String>> denied = requireAdmin(request);
        if (denied != null) return denied;
        long appId = Long.parseLong(String.valueOf(body.get("appId")));
        String node = String.valueOf(body.get("node"));
        String command = String.valueOf(body.get("command"));
        Integer timeout = body.get("timeout") != null ? Integer.parseInt(String.valueOf(body.get("timeout"))) : null;
        String result = diagnosticsApiService.executeCommand(appId, node, command, timeout);
        Map<String, String> data = new java.util.HashMap<>();
        data.put("result", result);
        return ApiResponse.ok(data);
    }
}
