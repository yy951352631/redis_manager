package com.shcj.cache.web.controller.api;

import com.shcj.cache.web.controller.api.dto.AppOpsInstancePageDto;
import com.shcj.cache.web.controller.api.dto.AppOpsMachineItemDto;
import com.shcj.cache.web.controller.api.dto.AppConfigConsistencyDto;
import com.shcj.cache.web.controller.api.dto.AppPasswordDto;
import com.shcj.cache.web.controller.api.dto.AppScrollRestartRequestDto;
import com.shcj.cache.web.controller.api.dto.OpsActionResultDto;
import com.shcj.cache.web.controller.api.dto.TopologyExamDto;
import com.shcj.cache.web.service.AppOpsApiService;
import com.shcj.cache.web.service.AppScrollRestartApiService;
import com.shcj.cache.web.service.FaultDiagnosticService;
import com.shcj.cache.web.vo.ApiResponse;
import com.shcj.cache.web.vo.FaultDiagnosticReportVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/apps/{appId}/ops")
public class AppOpsApiController extends AbstractAdminApiController {

    @Autowired
    private AppOpsApiService appOpsApiService;

    @Autowired
    private AppScrollRestartApiService appScrollRestartApiService;

    @GetMapping("/instances")
    public ApiResponse<AppOpsInstancePageDto> instances(HttpServletRequest request, @PathVariable long appId) {
        ApiResponse<AppOpsInstancePageDto> denied = requireAdmin(request);
        if (denied != null) return denied;
        return ApiResponse.ok(appOpsApiService.getInstanceOps(appId));
    }

    @GetMapping("/machines")
    public ApiResponse<List<AppOpsMachineItemDto>> machines(HttpServletRequest request, @PathVariable long appId) {
        ApiResponse<List<AppOpsMachineItemDto>> denied = requireAdmin(request);
        if (denied != null) return denied;
        return ApiResponse.ok(appOpsApiService.getAppMachines(appId));
    }

    @GetMapping("/password")
    public ApiResponse<AppPasswordDto> password(HttpServletRequest request, @PathVariable long appId) {
        ApiResponse<AppPasswordDto> denied = requireAdmin(request);
        if (denied != null) return denied;
        return ApiResponse.ok(appOpsApiService.getPassword(appId));
    }

    @GetMapping("/config-consistency")
    public ApiResponse<AppConfigConsistencyDto> configConsistency(
            HttpServletRequest request, @PathVariable long appId) {
        ApiResponse<AppConfigConsistencyDto> denied = requireAdmin(request);
        if (denied != null) return denied;
        try {
            return ApiResponse.ok(appOpsApiService.checkConfigConsistency(appId));
        } catch (Exception e) {
            return ApiResponse.fail(400, e.getMessage());
        }
    }

    @PostMapping("/password")
    public ApiResponse<Map<String, Object>> updatePassword(
            HttpServletRequest request,
            @PathVariable long appId,
            @RequestBody Map<String, String> body) {
        ApiResponse<Map<String, Object>> denied = requireAdmin(request);
        if (denied != null) return denied;
        try {
            boolean ok = appOpsApiService.updatePassword(appId, body.get("password"));
            if (!ok) {
                return ApiResponse.fail(400, "password update failed or auth check failed");
            }
            return ApiResponse.ok(java.util.Collections.singletonMap("success", ok));
        } catch (Exception e) {
            return ApiResponse.fail(400, e.getMessage());
        }
    }

    @GetMapping("/password/check")
    public ApiResponse<Map<String, Object>> checkPassword(HttpServletRequest request, @PathVariable long appId) {
        ApiResponse<Map<String, Object>> denied = requireAdmin(request);
        if (denied != null) return denied;
        return ApiResponse.ok(java.util.Collections.singletonMap("success", appOpsApiService.checkPassword(appId)));
    }

    @GetMapping("/topology-exam")
    public ApiResponse<TopologyExamDto> topologyExam(
            HttpServletRequest request,
            @PathVariable long appId,
            @RequestParam(required = false, defaultValue = "false") boolean refresh) {
        ApiResponse<TopologyExamDto> denied = requireAdmin(request);
        if (denied != null) return denied;
        return ApiResponse.ok(appOpsApiService.runTopologyExam(appId, refresh));
    }

    @PostMapping("/fault-diagnostic/run")
    public ApiResponse<FaultDiagnosticReportVO> runFaultDiagnostic(
            HttpServletRequest request,
            @PathVariable long appId,
            @RequestParam(required = false, defaultValue = FaultDiagnosticService.SCENARIO_REPLICATION_BREAK) String scenario) {
        ApiResponse<FaultDiagnosticReportVO> denied = requireAdmin(request);
        if (denied != null) return denied;
        try {
            return ApiResponse.ok(appOpsApiService.runFaultDiagnostic(appId, scenario));
        } catch (Exception e) {
            return ApiResponse.fail(400, e.getMessage());
        }
    }

    @GetMapping("/fault-diagnostic/history")
    public ApiResponse<List<FaultDiagnosticReportVO>> faultHistory(
            HttpServletRequest request,
            @PathVariable long appId,
            @RequestParam(defaultValue = "10") int limit) {
        ApiResponse<List<FaultDiagnosticReportVO>> denied = requireAdmin(request);
        if (denied != null) return denied;
        return ApiResponse.ok(appOpsApiService.listFaultHistory(appId, limit));
    }

    @PostMapping("/cluster-del-node")
    public ApiResponse<OpsActionResultDto> clusterDelNode(
            HttpServletRequest request,
            @PathVariable long appId,
            @RequestParam int instanceId) {
        return runOps(request, () -> appOpsApiService.clusterDelNode(appId, instanceId));
    }

    @PostMapping("/cluster-slave-failover")
    public ApiResponse<OpsActionResultDto> clusterSlaveFailOver(
            HttpServletRequest request,
            @PathVariable long appId,
            @RequestParam int slaveInstanceId,
            @RequestParam(required = false) String failoverParam) {
        return runOps(request, () -> appOpsApiService.clusterSlaveFailOver(appId, slaveInstanceId, failoverParam));
    }

    @PostMapping("/add-slave")
    public ApiResponse<OpsActionResultDto> addSlave(
            HttpServletRequest request,
            @PathVariable long appId,
            @RequestParam int masterInstanceId,
            @RequestParam String slaveHost) {
        return runOps(request, () -> appOpsApiService.addSlave(appId, masterInstanceId, slaveHost));
    }

    @PostMapping("/sentinel-failover")
    public ApiResponse<OpsActionResultDto> sentinelFailOver(
            HttpServletRequest request,
            @PathVariable long appId,
            @RequestParam(defaultValue = "0") int slaveInstanceId,
            @RequestParam(required = false) String failoverParam) {
        return runOps(request, () -> appOpsApiService.sentinelFailOver(appId, slaveInstanceId, failoverParam));
    }

    @PostMapping("/scroll-restart")
    public ApiResponse<Map<String, String>> scrollRestart(
            HttpServletRequest request,
            @PathVariable long appId,
            @RequestBody AppScrollRestartRequestDto body) {
        ApiResponse<Map<String, String>> denied = requireAdmin(request);
        if (denied != null) return denied;
        try {
            String message = appScrollRestartApiService.scrollRestart(resolveApiUser(request), appId, body);
            return ApiResponse.ok(Collections.singletonMap("message", message));
        } catch (Exception e) {
            return ApiResponse.fail(400, e.getMessage());
        }
    }

    @PostMapping("/update-config-restart")
    public ApiResponse<Map<String, Object>> updateConfigRestart(
            HttpServletRequest request,
            @PathVariable long appId,
            @RequestBody AppScrollRestartRequestDto body) {
        ApiResponse<Map<String, Object>> denied = requireAdmin(request);
        if (denied != null) return denied;
        try {
            return ApiResponse.ok(appScrollRestartApiService.updateConfig(resolveApiUser(request), appId, body));
        } catch (Exception e) {
            return ApiResponse.fail(400, e.getMessage());
        }
    }

    @PostMapping("/instances/{instanceId}/start")
    public ApiResponse<OpsActionResultDto> startInstance(
            HttpServletRequest request,
            @PathVariable long appId,
            @PathVariable int instanceId) {
        return runOps(request, () -> appOpsApiService.startInstance(appId, instanceId));
    }

    @PostMapping("/instances/{instanceId}/shutdown")
    public ApiResponse<OpsActionResultDto> shutdownInstance(
            HttpServletRequest request,
            @PathVariable long appId,
            @PathVariable int instanceId) {
        return runOps(request, () -> appOpsApiService.shutdownInstance(appId, instanceId));
    }

    @PostMapping("/instances/{instanceId}/forget")
    public ApiResponse<OpsActionResultDto> forgetInstance(
            HttpServletRequest request,
            @PathVariable long appId,
            @PathVariable int instanceId) {
        return runOps(request, () -> appOpsApiService.forgetInstance(appId, instanceId));
    }

    private ApiResponse<OpsActionResultDto> runOps(HttpServletRequest request, OpsCallable callable) {
        ApiResponse<OpsActionResultDto> denied = requireAdmin(request);
        if (denied != null) return denied;
        try {
            return ApiResponse.ok(callable.call());
        } catch (Exception e) {
            return ApiResponse.fail(400, e.getMessage());
        }
    }

    @FunctionalInterface
    private interface OpsCallable {
        OpsActionResultDto call() throws Exception;
    }
}
