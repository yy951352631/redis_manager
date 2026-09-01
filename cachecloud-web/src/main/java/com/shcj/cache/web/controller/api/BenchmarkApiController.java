package com.shcj.cache.web.controller.api;

import com.alibaba.fastjson.JSON;
import com.shcj.cache.benchmark.BenchmarkOptions;
import com.shcj.cache.benchmark.BenchmarkProgress;
import com.shcj.cache.benchmark.BenchmarkService;
import com.shcj.cache.benchmark.BenchmarkTask;
import com.shcj.cache.web.vo.ApiResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/benchmark")
public class BenchmarkApiController extends AbstractAdminApiController {

    @Autowired
    private BenchmarkService benchmarkService;

    /** 命令目录：按数据类型分组，读写分列 */
    @GetMapping("/commands")
    public ApiResponse<Map<String, List<Map<String, String>>>> commands(HttpServletRequest request) {
        ApiResponse<Map<String, List<Map<String, String>>>> denied = requireAdmin(request);
        if (denied != null) return denied;
        return ApiResponse.ok(benchmarkService.commandCatalog());
    }

    /** Cluster 集群可定向的 master 节点及槽位范围 */
    @GetMapping("/targets")
    public ApiResponse<List<Map<String, Object>>> targets(HttpServletRequest request,
                                                          @RequestParam long appId) {
        ApiResponse<List<Map<String, Object>>> denied = requireAdmin(request);
        if (denied != null) return denied;
        try {
            return ApiResponse.ok(benchmarkService.listTargets(appId));
        } catch (Exception e) {
            return ApiResponse.fail(400, e.getMessage());
        }
    }

    @PostMapping("/start")
    public ApiResponse<Map<String, Object>> start(HttpServletRequest request,
                                                  @RequestBody BenchmarkOptions options) {
        ApiResponse<Map<String, Object>> denied = requireAdmin(request);
        if (denied != null) return denied;
        try {
            long taskId = benchmarkService.start(options,
                    resolveApiUser(request) == null ? "" : resolveApiUser(request).getName());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("taskId", taskId);
            return ApiResponse.ok(result);
        } catch (Exception e) {
            return ApiResponse.fail(400, e.getMessage());
        }
    }

    /** 快捷压测：默认参数逐档加并发，直到打满或出现拐点 */
    @PostMapping("/quick")
    public ApiResponse<Map<String, Object>> quick(HttpServletRequest request,
                                                  @RequestBody Map<String, Object> body) {
        ApiResponse<Map<String, Object>> denied = requireAdmin(request);
        if (denied != null) return denied;
        try {
            Object appIdRaw = body == null ? null : body.get("appId");
            if (appIdRaw == null) {
                return ApiResponse.fail(400, "请选择目标集群");
            }
            @SuppressWarnings("unchecked")
            List<String> nodes = body.get("targetNodes") instanceof List
                    ? (List<String>) body.get("targetNodes") : null;
            long taskId = benchmarkService.startQuick(Long.parseLong(String.valueOf(appIdRaw)), nodes,
                    resolveApiUser(request) == null ? "" : resolveApiUser(request).getName());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("taskId", taskId);
            return ApiResponse.ok(result);
        } catch (Exception e) {
            return ApiResponse.fail(400, e.getMessage());
        }
    }

    @PostMapping("/{taskId}/stop")
    public ApiResponse<Void> stop(HttpServletRequest request, @PathVariable long taskId) {
        ApiResponse<Void> denied = requireAdmin(request);
        if (denied != null) return denied;
        try {
            benchmarkService.stop(taskId);
            return ApiResponse.ok();
        } catch (Exception e) {
            return ApiResponse.fail(400, e.getMessage());
        }
    }

    @DeleteMapping("/{taskId}")
    public ApiResponse<Void> delete(HttpServletRequest request, @PathVariable long taskId) {
        ApiResponse<Void> denied = requireAdmin(request);
        if (denied != null) return denied;
        try {
            benchmarkService.delete(taskId);
            return ApiResponse.ok();
        } catch (Exception e) {
            return ApiResponse.fail(400, e.getMessage());
        }
    }

    @GetMapping("/{taskId}/progress")
    public ApiResponse<BenchmarkProgress> progress(HttpServletRequest request, @PathVariable long taskId) {
        ApiResponse<BenchmarkProgress> denied = requireAdmin(request);
        if (denied != null) return denied;
        try {
            return ApiResponse.ok(benchmarkService.progress(taskId));
        } catch (Exception e) {
            return ApiResponse.fail(400, e.getMessage());
        }
    }

    @GetMapping("/{taskId}")
    public ApiResponse<Map<String, Object>> detail(HttpServletRequest request, @PathVariable long taskId) {
        ApiResponse<Map<String, Object>> denied = requireAdmin(request);
        if (denied != null) return denied;
        try {
            return ApiResponse.ok(toDetail(benchmarkService.get(taskId)));
        } catch (Exception e) {
            return ApiResponse.fail(400, e.getMessage());
        }
    }

    @GetMapping
    public ApiResponse<Map<String, Object>> list(HttpServletRequest request,
                                                 @RequestParam(required = false) Long appId,
                                                 @RequestParam(defaultValue = "1") int pageNo,
                                                 @RequestParam(defaultValue = "20") int pageSize) {
        ApiResponse<Map<String, Object>> denied = requireAdmin(request);
        if (denied != null) return denied;
        List<BenchmarkTask> tasks = benchmarkService.list(appId, pageNo, pageSize);
        List<Map<String, Object>> items = new java.util.ArrayList<>();
        for (BenchmarkTask task : tasks) {
            items.add(toDetail(task));
        }
        Map<String, Object> page = new LinkedHashMap<>();
        page.put("items", items);
        page.put("totalCount", benchmarkService.count(appId));
        page.put("pageNo", pageNo);
        page.put("pageSize", pageSize);
        return ApiResponse.ok(page);
    }

    /** JSON 字段在接口层解开，界面不必再解析一次字符串 */
    private Map<String, Object> toDetail(BenchmarkTask task) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", task.getId());
        item.put("appId", task.getAppId());
        item.put("appName", task.getAppName());
        item.put("targetDesc", task.getTargetDesc());
        item.put("status", task.getStatus());
        item.put("totalRequests", task.getTotalRequests());
        item.put("errorCount", task.getErrorCount());
        item.put("qps", task.getQps());
        item.put("p50Ms", task.getP50Ms());
        item.put("p95Ms", task.getP95Ms());
        item.put("p99Ms", task.getP99Ms());
        item.put("maxMs", task.getMaxMs());
        item.put("avgMs", task.getAvgMs());
        item.put("clientCpuPercent", task.getClientCpuPercent());
        item.put("rampMessage", task.getRampMessage());
        item.put("peakConcurrency", task.getPeakConcurrency());
        item.put("targetCpuPercent", task.getTargetCpuPercent());
        item.put("avgTargetCpuPercent", task.getAvgTargetCpuPercent());
        item.put("rampSteps", parse(task.getRampJson()));
        item.put("userName", task.getUserName());
        item.put("errorMsg", task.getErrorMsg());
        item.put("startTime", task.getStartTime());
        item.put("endTime", task.getEndTime());
        item.put("options", parse(task.getOptionsJson()));
        item.put("commandStats", parse(task.getCommandStatsJson()));
        item.put("errorStats", parse(task.getErrorStatsJson()));
        return item;
    }

    private Object parse(String json) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        try {
            return JSON.parse(json);
        } catch (Exception e) {
            return null;
        }
    }
}
