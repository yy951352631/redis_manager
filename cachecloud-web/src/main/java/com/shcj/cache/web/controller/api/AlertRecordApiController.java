package com.shcj.cache.web.controller.api;

import com.shcj.cache.web.controller.api.dto.AlertRecordPageDto;
import com.shcj.cache.web.service.AppAlertRecordService;
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
 * 报警记录查询，登录用户均可查看。
 *
 * <p>app_alert_record 汇集了实例存活、实例状态、实例分钟报警、机器内存、
 * 应用连接数/命中率/内存使用率等各类报警，此前只写不读。</p>
 */
@RestController
@RequestMapping("/api/v1/alert-records")
public class AlertRecordApiController extends AbstractAdminApiController {

    private static final String TIME_PATTERN = "yyyy-MM-dd HH:mm:ss";

    @Autowired
    private AppAlertRecordService appAlertRecordService;

    @GetMapping
    public ApiResponse<AlertRecordPageDto> search(
            HttpServletRequest request,
            @RequestParam(required = false) Integer importantLevel,
            @RequestParam(required = false) Long appId,
            @RequestParam(required = false) String ip,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String startTime,
            @RequestParam(required = false) String endTime,
            @RequestParam(defaultValue = "1") int pageNo,
            @RequestParam(defaultValue = "20") int pageSize) {
        ApiResponse<AlertRecordPageDto> denied = requireLogin(request);
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
        return ApiResponse.ok(appAlertRecordService.search(importantLevel, appId, ip,
                keyword, start, end, pageNo, pageSize));
    }

    private Date parseTime(String value) throws ParseException {
        if (StringUtils.isBlank(value)) {
            return null;
        }
        return new SimpleDateFormat(TIME_PATTERN).parse(value.trim());
    }
}
