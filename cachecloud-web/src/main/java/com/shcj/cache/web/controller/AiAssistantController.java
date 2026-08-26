package com.shcj.cache.web.controller;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.shcj.cache.web.service.AiChatService;
import com.shcj.cache.web.service.AiPlatformContextService;
import com.shcj.cache.web.service.FaultDiagnosticService;
import com.shcj.cache.web.vo.AjaxResult;
import com.shcj.cache.web.vo.FaultDiagnosticReportVO;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.math.NumberUtils;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 管理后台 AI 助手（DeepSeek）。
 */
@Controller
@RequestMapping("/manage/ai")
public class AiAssistantController extends BaseController {

    @Resource(name = "aiChatService")
    private AiChatService aiChatService;

    @Resource(name = "aiPlatformContextService")
    private AiPlatformContextService aiPlatformContextService;

    @Resource(name = "faultDiagnosticService")
    private FaultDiagnosticService faultDiagnosticService;

    @RequestMapping(value = "/status", method = RequestMethod.GET)
    @ResponseBody
    public AjaxResult status() {
        JSONObject data = new JSONObject();
        data.put("enabled", aiChatService.isEnabled());
        return AjaxResult.ok(data);
    }

    @RequestMapping(value = "/chat.json", method = RequestMethod.POST)
    @ResponseBody
    public AjaxResult chat(HttpServletRequest request, HttpServletResponse response) {
        response.setCharacterEncoding("UTF-8");
        response.setContentType("application/json;charset=UTF-8");
        if (!aiChatService.isEnabled()) {
            return AjaxResult.error("AI 助手未启用，请配置 cachecloud.ai");
        }
        String message = StringUtils.trimToEmpty(request.getParameter("message"));
        if (StringUtils.isBlank(message)) {
            return AjaxResult.error("请输入问题");
        }
        if (message.length() > 4000) {
            return AjaxResult.error("问题过长，请控制在 4000 字以内");
        }

        List<Map<String, String>> messages = parseHistory(request.getParameter("history"));
        Map<String, String> userMsg = new HashMap<String, String>();
        userMsg.put("role", "user");

        StringBuilder content = new StringBuilder();
        long appId = NumberUtils.toLong(request.getParameter("appId"));
        long instanceId = NumberUtils.toLong(request.getParameter("instanceId"));
        String diagnosticReportId = StringUtils.trimToEmpty(request.getParameter("diagnosticReportId"));
        String diagnosticReportJson = StringUtils.trimToEmpty(request.getParameter("diagnosticReportJson"));
        String platformData = aiPlatformContextService.buildContext(
                appId > 0 ? appId : null,
                instanceId > 0 ? instanceId : null,
                StringUtils.isNotBlank(diagnosticReportId) ? diagnosticReportId : null,
                StringUtils.isNotBlank(diagnosticReportJson) ? diagnosticReportJson : null);
        if (StringUtils.isNotBlank(platformData)) {
            content.append(platformData).append("\n\n");
        }
        String pageContext = StringUtils.trimToEmpty(request.getParameter("pageContext"));
        if (StringUtils.isNotBlank(pageContext)) {
            content.append("【当前页面】").append(pageContext).append("\n\n");
        }
        content.append("【问题】").append(message);
        userMsg.put("content", content.toString());
        messages.add(userMsg);

        try {
            String reply = aiChatService.chat(messages);
            JSONObject data = new JSONObject();
            data.put("reply", reply);
            return AjaxResult.ok(data);
        } catch (IllegalArgumentException e) {
            return AjaxResult.error(e.getMessage());
        } catch (Exception e) {
            logger.warn("AI chat error: {}", e.getMessage());
            return AjaxResult.error(e.getMessage());
        }
    }

    @RequestMapping(value = "/diagnose-report.json", method = RequestMethod.POST)
    @ResponseBody
    public AjaxResult diagnoseReport(HttpServletRequest request, HttpServletResponse response) {
        response.setCharacterEncoding("UTF-8");
        response.setContentType("application/json;charset=UTF-8");
        if (!aiChatService.isEnabled()) {
            return AjaxResult.error("AI 助手未启用，请配置 cachecloud.ai");
        }
        String reportId = StringUtils.trimToEmpty(request.getParameter("reportId"));
        if (StringUtils.isBlank(reportId)) {
            return AjaxResult.error("缺少 reportId");
        }
        String reportJson = StringUtils.trimToEmpty(request.getParameter("reportJson"));
        try {
            FaultDiagnosticReportVO report = faultDiagnosticService.resolveReport(reportId, reportJson);
            if (report == null) {
                return AjaxResult.error("诊断报告不存在或已过期，请重新执行 Layer① 诊断");
            }
            String summary = faultDiagnosticService.generateAiSummaryForReport(report);
            JSONObject data = new JSONObject();
            data.put("summary", summary);
            data.put("reportId", reportId);
            return AjaxResult.ok(data);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return AjaxResult.error(e.getMessage());
        } catch (Exception e) {
            logger.warn("AI diagnose report error: {}", e.getMessage());
            return AjaxResult.error(e.getMessage());
        }
    }

    private List<Map<String, String>> parseHistory(String historyJson) {
        List<Map<String, String>> list = new ArrayList<Map<String, String>>();
        if (StringUtils.isBlank(historyJson)) {
            return list;
        }
        try {
            JSONArray arr = JSON.parseArray(historyJson);
            if (arr == null) {
                return list;
            }
            int start = Math.max(0, arr.size() - 10);
            for (int i = start; i < arr.size(); i++) {
                JSONObject item = arr.getJSONObject(i);
                if (item == null) {
                    continue;
                }
                String role = item.getString("role");
                String content = StringUtils.trimToEmpty(item.getString("content"));
                if (StringUtils.isBlank(content)) {
                    continue;
                }
                if (!"user".equals(role) && !"assistant".equals(role)) {
                    continue;
                }
                Map<String, String> msg = new HashMap<String, String>();
                msg.put("role", role);
                msg.put("content", content);
                list.add(msg);
            }
        } catch (Exception e) {
            logger.warn("ignore invalid ai history: {}", e.getMessage());
        }
        return list;
    }
}
