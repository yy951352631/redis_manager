package com.shcj.cache.web.controller.api;

import com.shcj.cache.entity.AppUser;
import com.shcj.cache.web.controller.api.dto.*;
import com.shcj.cache.web.service.MigrateApiService;
import com.shcj.cache.web.vo.ApiResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/migrates")
public class MigrateApiController extends AbstractAdminApiController {

    @Autowired
    private MigrateApiService migrateApiService;

    @GetMapping
    public ApiResponse<MigrateListPageDto> list(
            HttpServletRequest request,
            com.shcj.cache.entity.AppDataMigrateSearch search,
            @RequestParam(defaultValue = "1") int pageNo,
            @RequestParam(defaultValue = "15") int pageSize) {
        ApiResponse<MigrateListPageDto> denied = requireAdmin(request);
        if (denied != null) return denied;
        if (search == null) {
            search = new com.shcj.cache.entity.AppDataMigrateSearch();
        }
        return ApiResponse.ok(migrateApiService.list(search, pageNo, pageSize));
    }

    @GetMapping("/init")
    public ApiResponse<MigrateInitDto> init(
            HttpServletRequest request,
            @RequestParam(required = false) Long importId) {
        ApiResponse<MigrateInitDto> denied = requireAdmin(request);
        if (denied != null) return denied;
        return ApiResponse.ok(migrateApiService.getInit(importId));
    }

    @GetMapping("/app-instances")
    public ApiResponse<MigrateAppInstancesDto> appInstances(
            HttpServletRequest request,
            @RequestParam long appId,
            @RequestParam(defaultValue = "0") int migrateTool) {
        ApiResponse<MigrateAppInstancesDto> denied = requireAdmin(request);
        if (denied != null) return denied;
        return ApiResponse.ok(migrateApiService.getAppInstances(appId, migrateTool));
    }

    @PostMapping("/check")
    public ApiResponse<MigrateActionResultDto> check(
            HttpServletRequest request,
            @RequestBody Map<String, Object> body) {
        ApiResponse<MigrateActionResultDto> denied = requireAdmin(request);
        if (denied != null) return denied;
        try {
            return ApiResponse.ok(migrateApiService.check(body));
        } catch (Exception e) {
            return ApiResponse.fail(400, e.getMessage());
        }
    }

    @PostMapping("/start")
    public ApiResponse<MigrateActionResultDto> start(
            HttpServletRequest request,
            @RequestBody Map<String, Object> body) {
        ApiResponse<MigrateActionResultDto> denied = requireAdmin(request);
        if (denied != null) return denied;
        try {
            return ApiResponse.ok(migrateApiService.start(resolveApiUser(request), body));
        } catch (Exception e) {
            return ApiResponse.fail(400, e.getMessage());
        }
    }

    @PostMapping("/{id}/stop")
    public ApiResponse<MigrateActionResultDto> stop(
            HttpServletRequest request,
            @PathVariable long id,
            @RequestParam(defaultValue = "0") int migrateTool) {
        ApiResponse<MigrateActionResultDto> denied = requireAdmin(request);
        if (denied != null) return denied;
        return ApiResponse.ok(migrateApiService.stop(id, migrateTool));
    }

    @PostMapping("/{id}/resync")
    public ApiResponse<MigrateActionResultDto> resync(HttpServletRequest request, @PathVariable long id) {
        ApiResponse<MigrateActionResultDto> denied = requireAdmin(request);
        if (denied != null) return denied;
        try {
            return ApiResponse.ok(migrateApiService.resync(id, resolveApiUser(request)));
        } catch (Exception e) {
            return ApiResponse.fail(400, e.getMessage());
        }
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(HttpServletRequest request, @PathVariable long id) {
        ApiResponse<Void> denied = requireAdmin(request);
        if (denied != null) return denied;
        try {
            migrateApiService.delete(id);
            return ApiResponse.ok(null);
        } catch (Exception e) {
            return ApiResponse.fail(400, e.getMessage());
        }
    }

    @GetMapping("/{id}/key-count-compare")
    public ApiResponse<Map<String, Object>> compareKeyCounts(HttpServletRequest request, @PathVariable long id) {
        ApiResponse<Map<String, Object>> denied = requireAdmin(request);
        if (denied != null) return denied;
        try {
            return ApiResponse.ok(migrateApiService.compareKeyCounts(id));
        } catch (Exception e) {
            return ApiResponse.fail(400, e.getMessage());
        }
    }

    @GetMapping("/{id}/log")
    public ApiResponse<MigrateTextDto> log(
            HttpServletRequest request,
            @PathVariable long id,
            @RequestParam(defaultValue = "100") int pageSize) {
        ApiResponse<MigrateTextDto> denied = requireAdmin(request);
        if (denied != null) return denied;
        return ApiResponse.ok(migrateApiService.getLog(id, pageSize));
    }

    @GetMapping("/{id}/config")
    public ApiResponse<MigrateTextDto> config(HttpServletRequest request, @PathVariable long id) {
        ApiResponse<MigrateTextDto> denied = requireAdmin(request);
        if (denied != null) return denied;
        return ApiResponse.ok(migrateApiService.getConfig(id));
    }

    @GetMapping("/{id}/process")
    public ApiResponse<Map<String, Object>> process(
            HttpServletRequest request,
            @PathVariable long id,
            @RequestParam(defaultValue = "0") int migrateTool) {
        ApiResponse<Map<String, Object>> denied = requireAdmin(request);
        if (denied != null) return denied;
        return ApiResponse.ok(migrateApiService.getProcess(id, migrateTool));
    }
}
