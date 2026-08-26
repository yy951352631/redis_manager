package com.shcj.cache.web.service;

import com.shcj.cache.dao.AppDao;
import com.shcj.cache.dao.OperationAuditDao;
import com.shcj.cache.entity.AppDesc;
import com.shcj.cache.entity.OperationAudit;
import com.shcj.cache.web.controller.api.dto.OperationAuditItemDto;
import com.shcj.cache.web.controller.api.dto.OperationAuditPageDto;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 平台变更操作审计：写入由 OperationAuditInterceptor 触发，查询供 SPA 审计页面使用。
 */
@Service
public class OperationAuditService {

    private static final Logger LOGGER = LoggerFactory.getLogger(OperationAuditService.class);

    private static final String TIME_PATTERN = "yyyy-MM-dd HH:mm:ss";

    @Autowired
    private OperationAuditDao operationAuditDao;

    @Autowired
    private AppDao appDao;

    /**
     * 异步落库，审计失败不影响业务请求。
     */
    @Async("auditTaskExecutor")
    public void record(OperationAudit audit) {
        try {
            operationAuditDao.save(audit);
        } catch (Exception e) {
            LOGGER.warn("save operation audit failed uri={}: {}", audit.getRequestUri(), e.getMessage());
        }
    }

    public OperationAuditPageDto search(String userName, String module, String keyword, Integer success,
                                        Date startTime, Date endTime, int pageNo, int pageSize) {
        int safePageNo = pageNo < 1 ? 1 : pageNo;
        int safePageSize = pageSize < 1 || pageSize > 200 ? 20 : pageSize;

        OperationAuditPageDto page = new OperationAuditPageDto();
        page.setPageNo(safePageNo);
        page.setPageSize(safePageSize);
        page.setModules(listModules());

        int total = operationAuditDao.count(trimToNull(userName), trimToNull(module), trimToNull(keyword),
                success, startTime, endTime);
        page.setTotalCount(total);
        page.setTotalPages(total == 0 ? 0 : (total + safePageSize - 1) / safePageSize);
        if (total == 0) {
            return page;
        }

        List<OperationAudit> records = operationAuditDao.search(trimToNull(userName), trimToNull(module),
                trimToNull(keyword), success, startTime, endTime, (safePageNo - 1) * safePageSize, safePageSize);
        SimpleDateFormat formatter = new SimpleDateFormat(TIME_PATTERN);
        // 一页里同一个 appId 往往重复出现，按页缓存集群名，避免逐行查库
        Map<Long, String> appNameCache = new HashMap<>();
        List<OperationAuditItemDto> items = new ArrayList<>(records.size());
        for (OperationAudit record : records) {
            items.add(toDto(record, formatter, appNameCache));
        }
        page.setItems(items);
        return page;
    }

    public List<String> listModules() {
        try {
            return operationAuditDao.listModules();
        } catch (Exception e) {
            LOGGER.warn("list audit modules failed: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * 清理过期审计记录。
     */
    public int cleanup(Date before) {
        return operationAuditDao.deleteBefore(before);
    }

    private String resolveAppName(Long appId, Map<Long, String> cache) {
        if (appId == null || appId <= 0) {
            return null;
        }
        if (cache.containsKey(appId)) {
            return cache.get(appId);
        }
        String name = null;
        try {
            AppDesc appDesc = appDao.getAppDescById(appId);
            if (appDesc != null) {
                name = appDesc.getName();
            }
        } catch (Exception e) {
            LOGGER.warn("resolve app name failed appId={}: {}", appId, e.getMessage());
        }
        cache.put(appId, name);
        return name;
    }

    private OperationAuditItemDto toDto(OperationAudit record, SimpleDateFormat formatter,
                                        Map<Long, String> appNameCache) {
        OperationAuditItemDto dto = new OperationAuditItemDto();
        dto.setId(record.getId());
        dto.setUserName(record.getUserName());
        dto.setModule(record.getModule());
        dto.setHttpMethod(record.getHttpMethod());
        dto.setRequestUri(record.getRequestUri());
        dto.setHandler(record.getHandler());
        dto.setAppId(record.getAppId());
        dto.setAppName(resolveAppName(record.getAppId(), appNameCache));
        dto.setInstanceId(record.getInstanceId());
        dto.setParams(record.getParams());
        dto.setClientIp(record.getClientIp());
        dto.setStatusCode(record.getStatusCode());
        dto.setSuccess(record.getSuccess() != null && record.getSuccess() == 1);
        dto.setErrorMsg(record.getErrorMsg());
        dto.setCostMs(record.getCostMs());
        dto.setCreateTime(record.getCreateTime() == null ? null : formatter.format(record.getCreateTime()));
        return dto;
    }

    private String trimToNull(String value) {
        return StringUtils.isBlank(value) ? null : value.trim();
    }
}
