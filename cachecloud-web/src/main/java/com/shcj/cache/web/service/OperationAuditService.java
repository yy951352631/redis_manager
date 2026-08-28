package com.shcj.cache.web.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.shcj.cache.dao.AppDao;
import com.shcj.cache.dao.OperationAuditDao;
import com.shcj.cache.entity.AppDesc;
import com.shcj.cache.entity.AppDataMigrateStatus;
import com.shcj.cache.entity.InstanceInfo;
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

    @Autowired
    private com.shcj.cache.dao.InstanceDao instanceDao;

    @Autowired
    private AppService appService;

    @Autowired
    private com.shcj.cache.dao.AppDataMigrateStatusDao appDataMigrateStatusDao;

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
        // 一页里同一个 appId / instanceId / 迁移任务往往重复出现，按页缓存，避免逐行查库
        Map<Long, String> appNameCache = new HashMap<>();
        Map<Long, String> instanceCache = new HashMap<>();
        Map<Long, String> migrateCache = new HashMap<>();
        List<OperationAuditItemDto> items = new ArrayList<>(records.size());
        for (OperationAudit record : records) {
            items.add(toDto(record, formatter, appNameCache, instanceCache, migrateCache));
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

    /** 保留天数。审计记录要留得比监控指标久，出问题时往回追责往往跨月。 */
    private static final int RETENTION_DAYS = 90;

    /** 必须与 OperationAuditDao.xml 中 delete 语句的 limit 语义一致 */
    private static final int DELETE_BATCH_SIZE = 5000;

    /** 单次执行的轮次上限，防止异常情况下空转 */
    private static final int MAX_DELETE_ROUNDS = 500;

    /** 审计里登录相关请求的业务域，取自 uri 的 /api/v1/{module} 段 */
    private static final String AUTH_MODULE = "auth";

    /** 平台自身作为操作对象时的展示名 */
    private static final String PLATFORM_OBJECT = "Redis管理平台";

    /**
     * 清理超过保留期的审计记录。
     *
     * <p>这张表此前没有任何清理调用方，deleteBefore 写好了一直没人用，
     * 上线至今一条没删过。</p>
     *
     * @return 实际删除的行数
     */
    public int cleanupExpired() {
        long start = System.currentTimeMillis();
        Date cutoff = new Date(System.currentTimeMillis() - RETENTION_DAYS * 24L * 3600 * 1000);
        int total = 0;
        for (int round = 0; round < MAX_DELETE_ROUNDS; round++) {
            int deleted;
            try {
                deleted = operationAuditDao.deleteBefore(cutoff, DELETE_BATCH_SIZE);
            } catch (Exception e) {
                LOGGER.warn("delete operation audit failed cutoff={}: {}", cutoff, e.getMessage());
                break;
            }
            total += deleted;
            // 单轮不足批量上限说明已删干净
            if (deleted < DELETE_BATCH_SIZE) {
                break;
            }
        }
        LOGGER.info("operation audit cleaned create_time<{} retentionDays={} rows={} cost={}ms",
                cutoff, RETENTION_DAYS, total, System.currentTimeMillis() - start);
        return total;
    }

    /**
     * 清理指定时间之前的审计记录，供测试与手工调用。
     */
    public int cleanup(Date before) {
        return operationAuditDao.deleteBefore(before, DELETE_BATCH_SIZE);
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
                                        Map<Long, String> appNameCache,
                                        Map<Long, String> instanceCache,
                                        Map<Long, String> migrateCache) {
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
        dto.setObjectLabel(resolveObjectLabel(record, dto.getAppName(), instanceCache, migrateCache));
        dto.setParams(record.getParams());
        dto.setClientIp(record.getClientIp());
        dto.setStatusCode(record.getStatusCode());
        dto.setSuccess(record.getSuccess() != null && record.getSuccess() == 1);
        dto.setErrorMsg(record.getErrorMsg());
        dto.setCostMs(record.getCostMs());
        dto.setCreateTime(record.getCreateTime() == null ? null : formatter.format(record.getCreateTime()));
        return dto;
    }

    /**
     * 操作对象文案。
     *
     * <p>优先级：集群 &gt; 节点 &gt; 迁移任务的源与目标。迁移接口的路径里既没有
     * apps 也没有 instances，拦截器解析不出 appId/instanceId，只能顺着任务 id
     * 回查；start / check 这类没有任务 id 的，从请求体里取源和目标。</p>
     */
    /**
     * 单条记录的操作对象文案，供主页「最近操作」复用。
     *
     * <p>主页只取 10 行且集群名另有来源，这里不带缓存，只解析节点与迁移任务。</p>
     */
    public String resolveObjectLabel(OperationAudit record) {
        return resolveObjectLabel(record, null, new HashMap<Long, String>(), new HashMap<Long, String>());
    }

    private String resolveObjectLabel(OperationAudit record, String appName,
                                      Map<Long, String> instanceCache, Map<Long, String> migrateCache) {
        if (StringUtils.isNotBlank(appName)) {
            return appName;
        }
        Long appId = record.getAppId();
        if (appId != null && appId > 0) {
            return "集群 " + appId;
        }
        Long instanceId = record.getInstanceId();
        if (instanceId != null && instanceId > 0) {
            String hostPort = resolveInstanceHostPort(instanceId, instanceCache);
            return StringUtils.isNotBlank(hostPort) ? hostPort : "节点 " + instanceId;
        }
        String migrateLabel = resolveMigrateLabel(record, migrateCache);
        if (StringUtils.isNotBlank(migrateLabel)) {
            return migrateLabel;
        }
        // 登录/登出/改个人资料操作的对象既不是集群也不是节点，就是平台本身
        if (AUTH_MODULE.equals(StringUtils.trimToEmpty(record.getModule()))) {
            return PLATFORM_OBJECT;
        }
        return null;
    }

    private String resolveInstanceHostPort(Long instanceId, Map<Long, String> cache) {
        if (cache.containsKey(instanceId)) {
            return cache.get(instanceId);
        }
        String hostPort = null;
        try {
            InstanceInfo instance = instanceDao.getInstanceInfoById(instanceId);
            if (instance != null && StringUtils.isNotBlank(instance.getIp())) {
                hostPort = instance.getIp() + ":" + instance.getPort();
            }
        } catch (Exception e) {
            LOGGER.warn("resolve instance host:port failed instanceId={}: {}", instanceId, e.getMessage());
        }
        cache.put(instanceId, hostPort);
        return hostPort;
    }

    private String resolveMigrateLabel(OperationAudit record, Map<Long, String> cache) {
        String uri = StringUtils.trimToEmpty(record.getRequestUri());
        if (!uri.contains("/migrates")) {
            return null;
        }
        Long migrateId = pathIdAfter(uri, "migrates");
        if (migrateId != null) {
            if (cache.containsKey(migrateId)) {
                return cache.get(migrateId);
            }
            String label = null;
            try {
                AppDataMigrateStatus status = appDataMigrateStatusDao.get(migrateId);
                if (status != null) {
                    label = arrow(status.getSourceServers(), status.getTargetServers());
                }
            } catch (Exception e) {
                LOGGER.warn("resolve migrate object failed id={}: {}", migrateId, e.getMessage());
            }
            cache.put(migrateId, label);
            return label;
        }
        // start / check 没有任务 id，源和目标只在请求体里
        return migrateLabelFromBody(record.getParams());
    }

    private String migrateLabelFromBody(String params) {
        if (StringUtils.isBlank(params)) {
            return null;
        }
        try {
            JSONObject outer = JSON.parseObject(params);
            String body = outer == null ? null : outer.getString("_body");
            if (StringUtils.isBlank(body)) {
                return null;
            }
            JSONObject payload = JSON.parseObject(body);
            if (payload == null) {
                return null;
            }
            String source = StringUtils.trimToEmpty(payload.getString("sourceServers"));
            String target = StringUtils.trimToEmpty(payload.getString("targetServers"));
            // 选「平台纳管」时节点串留空，落在 sourceAppId / targetAppId 上
            if (source.isEmpty()) {
                source = appLabel(payload.getLong("sourceAppId"));
            }
            if (target.isEmpty()) {
                target = appLabel(payload.getLong("targetAppId"));
            }
            return arrow(source, target);
        } catch (Exception e) {
            return null;
        }
    }

    private String appLabel(Long idOrClusterNo) {
        if (idOrClusterNo == null || idOrClusterNo <= 0) {
            return "";
        }
        try {
            // 迁移表单里传的是 clusterNo 而不是 appId，得走同一套解析才能拿到集群名
            AppDesc appDesc = appService.resolveApp(idOrClusterNo);
            if (appDesc != null && StringUtils.isNotBlank(appDesc.getName())) {
                return appDesc.getName();
            }
        } catch (Exception ignored) {
            // 名字取不到就退回编号，不因为一个展示字段让整页失败
        }
        return "集群 " + idOrClusterNo;
    }

    private String arrow(String source, String target) {
        String from = StringUtils.trimToEmpty(source);
        String to = StringUtils.trimToEmpty(target);
        if (from.isEmpty() && to.isEmpty()) {
            return null;
        }
        return (from.isEmpty() ? "?" : from) + " → " + (to.isEmpty() ? "?" : to);
    }

    private Long pathIdAfter(String uri, String segment) {
        String[] segments = StringUtils.split(uri, '/');
        if (segments == null) {
            return null;
        }
        for (int i = 0; i < segments.length - 1; i++) {
            if (segment.equals(segments[i])) {
                try {
                    return Long.valueOf(segments[i + 1].trim());
                } catch (NumberFormatException e) {
                    return null;
                }
            }
        }
        return null;
    }

    private String trimToNull(String value) {
        return StringUtils.isBlank(value) ? null : value.trim();
    }
}
