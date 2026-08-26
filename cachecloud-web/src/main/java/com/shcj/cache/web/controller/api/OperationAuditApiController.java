package com.shcj.cache.web.controller.api;

import com.shcj.cache.web.controller.api.dto.OperationAuditPageDto;
import com.shcj.cache.web.service.OperationAuditService;
import com.shcj.cache.web.vo.ApiResponse;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * 审计日志查询（仅管理员）
 */
@RestController
@RequestMapping("/api/v1/audits")
public class OperationAuditApiController extends AbstractAdminApiController {

    private static final String TIME_PATTERN = "yyyy-MM-dd HH:mm:ss";

    @Autowired
    private OperationAuditService operationAuditService;

    @GetMapping
    public ApiResponse<OperationAuditPageDto> search(
            HttpServletRequest request,
            @RequestParam(required = false) String userName,
            @RequestParam(required = false) String module,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer success,
            @RequestParam(required = false) String startTime,
            @RequestParam(required = false) String endTime,
            @RequestParam(defaultValue = "1") int pageNo,
            @RequestParam(defaultValue = "20") int pageSize) {
        ApiResponse<OperationAuditPageDto> denied = requireAdmin(request);
        if (denied != null) {
            return denied;
        }
        Date start;
        Date end;
        try {
            start = parseTime(startTime);
            end = parseTime(endTime);
        } catch (ParseException e) {
            return ApiResponse.fail(400, "时间格式应为 " + TIME_PATTERN);
        }
        return ApiResponse.ok(operationAuditService.search(userName, module, keyword, success,
                start, end, pageNo, pageSize));
    }

    private Date parseTime(String value) throws ParseException {
        if (StringUtils.isBlank(value)) {
            return null;
        }
        return new SimpleDateFormat(TIME_PATTERN).parse(value.trim());
    }
}
