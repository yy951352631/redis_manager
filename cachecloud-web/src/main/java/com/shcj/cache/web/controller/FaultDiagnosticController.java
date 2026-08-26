package com.shcj.cache.web.controller;

import com.shcj.cache.web.service.FaultDiagnosticService;
import com.shcj.cache.web.vo.FaultDiagnosticReportVO;
import com.shcj.cache.web.vo.AjaxResult;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import javax.annotation.Resource;

/**
 * 故障诊断：Layer① 规则检查 + 报告查询。
 */
@Controller
@RequestMapping("/manage/diagnostic/fault")
public class FaultDiagnosticController extends BaseController {

    @Resource(name = "faultDiagnosticService")
    private FaultDiagnosticService faultDiagnosticService;

    @RequestMapping(value = "/report.json", method = RequestMethod.GET)
    @ResponseBody
    public AjaxResult report(@RequestParam String reportId) {
        FaultDiagnosticReportVO report = faultDiagnosticService.getReport(reportId);
        if (report == null) {
            return AjaxResult.error("报告不存在或已过期");
        }
        return AjaxResult.ok(report);
    }
}
