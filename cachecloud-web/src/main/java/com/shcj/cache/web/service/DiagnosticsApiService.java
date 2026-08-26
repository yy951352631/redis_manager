package com.shcj.cache.web.service;

import com.alibaba.fastjson.JSONObject;
import com.shcj.cache.constant.AppCheckEnum;
import com.shcj.cache.constant.DiagnosticTypeEnum;
import com.shcj.cache.dao.AppAuditDao;
import com.shcj.cache.dao.AppDao;
import com.shcj.cache.dao.DiagnosticTaskRecordDao;
import com.shcj.cache.dao.TaskQueueDao;
import com.shcj.cache.entity.*;
import com.shcj.cache.exception.BizException;
import com.shcj.cache.redis.AssistRedisService;
import com.shcj.cache.redis.RedisCenter;
import com.shcj.cache.task.TaskService;
import com.shcj.cache.task.constant.TaskConstants;
import com.shcj.cache.task.entity.TaskQueue;
import com.shcj.cache.util.AppClusterNoSupport;
import com.shcj.cache.util.StringUtil;
import com.shcj.cache.util.TypeUtil;
import com.shcj.cache.web.controller.api.dto.*;
import com.shcj.cache.web.util.DateUtil;
import org.apache.commons.lang.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.*;

@Service
public class DiagnosticsApiService {

    private static final int DELETE_STATUS_NO_MATCH = 0;
    private static final int DELETE_STATUS_DELETED = 1;
    private static final int DELETE_STATUS_NOT_DELETED = 2;
    /** diagnostic_task_record.status：0=进行中 1=成功 2=异常 */
    private static final int DIAGNOSTIC_STATUS_RUNNING = 0;
    private static final String PENDING_NODE_TEXT = "所有主节点（正在创建子任务）";

    @Autowired
    private AppDao appDao;

    @Autowired
    private AppService appService;

    @Autowired
    private DiagnosticToolService diagnosticToolService;

    @Autowired
    private TaskService taskService;

    @Autowired
    private TaskQueueDao taskQueueDao;

    @Autowired
    private OnlineVerifyService onlineVerifyService;

    @Autowired
    private RedisCenter redisCenter;

    @Resource
    private AppAuditDao appAuditDao;

    @Autowired
    private ResourceService resourceService;

    @Autowired
    private DiagnosticTaskRecordDao diagnosticTaskRecordDao;

    @Autowired
    private AssistRedisService assistRedisService;

    public List<DiagnosticAppOptionDto> listOnlineApps() {
        List<AppDesc> apps = appDao.getOnlineApps();
        if (apps == null) {
            return Collections.emptyList();
        }
        List<DiagnosticAppOptionDto> items = new ArrayList<>();
        for (AppDesc app : apps) {
            DiagnosticAppOptionDto dto = new DiagnosticAppOptionDto();
            dto.setAppId(app.getAppId());
            dto.setClusterNo(com.shcj.cache.util.AppClusterNoSupport.displayClusterNo(app));
            dto.setType(app.getType());
            dto.setAppName(app.getName());
            dto.setTypeDesc(app.getTypeDesc());
            SystemResource ver = resourceService.getResourceById(app.getVersionId());
            dto.setVersionName(ver != null ? ver.getName() : "");
            items.add(dto);
        }
        return items;
    }

    public List<DiagnosticInstanceDto> getAppInstances(long appId) {
        AppDesc appDesc = appService.getByAppId(appId);
        if (appDesc == null) {
            return Collections.emptyList();
        }
        List<InstanceInfo> instances = appService.getAppOnlineInstanceInfo(appId);
        if (instances == null) {
            return Collections.emptyList();
        }
        List<DiagnosticInstanceDto> items = new ArrayList<>();
        for (InstanceInfo inst : instances) {
            DiagnosticInstanceDto dto = new DiagnosticInstanceDto();
            dto.setId(inst.getId());
            dto.setIp(inst.getIp());
            dto.setPort(inst.getPort());
            dto.setHostPort(inst.getHostPort());
            items.add(dto);
        }
        return items;
    }

    public List<DiagnosticTaskDto> listTasks(Long appId, String tabTag, Long parentTaskId, Long auditId, Integer status) {
        int type = DiagnosticTypeEnum.getDescKey(tabTag);
        if (type < 0 && !"onlineVerify".equals(tabTag) && !"redis-cli".equals(tabTag)) {
            return Collections.emptyList();
        }
        Long queryAppId = appId;
        if (appId != null && appId >= 0) {
            queryAppId = appService.resolveAppId(appId);
            if (queryAppId == null) {
                return Collections.emptyList();
            }
        }
        List<DiagnosticTaskRecord> records = diagnosticToolService.getDiagnosticTaskRecords(
                queryAppId, parentTaskId, auditId, type >= 0 ? type : null, status);
        if (records == null) {
            records = Collections.emptyList();
        }
        List<DiagnosticTaskDto> items = new ArrayList<>();
        Map<Long, AppDesc> appCache = new HashMap<>();
        for (DiagnosticTaskRecord r : records) {
            items.add(toTaskDto(r, resolveApp(appCache, r.getAppId())));
        }
        // 父任务在拆分出各节点子任务之前，diagnostic_task_record 里没有任何行；
        // 若只查该表，刚提交的任务在页面刷新后会凭空消失。这里补上仍在排队/运行的父任务。
        items.addAll(0, listPendingParentTasks(type, queryAppId, parentTaskId, auditId, status, records, appCache));
        return items;
    }

    /**
     * 把 task_queue 中尚未结束、且还没有产出任何子任务记录的父任务，转成"执行中"的占位行。
     */
    private List<DiagnosticTaskDto> listPendingParentTasks(int type, Long queryAppId, Long parentTaskId,
            Long auditId, Integer status, List<DiagnosticTaskRecord> records, Map<Long, AppDesc> appCache) {
        DiagnosticTypeEnum typeEnum = DiagnosticTypeEnum.getByType(type);
        if (typeEnum == null) {
            return Collections.emptyList();
        }
        // 占位行的状态固定为 0（进行中），按其它状态过滤时不应出现
        if (status != null && status != DIAGNOSTIC_STATUS_RUNNING) {
            return Collections.emptyList();
        }
        List<TaskQueue> unfinished;
        try {
            unfinished = taskQueueDao.getUnfinishedByClassName(typeEnum.getParentTaskClassName(), queryAppId);
        } catch (Exception e) {
            // 占位行只是体验优化，查询失败不应影响主列表
            return Collections.emptyList();
        }
        if (unfinished == null || unfinished.isEmpty()) {
            return Collections.emptyList();
        }
        Set<Long> startedParentIds = new HashSet<>();
        for (DiagnosticTaskRecord r : records) {
            if (r.getParentTaskId() > 0) {
                startedParentIds.add(r.getParentTaskId());
            }
        }
        List<DiagnosticTaskDto> pending = new ArrayList<>();
        for (TaskQueue task : unfinished) {
            if (startedParentIds.contains(task.getId())) {
                continue;
            }
            if (parentTaskId != null && parentTaskId > 0 && parentTaskId != task.getId()) {
                continue;
            }
            DiagnosticTaskDto dto = toPendingTaskDto(task, typeEnum, resolveApp(appCache, task.getAppId()));
            if (auditId != null && auditId > 0 && dto.getAuditId() != auditId) {
                continue;
            }
            pending.add(dto);
        }
        return pending;
    }

    private DiagnosticTaskDto toPendingTaskDto(TaskQueue task, DiagnosticTypeEnum typeEnum, AppDesc appDesc) {
        DiagnosticTaskDto dto = new DiagnosticTaskDto();
        // 子任务尚未落库，没有真实主键；用负的父任务 ID 保证前端 key 唯一且不会误当成可删除记录
        dto.setId(-task.getId());
        dto.setTaskId(0);
        dto.setParentTaskId(task.getId());
        dto.setType(typeEnum.getType());
        dto.setStatus(DIAGNOSTIC_STATUS_RUNNING);
        dto.setAppId(task.getAppId());
        if (appDesc != null) {
            dto.setClusterNo(AppClusterNoSupport.displayClusterNo(appDesc));
            dto.setAppName(appDesc.getName());
        }
        JSONObject param = parseTaskParam(task.getParam());
        dto.setAuditId(param != null ? param.getLongValue(TaskConstants.AUDIT_ID_KEY) : 0);
        dto.setNode(describePendingNodes(param));
        dto.setDiagnosticCondition("等待调度");
        dto.setRedisKey("");
        if (typeEnum == DiagnosticTypeEnum.DEL_KEY) {
            dto.setDeleteStatus(DELETE_STATUS_NOT_DELETED);
            dto.setResultCount(0);
        }
        dto.setFormatCostTime("-");
        if (task.getCreateTime() != null) {
            dto.setCreateTime(DateUtil.formatDate(task.getCreateTime(), "yyyy-MM-dd HH:mm:ss"));
        }
        return dto;
    }

    private JSONObject parseTaskParam(String param) {
        if (StringUtils.isBlank(param)) {
            return null;
        }
        try {
            return JSONObject.parseObject(param);
        } catch (Exception e) {
            return null;
        }
    }

    /** 父任务参数里存的是待拆分的节点列表，转成可读文案 */
    private String describePendingNodes(JSONObject param) {
        if (param == null) {
            return PENDING_NODE_TEXT;
        }
        try {
            List<Object> nodes = param.getJSONArray(TaskConstants.REDIS_SERVER_NODES_KEY);
            if (nodes == null || nodes.isEmpty()) {
                return PENDING_NODE_TEXT;
            }
            List<String> hostPorts = new ArrayList<>();
            for (Object node : nodes) {
                JSONObject item = (JSONObject) node;
                hostPorts.add(item.getString("ip") + ":" + item.getIntValue("port"));
            }
            return String.join(",", hostPorts);
        } catch (Exception e) {
            return PENDING_NODE_TEXT;
        }
    }

    private AppDesc resolveApp(Map<Long, AppDesc> appCache, long appId) {
        AppDesc appDesc = appCache.get(appId);
        if (appDesc == null) {
            appDesc = appService.getByAppId(appId);
            if (appDesc != null) {
                appCache.put(appId, appDesc);
            }
        }
        return appDesc;
    }

    public long submit(AppUser user, DiagnosticSubmitRequestDto req) {
        int type = DiagnosticTypeEnum.getDescKey(req.getTabTag());
        if (type < 0) {
            throw new com.shcj.cache.exception.BizException("不支持的诊断类型");
        }
        Long auditId = req.getAuditId();
        if (auditId == null) {
            AppDesc appDesc = appService.getByAppId(req.getAppId());
            AppAudit appAudit = appService.saveAppDiagnostic(appDesc, user,
                    "集群诊断任务:" + DiagnosticTypeEnum.getKeyDesc(type));
            auditId = appAudit.getId();
        }
        appAuditDao.updateAppAuditUser(auditId, AppCheckEnum.APP_ALLOCATE_RESOURCE.value(), user.getId());

        String nodes = StringUtils.defaultString(req.getNodes());
        String params = StringUtils.defaultString(req.getParams());
        long appId = req.getAppId();
        AppDesc targetApp = appService.getByAppId(appId);

        if (type == DiagnosticTypeEnum.SCAN_KEY.getType()) {
            String[] arr = params.split(",");
            String pattern = arr.length > 0 ? arr[0] : "";
            int size = arr.length > 1 ? Integer.parseInt(arr[1]) : 20;
            return taskService.addAppScanKeyTask(appId, auditId, nodes, pattern, size, 0);
        } else if (type == DiagnosticTypeEnum.DEL_KEY.getType()) {
            String[] arr = params.split(",");
            String pattern = arr.length > 0 ? arr[0] : "";
            return taskService.addAppDelKeyTask(appId, nodes, pattern, auditId, 0);
        } else if (type == DiagnosticTypeEnum.SLOT_ANALYSIS.getType()) {
            if (targetApp == null || !TypeUtil.isRedisCluster(targetApp.getType())) {
                throw new com.shcj.cache.exception.BizException("slot analysis only supports Redis Cluster apps");
            }
            return taskService.addAppSlotAnalysisTask(appId, nodes, auditId, 0);
        } else if (type == DiagnosticTypeEnum.SCAN_CLEAN.getType()) {
            Map map = JSONObject.parseObject(params, Map.class);
            return taskService.addAppScanCleanTask(appId, map, auditId, 0);
        }
        throw new com.shcj.cache.exception.BizException("不支持的诊断类型");
    }

    public DiagnosticResultDto getTaskData(String redisKey, int type, boolean err) {
        DiagnosticResultDto dto = new DiagnosticResultDto();
        if (StringUtil.isBlank(redisKey)) {
            return dto;
        }
        if (type == DiagnosticTypeEnum.SCAN_KEY.getType()) {
            List<String> result = diagnosticToolService.getScanDiagnosticData(redisKey);
            dto.setListResult(result);
            dto.setCount(result.size());
        } else if (type == DiagnosticTypeEnum.DEL_KEY.getType()) {
            List<String> result = assistRedisService.lrange(redisKey, 0, 999);
            Long total = assistRedisService.llen(redisKey);
            dto.setListResult(result);
            dto.setCount(total == null ? result.size() : (int) Math.min(total, Integer.MAX_VALUE));
        } else if (type == DiagnosticTypeEnum.SLOT_ANALYSIS.getType()) {
            Map<String, String> result = diagnosticToolService.getDiagnosticDataMap(redisKey, type, err);
            dto.setMapResult(result);
            dto.setCount(result.size());
        } else if (type == DiagnosticTypeEnum.SCAN_CLEAN.getType()) {
            List<String> result = diagnosticToolService.getScanCleanDiagnosticData(redisKey);
            dto.setListResult(result);
            dto.setCount(result.size());
        }
        return dto;
    }

    public void deleteTaskRecord(long recordId) {
        DiagnosticTaskRecord record = getTaskRecord(recordId);
        if (record.getStatus() == 0) {
            throw new BizException("任务执行中，暂不能删除");
        }
        boolean legacyDeleteRecord = record.getType() == DiagnosticTypeEnum.DEL_KEY.getType()
                && StringUtils.isBlank(record.getParam1());
        if (!legacyDeleteRecord && StringUtils.isNotBlank(record.getRedisKey())) {
            assistRedisService.del(record.getRedisKey());
        }
        if (diagnosticTaskRecordDao.deleteById(recordId) <= 0) {
            throw new BizException("删除查询记录失败");
        }
    }

    public long confirmDelete(long recordId) {
        DiagnosticTaskRecord record = getTaskRecord(recordId);
        if (record.getType() != DiagnosticTypeEnum.DEL_KEY.getType()) {
            throw new BizException("该记录不是 key 清理预览任务");
        }
        if (record.getStatus() != 1 || parseInt(record.getParam1(), -1) != DELETE_STATUS_NOT_DELETED) {
            throw new BizException("仅允许提交已完成且未删除的预览记录");
        }
        if (StringUtils.isBlank(record.getRedisKey()) || parseLong(record.getParam2(), 0) <= 0) {
            throw new BizException("预览结果为空或已失效，请重新预览");
        }

        String node = StringUtils.defaultString(record.getNode());
        int separator = node.lastIndexOf(':');
        if (separator <= 0 || separator >= node.length() - 1) {
            throw new BizException("预览记录的节点地址格式错误");
        }
        String host = node.substring(0, separator);
        int port;
        try {
            port = Integer.parseInt(node.substring(separator + 1));
        } catch (NumberFormatException e) {
            throw new BizException("预览记录的节点端口格式错误");
        }

        if (diagnosticTaskRecordDao.markDeleteStarted(recordId) <= 0) {
            throw new BizException("删除任务已提交，请勿重复操作");
        }
        try {
            return taskService.addInstanceDelKeyConfirmTask(record.getAppId(), host, port,
                    extractPattern(record.getDiagnosticCondition()), record.getAuditId(),
                    record.getParentTaskId(), recordId, record.getRedisKey());
        } catch (RuntimeException e) {
            diagnosticTaskRecordDao.updateDeleteFinished(recordId, DELETE_STATUS_NOT_DELETED,
                    parseLong(record.getParam2(), 0), 2, 0);
            throw e;
        }
    }

    public OnlineHealthCheckResultDto runOnlineVerify(String servers, String verifyType) {
        return onlineVerifyService.verifyDetailed(servers, verifyType);
    }

    public String executeCommand(long appId, String node, String command, Integer timeout) {
        if (appId < 0 || StringUtil.isBlank(node)) {
            return "error";
        }
        String host = node.split(":")[0];
        int port = Integer.parseInt(node.split(":")[1]);
        return redisCenter.executeAdminCommand(appId, host, port, command, timeout);
    }

    public List<String> sampleScan(long appId, String nodes, String pattern) {
        return diagnosticToolService.getSampleScanData(appId, nodes, pattern);
    }

    private DiagnosticTaskDto toTaskDto(DiagnosticTaskRecord r, AppDesc appDesc) {
        DiagnosticTaskDto dto = new DiagnosticTaskDto();
        dto.setId(r.getId());
        dto.setTaskId(r.getTaskId());
        dto.setParentTaskId(r.getParentTaskId());
        dto.setAuditId(r.getAuditId());
        dto.setType(r.getType());
        dto.setStatus(r.getStatus());
        dto.setAppId(r.getAppId());
        if (appDesc != null) {
            dto.setClusterNo(AppClusterNoSupport.displayClusterNo(appDesc));
            dto.setAppName(appDesc.getName());
        }
        dto.setNode(r.getNode());
        dto.setDiagnosticCondition(r.getDiagnosticCondition());
        dto.setRedisKey(r.getRedisKey());
        if (r.getType() == DiagnosticTypeEnum.DEL_KEY.getType()) {
            if (StringUtils.isNotBlank(r.getParam1())) {
                dto.setDeleteStatus(parseInt(r.getParam1(), DELETE_STATUS_NOT_DELETED));
                dto.setResultCount(parseLong(r.getParam2(), 0));
            } else if (r.getStatus() == 0) {
                dto.setDeleteStatus(DELETE_STATUS_NOT_DELETED);
                dto.setResultCount(0);
            } else {
                // 历史记录在扫描完成时已直接删除，redis_key 保存的是删除数量。
                dto.setDeleteStatus(DELETE_STATUS_DELETED);
                dto.setResultCount(parseLong(r.getRedisKey(), 0));
                dto.setRedisKey("");
            }
        }
        dto.setCost(r.getCost());
        dto.setFormatCostTime(r.getFormatCostTime());
        if (r.getCreateTime() != null) {
            dto.setCreateTime(DateUtil.formatDate(r.getCreateTime(), "yyyy-MM-dd HH:mm:ss"));
        }
        return dto;
    }

    private DiagnosticTaskRecord getTaskRecord(long recordId) {
        DiagnosticTaskRecord record = diagnosticTaskRecordDao.getById(recordId);
        if (record == null) {
            throw new BizException("查询记录不存在或已删除");
        }
        return record;
    }

    private String extractPattern(String condition) {
        String value = StringUtils.defaultString(condition);
        return value.startsWith("pattern:") ? value.substring("pattern:".length()) : value;
    }

    private int parseInt(String value, int defaultValue) {
        try {
            return Integer.parseInt(value);
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private long parseLong(String value, long defaultValue) {
        try {
            return Long.parseLong(value);
        } catch (Exception e) {
            return defaultValue;
        }
    }
}
