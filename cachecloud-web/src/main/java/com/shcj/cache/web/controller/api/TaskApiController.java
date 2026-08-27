package com.shcj.cache.web.controller.api;

import com.shcj.cache.web.controller.api.dto.TaskFlowDetailDto;
import com.shcj.cache.web.service.TaskApiService;
import com.shcj.cache.web.vo.ApiResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.util.Map;

/**
 * 任务流详情。
 *
 * <p>任务管理页面已下线，这里只留「键值分析」查询分析任务进度用的一个接口，
 * 底层的任务执行框架仍在为数据迁移、键值分析、故障诊断服务。</p>
 */
@RestController
@RequestMapping("/api/v1/tasks")
public class TaskApiController extends AbstractAdminApiController {

    @Autowired
    private TaskApiService taskApiService;

    @GetMapping("/{taskId}")
    public ApiResponse<TaskFlowDetailDto> getFlow(HttpServletRequest request, @PathVariable long taskId) {
        ApiResponse<TaskFlowDetailDto> denied = requireAdmin(request);
        if (denied != null) return denied;
        TaskFlowDetailDto data = taskApiService.getFlow(taskId);
        if (data == null) return ApiResponse.fail(404, "任务不存在");
        return ApiResponse.ok(data);
    }
}
