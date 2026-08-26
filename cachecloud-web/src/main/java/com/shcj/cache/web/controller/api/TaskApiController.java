package com.shcj.cache.web.controller.api;

import com.shcj.cache.web.controller.api.dto.TaskFlowDetailDto;
import com.shcj.cache.web.controller.api.dto.TaskListPageDto;
import com.shcj.cache.web.service.TaskApiService;
import com.shcj.cache.web.vo.ApiResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/tasks")
public class TaskApiController extends AbstractAdminApiController {

    @Autowired
    private TaskApiService taskApiService;

    @GetMapping
    public ApiResponse<TaskListPageDto> list(
            HttpServletRequest request,
            @RequestParam(required = false) Long searchTaskId,
            @RequestParam(required = false) Long appId,
            @RequestParam(required = false) String className,
            @RequestParam(required = false) Integer status,
            @RequestParam(defaultValue = "1") int pageNo,
            @RequestParam(defaultValue = "30") int pageSize) {
        ApiResponse<TaskListPageDto> denied = requireAdmin(request);
        if (denied != null) return denied;
        return ApiResponse.ok(taskApiService.list(searchTaskId, appId, className, status, pageNo, pageSize));
    }

    @GetMapping("/{taskId}")
    public ApiResponse<TaskFlowDetailDto> getFlow(HttpServletRequest request, @PathVariable long taskId) {
        ApiResponse<TaskFlowDetailDto> denied = requireAdmin(request);
        if (denied != null) return denied;
        TaskFlowDetailDto data = taskApiService.getFlow(taskId);
        if (data == null) return ApiResponse.fail(404, "任务不存在");
        return ApiResponse.ok(data);
    }

    @PostMapping("/{taskId}/execute")
    public ApiResponse<Void> execute(HttpServletRequest request, @PathVariable long taskId) {
        ApiResponse<Void> denied = requireAdmin(request);
        if (denied != null) return denied;
        taskApiService.execute(taskId);
        return ApiResponse.ok();
    }
}
