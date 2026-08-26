package com.shcj.cache.web.controller.api;

import com.shcj.cache.web.controller.api.dto.*;
import com.shcj.cache.web.service.InstanceApiService;
import com.shcj.cache.web.vo.ApiResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.text.ParseException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/instances")
public class InstanceApiController extends AbstractAdminApiController {

    @Autowired
    private InstanceApiService instanceApiService;

    @GetMapping("/{instanceId}")
    public ApiResponse<InstanceDetailDto> detail(
            HttpServletRequest request,
            @PathVariable long instanceId,
            @RequestParam(required = false) Long appId,
            @RequestParam(required = false, defaultValue = "false") boolean fromNode) {
        ApiResponse<InstanceDetailDto> denied = requireAdmin(request);
        if (denied != null) return denied;
        InstanceDetailDto data = instanceApiService.getDetail(instanceId, appId, fromNode);
        if (data == null) {
            return ApiResponse.fail(404, "节点不存在");
        }
        return ApiResponse.ok(data);
    }

    @GetMapping("/{instanceId}/stat")
    public ApiResponse<InstanceStatDto> stat(
            HttpServletRequest request,
            @PathVariable long instanceId,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate) {
        ApiResponse<InstanceStatDto> denied = requireAdmin(request);
        if (denied != null) return denied;
        InstanceStatDto data = instanceApiService.getStat(instanceId, startDate, endDate);
        if (data == null) {
            return ApiResponse.fail(404, "节点不存在");
        }
        return ApiResponse.ok(data);
    }

    @GetMapping("/{instanceId}/log")
    public ApiResponse<InstanceLogDto> log(
            HttpServletRequest request,
            @PathVariable long instanceId,
            @RequestParam(defaultValue = "100") int pageSize) {
        ApiResponse<InstanceLogDto> denied = requireAdmin(request);
        if (denied != null) return denied;
        return ApiResponse.ok(instanceApiService.getLog(instanceId, pageSize));
    }

    @GetMapping("/{instanceId}/slow-logs")
    public ApiResponse<InstanceSlowLogPageDto> slowLogs(
            HttpServletRequest request,
            @PathVariable long instanceId,
            @RequestParam(defaultValue = "1") int pageNo,
            @RequestParam(defaultValue = "20") int pageSize) {
        ApiResponse<InstanceSlowLogPageDto> denied = requireAdmin(request);
        if (denied != null) return denied;
        return ApiResponse.ok(instanceApiService.getSlowLogs(instanceId, pageNo, pageSize));
    }

    @GetMapping("/{instanceId}/config")
    public ApiResponse<Map<String, String>> config(
            HttpServletRequest request,
            @PathVariable long instanceId) {
        ApiResponse<Map<String, String>> denied = requireAdmin(request);
        if (denied != null) return denied;
        return ApiResponse.ok(instanceApiService.getConfig(instanceId));
    }

    @PutMapping("/{instanceId}/config")
    public ApiResponse<InstanceConfigUpdateResultDto> updateConfig(
            HttpServletRequest request,
            @PathVariable long instanceId,
            @RequestBody InstanceConfigUpdateRequestDto body) {
        ApiResponse<InstanceConfigUpdateResultDto> denied = requireAdmin(request);
        if (denied != null) return denied;
        try {
            return ApiResponse.ok(instanceApiService.updateConfig(instanceId, body));
        } catch (Exception e) {
            return ApiResponse.fail(400, e.getMessage());
        }
    }

    @GetMapping("/{instanceId}/clients")
    public ApiResponse<InstanceClientsPageDto> clients(
            HttpServletRequest request,
            @PathVariable long instanceId,
            @RequestParam(defaultValue = "3") int condition,
            @RequestParam(defaultValue = "false") boolean includeRaw,
            @RequestParam(defaultValue = "false") boolean includeDetails,
            @RequestParam(required = false) String addr,
            @RequestParam(defaultValue = "200") int detailLimit) {
        ApiResponse<InstanceClientsPageDto> denied = requireAdmin(request);
        if (denied != null) return denied;
        return ApiResponse.ok(instanceApiService.getClients(
                instanceId, condition, includeRaw, includeDetails, addr, detailLimit));
    }

    @GetMapping("/{instanceId}/faults")
    public ApiResponse<InstanceFaultPageDto> faults(
            HttpServletRequest request,
            @PathVariable long instanceId,
            @RequestParam(defaultValue = "1") int pageNo,
            @RequestParam(defaultValue = "20") int pageSize) {
        ApiResponse<InstanceFaultPageDto> denied = requireAdmin(request);
        if (denied != null) return denied;
        return ApiResponse.ok(instanceApiService.getFaults(instanceId, pageNo, pageSize));
    }

    @GetMapping("/{instanceId}/command-analysis")
    public ApiResponse<InstanceCommandAnalysisDto> commandAnalysis(
            HttpServletRequest request,
            @PathVariable long instanceId,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate) {
        ApiResponse<InstanceCommandAnalysisDto> denied = requireAdmin(request);
        if (denied != null) return denied;
        InstanceCommandAnalysisDto data = instanceApiService.getCommandAnalysis(instanceId, startDate, endDate);
        if (data == null) {
            return ApiResponse.fail(404, "节点不存在");
        }
        return ApiResponse.ok(data);
    }

    @GetMapping("/{instanceId}/stat-charts")
    public ApiResponse<Map<String, List<ChartPointDto>>> statCharts(
            HttpServletRequest request,
            @PathVariable long instanceId,
            @RequestParam String statName,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate) {
        ApiResponse<Map<String, List<ChartPointDto>>> denied = requireAdmin(request);
        if (denied != null) return denied;
        return ApiResponse.ok(instanceApiService.getStatCharts(instanceId, statName, startDate, endDate));
    }

    @GetMapping("/{instanceId}/command-names")
    public ApiResponse<List<String>> commandNames(
            HttpServletRequest request,
            @PathVariable long instanceId,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate) {
        ApiResponse<List<String>> denied = requireAdmin(request);
        if (denied != null) return denied;
        return ApiResponse.ok(instanceApiService.getCommandNames(instanceId, startDate, endDate));
    }

    @GetMapping("/{instanceId}/command-charts")
    public ApiResponse<Map<String, List<ChartPointDto>>> commandCharts(
            HttpServletRequest request,
            @PathVariable long instanceId,
            @RequestParam String commands,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate) {
        ApiResponse<Map<String, List<ChartPointDto>>> denied = requireAdmin(request);
        if (denied != null) return denied;
        return ApiResponse.ok(instanceApiService.getCommandCharts(instanceId, commands, startDate, endDate));
    }

    @GetMapping("/{instanceId}/command-chart")
    public ApiResponse<InstanceCommandChartDto> commandChart(
            HttpServletRequest request,
            @PathVariable long instanceId,
            @RequestParam String commandName,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate) {
        ApiResponse<InstanceCommandChartDto> denied = requireAdmin(request);
        if (denied != null) return denied;
        try {
            InstanceCommandChartDto data = instanceApiService.getCommandChart(
                    instanceId, commandName, startDate, endDate);
            if (data == null) {
                return ApiResponse.fail(404, "节点不存在或命令无效");
            }
            return ApiResponse.ok(data);
        } catch (ParseException e) {
            return ApiResponse.fail(400, "日期格式无效");
        }
    }

    @PostMapping("/{instanceId}/command")
    public ApiResponse<InstanceCommandResultDto> command(
            HttpServletRequest request,
            @PathVariable long instanceId,
            @RequestBody Map<String, String> body) {
        ApiResponse<InstanceCommandResultDto> denied = requireAdmin(request);
        if (denied != null) return denied;
        return ApiResponse.ok(instanceApiService.executeCommand(instanceId, body.get("command")));
    }
}
