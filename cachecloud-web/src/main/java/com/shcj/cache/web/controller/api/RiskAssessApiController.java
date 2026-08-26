package com.shcj.cache.web.controller.api;

import com.shcj.cache.entity.AppUser;
import com.shcj.cache.web.controller.api.dto.RiskAssessOverviewDto;
import com.shcj.cache.web.controller.api.dto.RiskAssessReportDto;
import com.shcj.cache.web.controller.api.dto.RiskRuleDimensionDto;
import com.shcj.cache.web.service.RiskAssessApiService;
import com.shcj.cache.web.vo.ApiResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.util.List;

/**
 * 多维度风险评估
 */
@RestController
@RequestMapping("/api/v1/risk-assess")
public class RiskAssessApiController extends AbstractAdminApiController {

    @Autowired
    private RiskAssessApiService riskAssessApiService;

    /** 评估策略：各维度的判定规则与阈值，供「评估策略」抽屉展示 */
    @GetMapping("/rules")
    public ApiResponse<List<RiskRuleDimensionDto>> rules(HttpServletRequest request) {
        ApiResponse<List<RiskRuleDimensionDto>> denied = requireAdmin(request);
        if (denied != null) {
            return denied;
        }
        return ApiResponse.ok(riskAssessApiService.listRules());
    }

    /** 总览清单：全部在线集群 + 各自最近一次评估结果 */
    @GetMapping("/overview")
    public ApiResponse<RiskAssessOverviewDto> overview(
            HttpServletRequest request,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String level,
            @RequestParam(defaultValue = "1") int pageNo,
            @RequestParam(defaultValue = "20") int pageSize) {
        ApiResponse<RiskAssessOverviewDto> denied = requireAdmin(request);
        if (denied != null) {
            return denied;
        }
        return ApiResponse.ok(riskAssessApiService.overview(keyword, level, pageNo, pageSize));
    }

    /**
     * 触发一次评估。同步执行——命令耗时基线由定时任务预计算，评估本身退化为一次 INFO 采集
     * 加若干索引查询，无需异步轮询。
     */
    @PostMapping("/apps/{appId}")
    public ApiResponse<RiskAssessReportDto> assess(
            HttpServletRequest request,
            @PathVariable long appId,
            @RequestParam(defaultValue = "168") int windowHours) {
        ApiResponse<RiskAssessReportDto> denied = requireAdmin(request);
        if (denied != null) {
            return denied;
        }
        AppUser user = resolveApiUser(request);
        try {
            return ApiResponse.ok(riskAssessApiService.assess(appId, windowHours,
                    user == null ? null : user.getName()));
        } catch (IllegalArgumentException e) {
            return ApiResponse.fail(400, e.getMessage());
        } catch (Exception e) {
            logger.error("risk assess failed appId={}: {}", appId, e.getMessage(), e);
            return ApiResponse.fail(500, "评估执行失败：" + e.getMessage());
        }
    }

    @GetMapping("/reports/{reportId}")
    public ApiResponse<RiskAssessReportDto> report(HttpServletRequest request, @PathVariable long reportId) {
        ApiResponse<RiskAssessReportDto> denied = requireAdmin(request);
        if (denied != null) {
            return denied;
        }
        RiskAssessReportDto dto = riskAssessApiService.getReport(reportId);
        return dto == null ? ApiResponse.fail(404, "报告不存在") : ApiResponse.ok(dto);
    }

    @GetMapping("/apps/{appId}/history")
    public ApiResponse<List<RiskAssessReportDto>> history(
            HttpServletRequest request,
            @PathVariable long appId,
            @RequestParam(defaultValue = "10") int limit) {
        ApiResponse<List<RiskAssessReportDto>> denied = requireAdmin(request);
        if (denied != null) {
            return denied;
        }
        return ApiResponse.ok(riskAssessApiService.history(appId, limit));
    }
}
