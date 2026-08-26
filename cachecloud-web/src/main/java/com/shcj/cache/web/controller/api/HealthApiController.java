package com.shcj.cache.web.controller.api;

import com.shcj.cache.web.vo.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.Map;

/**
 * 存活探针。前后端分离后后端不再提供任何页面，容器健康检查改用本接口。
 */
@RestController
@RequestMapping("/api/v1")
public class HealthApiController {

    @GetMapping("/health")
    public ApiResponse<Map<String, String>> health() {
        return ApiResponse.ok(Collections.singletonMap("status", "UP"));
    }
}
