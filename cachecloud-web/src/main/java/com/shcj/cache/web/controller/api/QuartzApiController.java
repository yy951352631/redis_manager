package com.shcj.cache.web.controller.api;

import com.shcj.cache.web.controller.api.dto.QuartzJobDto;
import com.shcj.cache.web.service.QuartzApiService;
import com.shcj.cache.web.vo.ApiResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.util.List;

@RestController
@RequestMapping("/api/v1/quartz/jobs")
public class QuartzApiController extends AbstractAdminApiController {

    @Autowired
    private QuartzApiService quartzApiService;

    @GetMapping
    public ApiResponse<List<QuartzJobDto>> list(
            HttpServletRequest request,
            @RequestParam(value = "query", required = false) String query) {
        ApiResponse<List<QuartzJobDto>> denied = requireAdmin(request);
        if (denied != null) {
            return denied;
        }
        return ApiResponse.ok(quartzApiService.listJobs(query));
    }

    @PostMapping("/pause")
    public ApiResponse<Void> pause(
            HttpServletRequest request,
            @RequestParam String triggerName,
            @RequestParam String triggerGroup) {
        ApiResponse<Void> denied = requireAdmin(request);
        if (denied != null) {
            return denied;
        }
        quartzApiService.pause(triggerName, triggerGroup);
        return ApiResponse.ok();
    }

    @PostMapping("/resume")
    public ApiResponse<Void> resume(
            HttpServletRequest request,
            @RequestParam String triggerName,
            @RequestParam String triggerGroup) {
        ApiResponse<Void> denied = requireAdmin(request);
        if (denied != null) {
            return denied;
        }
        quartzApiService.resume(triggerName, triggerGroup);
        return ApiResponse.ok();
    }

    @DeleteMapping
    public ApiResponse<Void> remove(
            HttpServletRequest request,
            @RequestParam String triggerName,
            @RequestParam String triggerGroup) {
        ApiResponse<Void> denied = requireAdmin(request);
        if (denied != null) {
            return denied;
        }
        quartzApiService.remove(triggerName, triggerGroup);
        return ApiResponse.ok();
    }
}
