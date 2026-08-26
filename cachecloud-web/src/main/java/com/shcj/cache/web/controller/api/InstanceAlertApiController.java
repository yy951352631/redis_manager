package com.shcj.cache.web.controller.api;

import com.shcj.cache.web.controller.api.dto.InstanceAlertPageDto;
import com.shcj.cache.web.controller.api.dto.InstanceAlertSaveRequestDto;
import com.shcj.cache.web.service.InstanceAlertApiService;
import com.shcj.cache.web.vo.ApiResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/api/v1/instance-alerts")
public class InstanceAlertApiController extends AbstractAdminApiController {

    @Autowired
    private InstanceAlertApiService instanceAlertApiService;

    @GetMapping
    public ApiResponse<InstanceAlertPageDto> getPage(HttpServletRequest request) {
        ApiResponse<InstanceAlertPageDto> denied = requireAdmin(request);
        if (denied != null) return denied;
        return ApiResponse.ok(instanceAlertApiService.getPage());
    }

    @PostMapping
    public ApiResponse<Void> add(HttpServletRequest request, @RequestBody InstanceAlertSaveRequestDto body) {
        ApiResponse<Void> denied = requireAdmin(request);
        if (denied != null) return denied;
        instanceAlertApiService.add(body);
        return ApiResponse.ok();
    }

    @PostMapping("/app")
    public ApiResponse<Void> addApp(HttpServletRequest request, @RequestBody InstanceAlertSaveRequestDto body) {
        ApiResponse<Void> denied = requireAdmin(request);
        if (denied != null) return denied;
        instanceAlertApiService.addApp(body);
        return ApiResponse.ok();
    }

    @PutMapping("/{id}")
    public ApiResponse<Void> update(
            HttpServletRequest request,
            @PathVariable long id,
            @RequestParam String alertValue,
            @RequestParam int checkCycle,
            @RequestParam int compareType,
            @RequestParam int importantLevel) {
        ApiResponse<Void> denied = requireAdmin(request);
        if (denied != null) return denied;
        instanceAlertApiService.update(id, alertValue, checkCycle, compareType, importantLevel);
        return ApiResponse.ok();
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> remove(HttpServletRequest request, @PathVariable long id) {
        ApiResponse<Void> denied = requireAdmin(request);
        if (denied != null) return denied;
        instanceAlertApiService.remove(id);
        return ApiResponse.ok();
    }
}
