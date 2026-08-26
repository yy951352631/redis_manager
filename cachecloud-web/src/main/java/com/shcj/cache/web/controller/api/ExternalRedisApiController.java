package com.shcj.cache.web.controller.api;

import com.shcj.cache.web.controller.api.dto.ExternalNodeListPageDto;
import com.shcj.cache.web.controller.api.dto.ExternalRedisCreateFormDto;
import com.shcj.cache.web.service.ExternalRedisApiService;
import com.shcj.cache.web.vo.ApiResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/external-redis")
public class ExternalRedisApiController extends AbstractAdminApiController {

    @Autowired
    private ExternalRedisApiService externalRedisApiService;

    @GetMapping
    public ApiResponse<ExternalNodeListPageDto> list(
            HttpServletRequest request,
            @RequestParam(value = "ip", required = false) String ip,
            @RequestParam(value = "status", required = false) Integer status,
            @RequestParam(value = "includeSentinel", required = false, defaultValue = "false") boolean includeSentinel,
            @RequestParam(value = "pageNo", required = false, defaultValue = "1") int pageNo,
            @RequestParam(value = "pageSize", required = false, defaultValue = "20") int pageSize) {
        ApiResponse<ExternalNodeListPageDto> denied = requireAdmin(request);
        if (denied != null) return denied;
        return ApiResponse.ok(externalRedisApiService.listNodes(ip, status, includeSentinel, pageNo, pageSize));
    }

    @GetMapping("/create-form")
    public ApiResponse<ExternalRedisCreateFormDto> createForm(HttpServletRequest request) {
        ApiResponse<ExternalRedisCreateFormDto> denied = requireAdmin(request);
        if (denied != null) return denied;
        return ApiResponse.ok(externalRedisApiService.getCreateForm(resolveApiUser(request)));
    }

    @PostMapping("/check")
    public ApiResponse<Map<String, String>> check(
            HttpServletRequest request,
            @RequestBody Map<String, Object> body) {
        ApiResponse<Map<String, String>> denied = requireAdmin(request);
        if (denied != null) return denied;
        try {
            Map<String, String> result = externalRedisApiService.check(
                    intVal(body, "appType", intVal(body, "type", 0)),
                    str(body, "appInstanceInfo"),
                    str(body, "password"),
                    str(body, "sentinelPassword"));
            return ApiResponse.ok(result);
        } catch (Exception e) {
            return ApiResponse.fail(400, e.getMessage());
        }
    }

    @GetMapping("/check-name")
    public ApiResponse<Void> checkName(
            HttpServletRequest request,
            @RequestParam String name) {
        ApiResponse<Void> denied = requireAdmin(request);
        if (denied != null) return denied;
        try {
            externalRedisApiService.checkName(name);
            return ApiResponse.ok();
        } catch (Exception e) {
            return ApiResponse.fail(400, e.getMessage());
        }
    }

    @PostMapping
    public ApiResponse<Void> save(
            HttpServletRequest request,
            @RequestBody Map<String, Object> body) {
        ApiResponse<Void> denied = requireAdmin(request);
        if (denied != null) return denied;
        try {
            externalRedisApiService.save(resolveApiUser(request), body);
            return ApiResponse.ok();
        } catch (Exception e) {
            return ApiResponse.fail(400, e.getMessage());
        }
    }

    @PostMapping("/{appId}/instances")
    public ApiResponse<Map<String, String>> addInstances(
            HttpServletRequest request,
            @PathVariable long appId,
            @RequestBody Map<String, Object> body) {
        ApiResponse<Map<String, String>> denied = requireAdmin(request);
        if (denied != null) return denied;
        try {
            String appInstanceInfo = body == null ? null : String.valueOf(body.get("appInstanceInfo"));
            String message = externalRedisApiService.addInstances(appId, appInstanceInfo);
            return ApiResponse.ok(java.util.Collections.singletonMap("message", message));
        } catch (Exception e) {
            return ApiResponse.fail(400, e.getMessage());
        }
    }

    @DeleteMapping("/{appId}/instances/{instanceId}")
    public ApiResponse<Map<String, String>> removeInstance(
            HttpServletRequest request,
            @PathVariable long appId,
            @PathVariable int instanceId) {
        ApiResponse<Map<String, String>> denied = requireAdmin(request);
        if (denied != null) return denied;
        try {
            String message = externalRedisApiService.removeInstance(appId, instanceId);
            return ApiResponse.ok(java.util.Collections.singletonMap("message", message));
        } catch (Exception e) {
            return ApiResponse.fail(400, e.getMessage());
        }
    }

    @PostMapping("/{appId}/repair-instances")
    public ApiResponse<Map<String, String>> repairInstances(
            HttpServletRequest request,
            @PathVariable long appId) {
        ApiResponse<Map<String, String>> denied = requireAdmin(request);
        if (denied != null) return denied;
        try {
            String message = externalRedisApiService.repairInstances(appId);
            return ApiResponse.ok(java.util.Collections.singletonMap("message", message));
        } catch (Exception e) {
            return ApiResponse.fail(400, e.getMessage());
        }
    }

    private String str(Map<String, Object> body, String key) {
        Object val = body.get(key);
        return val == null ? null : String.valueOf(val);
    }

    private int intVal(Map<String, Object> body, String key, int defaultValue) {
        Object val = body.get(key);
        if (val == null) return defaultValue;
        return org.apache.commons.lang.math.NumberUtils.toInt(String.valueOf(val), defaultValue);
    }
}
