package com.shcj.cache.task.impl;

import com.alibaba.fastjson.JSONObject;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.shcj.cache.async.AsyncService;
import com.shcj.cache.async.AsyncThreadPoolFactory;
import com.shcj.cache.async.KeyCallable;
import com.shcj.cache.constant.AppCheckEnum;
import com.shcj.cache.constant.OperateResult;
import com.shcj.cache.dao.AppAuditDao;
import com.shcj.cache.dao.TaskQueueDao;
import com.shcj.cache.dao.TaskStepFlowDao;
import com.shcj.cache.dao.TaskStepMetaDao;
import com.shcj.cache.entity.AppAudit;
import com.shcj.cache.entity.AppDesc;
import com.shcj.cache.entity.AppUser;
import com.shcj.cache.entity.InstanceInfo;
import com.shcj.cache.task.BaseTask;
import com.shcj.cache.task.TaskService;
import com.shcj.cache.task.constant.PikaNode;
import com.shcj.cache.task.constant.TaskConstants;
import com.shcj.cache.task.constant.TaskQueueEnum.TaskErrorCodeEnum;
import com.shcj.cache.task.constant.TaskQueueEnum.TaskStatusEnum;
import com.shcj.cache.task.constant.TaskStepFlowEnum.TaskFlowStatusEnum;
import com.shcj.cache.task.constant.TaskTypeConsts;
import com.shcj.cache.task.entity.TaskQueue;
import com.shcj.cache.task.entity.*;
import com.shcj.cache.task.tasks.AppKeyAnalysisTask;
import com.shcj.cache.task.tasks.OffLineAppTask;
import com.shcj.cache.task.tasks.analysis.*;
import com.shcj.cache.task.tasks.daily.MachineExamTask;
import com.shcj.cache.task.tasks.daily.TopologyExamTask;
import com.shcj.cache.task.tasks.diagnosticTask.*;
import com.shcj.cache.task.util.AppWechatUtil;
import com.shcj.cache.task.util.SpringContextUtil;
import com.shcj.cache.util.ConstUtils;
import com.shcj.cache.util.RedisConstUtils;
import com.shcj.cache.web.enums.ExamToolEnum;
import com.shcj.cache.web.service.AppService;
import com.shcj.cache.web.util.IpUtil;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.exception.ExceptionUtils;
import org.apache.commons.lang.math.NumberUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import java.lang.reflect.Method;
import java.util.*;
import java.util.stream.Collectors;

/**
 * @author fulei
 */
@Service
public class TaskServiceImpl implements TaskService {

    private Logger logger = LoggerFactory.getLogger(TaskServiceImpl.class);

    @Autowired
    private TaskQueueDao taskQueueDao;

    @Autowired
    private TaskStepFlowDao taskStepFlowDao;

    @Autowired
    private TaskStepMetaDao taskStepMetaDao;

    @Autowired
    private AppAuditDao appAuditDao;

    @Autowired
    private SpringContextUtil springContextUtil;

    @Autowired
    private AsyncService asyncService;

    @Autowired
    private AppService appService;

    @Autowired
    private AppWechatUtil appWechatUtil;

    @Autowired
    IpUtil ipUtil;

    private int port;

    private String host;

    @PostConstruct
    public void init() {
        asyncService.assemblePool(getThreadPoolKey(), AsyncThreadPoolFactory.TASK_EXECUTE_THREAD_POOL);
        host = ipUtil.getLocalIP();
        port = ipUtil.getLocalPort();
    }

    @Override
    public void executeNewTask() {
        try {
            List<TaskQueue> newTaskQueueList = getTaskQueueList(TaskStatusEnum.NEW);
            int limit = 20;
            if (CollectionUtils.isEmpty(newTaskQueueList)) {
                logger.debug("current newTaskQueueList is empty");
                return;
            } else {
                logger.info("current newTaskQueueList size is {}", newTaskQueueList.size());
            }

            for (int i = 0; i < limit && i < newTaskQueueList.size(); i++) {
                final TaskQueue taskQueue = newTaskQueueList.get(i);
                updateTaskQueueStatus(taskQueue.getId(), TaskStatusEnum.READY);
                logger.info("task {} ready to execute", taskQueue.getId());
                String key = getThreadPoolKey() + "_" + taskQueue.getId();
                asyncService.submitFuture(getThreadPoolKey(), new KeyCallable<Boolean>(key) {
                    @Override
                    public Boolean execute() {
                        logger.info("===================={} start task {}==================",
                                Thread.currentThread().getName(), taskQueue.getId());
                        executeTask(taskQueue.getId());
                        return true;
                    }
                });
            }
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
        }
    }

    @Override
    public TaskStatusEnum executeTask(long taskId) {
        TaskStatusEnum taskStatusEnum = null;
        TaskQueue taskQueue = taskQueueDao.getById(taskId);
        if (taskQueue == null) {
            logger.error("taskId {} is null", taskId);
            taskStatusEnum = TaskStatusEnum.ABORT;
            taskQueueDao.updateStatus(taskId, taskStatusEnum.getStatus());
            return taskStatusEnum;
        }
        if (taskQueue.isSuccess()) {
            logger.error("taskId {} already success", taskId);
            return TaskStatusEnum.SUCCESS;
        }
        //todo
//        if (taskQueue.isRunning()) {
//            logger.error("taskId {} is running", taskId);
//            return TaskStatusEnum.RUNNING;
//        }
        //正在执行
        taskStatusEnum = TaskStatusEnum.RUNNING;
        taskQueueDao.updateStatus(taskId, taskStatusEnum.getStatus());
        taskQueueDao.updateStartTime(taskId, new Date());

        String executeIpPort = host + ":" + port;
        taskQueueDao.updateExecuteIpPort(taskId, executeIpPort);

        //实际是beanId
        String className = taskQueue.getClassName();
        //parse参数
        Map<String, Object> paramMap = taskQueue.getParamMap();
        String failedStepName = null;

        try {
            //任务列表
            List<TaskStepFlow> taskStepFlowList = new ArrayList<TaskStepFlow>();

            //获取任务
            BaseTask task = (BaseTask) SpringContextUtil.getBeanById(className);
            paramMap.put(TaskConstants.TASK_ID_KEY, taskQueue.getId());
            task.setParamMap(paramMap);

            //任务步骤
            List<String> stepNameList = task.getTaskSteps();

            //这块要考虑好重试的问题 @TODO
            for (int i = 0; i < stepNameList.size(); i++) {
                String stepName = stepNameList.get(i);
                TaskStepFlow oldTaskStepFlow = taskStepFlowDao.getByTaskClassStep(taskId, className, stepName);
                if (oldTaskStepFlow != null) {
                    taskStepFlowList.add(oldTaskStepFlow);
                } else {
                    TaskStepFlow taskStepFlow = new TaskStepFlow();
                    taskStepFlow.setClassName(className);
                    taskStepFlow.setStepName(stepName);
                    taskStepFlow.setOrderNo(i + 1);
                    taskStepFlow.setTaskId(taskId);
                    taskStepFlow.setStatus(TaskFlowStatusEnum.READY.getStatus());
                    Date date = new Date();
                    taskStepFlow.setStartTime(date);
                    taskStepFlow.setEndTime(date);
                    taskStepFlow.setCreateTime(date);
                    taskStepFlow.setUpdateTime(date);
                    taskStepFlowList.add(taskStepFlow);

                    taskStepFlowDao.save(taskStepFlow);
                }
            }
            // 执行任务
            TaskFlowStatusEnum taskFlowStatusEnum = null;
            for (TaskStepFlow taskStepFlow : taskStepFlowList) {
                long id = taskStepFlow.getId();
                if ((taskStepFlow.isSuccess() || taskStepFlow.isSkip()) && !TaskConstants.INIT_METHOD_KEY
                        .equals(taskStepFlow.getStepName())
                        && !shouldForceRerunKeyAnalysisStep(className, taskStepFlow.getStepName())) {
                    continue;
                }
                task.getParamMap().put(TaskConstants.TASK_STEP_FLOW_ID, id);
                try {
                    // 更新开始时间
                    taskStepFlowDao.updateStartTime(id, new Date());
                    taskStepFlowDao.updateStatus(id, TaskFlowStatusEnum.RUNNING.getStatus());
                    taskStepFlowDao.updateExecuteIpPort(id, host + ":" + port);
                    // 日志
                    MDC.put(TaskConstants.TASK_STEP_FLOW_ID, String.valueOf(id));

                    // 执行日志
                    logger.info(BaseTask.marker, "task {} {} start", taskId, taskStepFlow.getStepName());
                    // 执行任务
                    Method method = task.getClass().getDeclaredMethod(taskStepFlow.getStepName());
                    taskFlowStatusEnum = (TaskFlowStatusEnum) method.invoke(task);
                    // 执行日志
                    logger.info(BaseTask.marker, "task {} {} finish, result is {}", taskId, taskStepFlow.getStepName(),
                            taskFlowStatusEnum.getInfo());
                    taskStepFlowDao.updateStatus(id, taskFlowStatusEnum.getStatus());
                    if (taskFlowStatusEnum.equals(TaskFlowStatusEnum.ABORT)) {
                        failedStepName = taskStepFlow.getStepName();
                        appWechatUtil.noticeTaskAbort(taskId, taskStepFlow.getStepName());
                        break;
                    }
                    taskStepFlowDao.updateEndTime(id, new Date());
                    // 更新参数
                    String paramJson = JSONObject.toJSONString(task.getParamMap());
                    taskQueueDao.updateParam(taskQueue.getId(), paramJson);
                } catch (Exception e) {
                    failedStepName = taskStepFlow.getStepName();
                    taskFlowStatusEnum = TaskFlowStatusEnum.ABORT;
                    appWechatUtil.noticeTaskAbort(taskId, taskStepFlow.getStepName());
                    taskStepFlowDao.updateStatus(id, taskFlowStatusEnum.getStatus());
                    taskStepFlowDao.updateLog(id, ExceptionUtils.getFullStackTrace(e));
                    logger.error(BaseTask.marker, "{} step {} error: " + e.getMessage(), className,
                            taskStepFlow.getStepName(), e);
                    break;
                }
            }
            if (taskFlowStatusEnum != null && taskFlowStatusEnum.equals(TaskFlowStatusEnum.SUCCESS)) {
                taskQueueDao.updateEndTime(taskId, new Date());
                taskStatusEnum = TaskStatusEnum.SUCCESS;
            } else {
                taskStatusEnum = TaskStatusEnum.ABORT;
            }
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            taskStatusEnum = TaskStatusEnum.ABORT;
        }
        taskQueueDao.updateStatus(taskId, taskStatusEnum.getStatus());
        syncKeyAnalysisAuditOnFinish(taskQueue, taskStatusEnum, failedStepName);
        return taskStatusEnum;
    }

    private boolean shouldForceRerunKeyAnalysisStep(String className, String stepName) {
        if (AppKeyAnalysisTask.class.getSimpleName().equals(className)) {
            return TaskConstants.APP_KEY_ANALYSIS_FORCE_RERUN_STEPS.contains(stepName);
        }
        if (RedisServerKeyCombinedAnalysisTask.class.getSimpleName().equals(className)) {
            return TaskConstants.COMBINED_KEY_ANALYSIS_FORCE_RERUN_STEPS.contains(stepName);
        }
        return false;
    }

    private void syncKeyAnalysisAuditOnFinish(TaskQueue taskQueue, TaskStatusEnum taskStatusEnum, String failedStepName) {
        if (taskQueue == null || taskStatusEnum == null
                || !AppKeyAnalysisTask.class.getSimpleName().equals(taskQueue.getClassName())) {
            return;
        }
        long auditId = MapUtils.getLongValue(taskQueue.getParamMap(), TaskConstants.AUDIT_ID_KEY, -1);
        if (auditId <= 0) {
            return;
        }
        if (TaskStatusEnum.ABORT.equals(taskStatusEnum)) {
            appAuditDao.updateAppAudit(auditId, AppCheckEnum.APP_REJECT.value());
            String reason = StringUtils.isNotBlank(failedStepName)
                    ? "分析任务失败，失败步骤：" + failedStepName
                    : "分析任务失败";
            String stepLog = loadFailedStepLog(taskQueue.getId(), failedStepName);
            if (StringUtils.isNotBlank(stepLog)) {
                reason += "：" + stepLog;
            }
            appAuditDao.updateRefuseReason(auditId, truncateReason(reason));
        }
    }

    private String loadFailedStepLog(long taskId, String failedStepName) {
        if (StringUtils.isBlank(failedStepName)) {
            return null;
        }
        try {
            TaskStepFlow stepFlow = taskStepFlowDao.getByTaskClassStep(taskId, taskQueueClassName(failedStepName), failedStepName);
            if (stepFlow == null || StringUtils.isBlank(stepFlow.getLog())) {
                return null;
            }
            String log = stepFlow.getLog().trim();
            int nl = log.indexOf('\n');
            return nl > 0 ? log.substring(0, Math.min(nl, 180)) : log.substring(0, Math.min(log.length(), 180));
        } catch (Exception e) {
            return null;
        }
    }

    private String taskQueueClassName(String failedStepName) {
        if ("combinedKeyAnalysis".equals(failedStepName) || "checkIsRun".equals(failedStepName)) {
            return RedisServerKeyCombinedAnalysisTask.class.getSimpleName();
        }
        return AppKeyAnalysisTask.class.getSimpleName();
    }

    private String truncateReason(String reason) {
        if (reason == null || reason.length() <= 590) {
            return reason;
        }
        return reason.substring(0, 587) + "...";
    }

    @Override
    public List<TaskStepFlow> getTaskStepFlowList(long taskId) {
        try {
            return taskStepFlowDao.getTaskStepFlowList(taskId);
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    @Override
    public TaskQueue getTaskQueueById(long taskId) {
        try {
            return taskQueueDao.getById(taskId);
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            return null;
        }
    }

    @Override
    public List<TaskStepMeta> getTaskStepMetaList(String className) {
        try {
            return taskStepMetaDao.getTaskStepMetaList(className);
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    @Override
    public TaskStepFlow getCurrentTaskStepFlow(long taskId) {
        List<TaskStepFlow> taskStepFlowList = taskStepFlowDao.getTaskStepFlowList(taskId);
        if (CollectionUtils.isEmpty(taskStepFlowList)) {
            return null;
        }
        for (TaskStepFlow taskStepFlow : taskStepFlowList) {
            if (taskStepFlow.isSuccess() || taskStepFlow.isSkip()) {
                continue;
            }
            return taskStepFlow;
        }
        // 如果是空，则返回最后一步
        return taskStepFlowList.get(taskStepFlowList.size() - 1);
    }

    @Override
    public long addRedisServerInstallTask(long appId, String host, int port, int maxMemory, String version, Boolean isCluster,
                                          long parentTaskId) {
        Map<String, Object> paramMap = new HashMap<String, Object>();
        paramMap.put(TaskConstants.APPID_KEY, appId);
        paramMap.put(TaskConstants.HOST_KEY, host);
        paramMap.put(TaskConstants.PORT_KEY, port);
        paramMap.put(TaskConstants.REDIS_SERVER_MAX_MEMORY_KEY, maxMemory);
        paramMap.put(TaskConstants.VERSION_KEY, version);
        paramMap.put(TaskConstants.IS_CLUSTER_KEY, isCluster);
        String importantInfo = host + ":" + port;
        String param = JSONObject.toJSONString(paramMap);
        String className = TaskTypeConsts.REDIS_SERVER_INSTANCE_INSTALL_CLASS;
        return generateAndSaveTaskQueue(appId, className, param, importantInfo,
                parentTaskId);
    }

    @Override
    public long addPikaInstallTask(long appId, String host, int port, long parentTaskId) {
        Map<String, Object> paramMap = new HashMap<String, Object>();
        paramMap.put(TaskConstants.APPID_KEY, appId);
        paramMap.put(TaskConstants.HOST_KEY, host);
        paramMap.put(TaskConstants.PORT_KEY, port);
        String importantInfo = "pika-" + host + ":" + port;
        String param = JSONObject.toJSONString(paramMap);

        String className = null;//todo TaskConstants.PIKA_INSTANCE_INSTALL_CLASS;
        return generateAndSaveTaskQueue(appId, className, param, importantInfo,
                parentTaskId);
    }

    /*@Override
    public long addCodisServerInstallTask(long appId, String host, int port, int maxMemory, long parentTaskId) {
        Map<String, Object> paramMap = new HashMap<String, Object>();
        paramMap.put(TaskConstants.APPID_KEY, appId);
        paramMap.put(TaskConstants.HOST_KEY, host);
        paramMap.put(TaskConstants.PORT_KEY, port);
        paramMap.put(TaskConstants.REDIS_SERVER_MAX_MEMORY_KEY, maxMemory);
        String importantInfo = host + ":" + port;
        String param = JSONObject.toJSONString(paramMap);

        String className = null;//todo TaskConstants.CODIS_SERVER_INSTANCE_INSTALL_CLASS;
        return generateAndSaveTaskQueue(appId, className, param, importantInfo,
                parentTaskId);
    }*/

    @Override
    public long addRedisSentinelInstallTask(long appId, String host, int port,
                                            List<RedisServerNode> masterRedisServerNodes, int quorum, String dbVersion, long parentTaskId) {
        Map<String, Object> paramMap = new HashMap<String, Object>();
        paramMap.put(TaskConstants.APPID_KEY, appId);
        paramMap.put(TaskConstants.HOST_KEY, host);
        paramMap.put(TaskConstants.PORT_KEY, port);
        paramMap.put(TaskConstants.REDIS_SENTINEL_QUORUM_KEY, quorum);
        paramMap.put(TaskConstants.MASTER_REDIS_SERVER_NODES, masterRedisServerNodes);
        paramMap.put(TaskConstants.VERSION_KEY, dbVersion);
        String importantInfo = host + ":" + port;
        String param = JSONObject.toJSONString(paramMap);

        String className = TaskTypeConsts.REDIS_SENTINEL_INSTANCE_INSTALL_CLASS;
        return generateAndSaveTaskQueue(appId, className, param, importantInfo,
                parentTaskId);
    }

    /*@Override
    public long addRedisPortInstallTask(long appId, String redisPortHost, int redisPortHttpPort, String sourceHost,
                                        int sourcePort, String targetHost, int targetPort, long parentTaskId) {
        Map<String, Object> paramMap = new HashMap<String, Object>();
        paramMap.put(TaskConstants.APPID_KEY, appId);
        paramMap.put(TaskConstants.HOST_KEY, redisPortHost);
        paramMap.put(TaskConstants.HTTP_PORT_KEY, redisPortHttpPort);
        paramMap.put(TaskConstants.SOURCE_HOST_KEY, sourceHost);
        paramMap.put(TaskConstants.SOURCE_PORT_KEY, sourcePort);
        paramMap.put(TaskConstants.TARGET_HOST_KEY, targetHost);
        paramMap.put(TaskConstants.TARGET_PORT_KEY, targetPort);
        String importantInfo = redisPortHost + ":" + redisPortHttpPort;
        String param = JSONObject.toJSONString(paramMap);
        //todo TaskConstants.REDIS_PORT_INSTANCE_INSTALL_CLASS;
        String className = null;
        return generateAndSaveTaskQueue(appId, className, param, importantInfo,
                parentTaskId);
    }*/

    @Override
    public long addRedisMigrateToolInstallTask(String migrateToolHost, int migrateToolPort, long sourceAppId,
                                               long targetAppId, List<RedisServerNode> sourceRedisServerNodes, long parentTaskId) {
        Map<String, Object> paramMap = new HashMap<String, Object>();
        paramMap.put(TaskConstants.HOST_KEY, migrateToolHost);
        paramMap.put(TaskConstants.PORT_KEY, migrateToolPort);
        paramMap.put(TaskConstants.SOURCE_APP_ID_KEY, sourceAppId);
        paramMap.put(TaskConstants.TARGET_APP_ID_KEY, targetAppId);
        paramMap.put(TaskConstants.REDIS_SERVER_NODES_KEY, sourceRedisServerNodes);
        String importantInfo = String
                .format("%s:%s(%s->%s)", migrateToolHost, migrateToolPort, sourceAppId, targetAppId);
        String param = JSONObject.toJSONString(paramMap);
        long appId = sourceAppId;

        String className = null;//todo TaskConstants.REDIS_MIGRATE_TOOL_INSTANCE_INSTALL_CLASS;
        return generateAndSaveTaskQueue(appId, className, param, importantInfo,
                parentTaskId);
    }

    @Override
    public long addCodisProxyInstallTask(long appId, String host, int port, long parentTaskId) {
        Map<String, Object> paramMap = new HashMap<String, Object>();
        paramMap.put(TaskConstants.APPID_KEY, appId);
        paramMap.put(TaskConstants.HOST_KEY, host);
        paramMap.put(TaskConstants.PORT_KEY, port);
        String importantInfo = host + ":" + port;
        String param = JSONObject.toJSONString(paramMap);

        String className = null;//todo TaskConstants.CODIS_PROXY_INSTANCE_INSTALL_CLASS;
        return generateAndSaveTaskQueue(appId, className, param, importantInfo,
                parentTaskId);
    }

    @Override
    public long addCodisDashboardTask(long appId, String host, int port, long parentTaskId) {
        Map<String, Object> paramMap = new HashMap<String, Object>();
        paramMap.put(TaskConstants.APPID_KEY, appId);
        paramMap.put(TaskConstants.HOST_KEY, host);
        paramMap.put(TaskConstants.PORT_KEY, port);
        String importantInfo = host + ":" + port;
        String param = JSONObject.toJSONString(paramMap);

        String className = null;//todo TaskConstants.CODIS_DASHBOARD_INSTANCE_INSTALL_CLASS;
        return generateAndSaveTaskQueue(appId, className, param, importantInfo,
                parentTaskId);
    }

    @Override
    public long addNutCrackerInstallTask(long appId, String host, int port,
                                         List<RedisServerNode> masterRedisServerNodes, long parentTaskId) {
        Map<String, Object> paramMap = new HashMap<String, Object>();
        paramMap.put(TaskConstants.APPID_KEY, appId);
        paramMap.put(TaskConstants.HOST_KEY, host);
        paramMap.put(TaskConstants.PORT_KEY, port);
        paramMap.put(TaskConstants.MASTER_REDIS_SERVER_NODES, masterRedisServerNodes);
        String importantInfo = host + ":" + port;
        String param = JSONObject.toJSONString(paramMap);

        String className = null;//todo TaskConstants.NUT_CRACKER_INSTANCE_INSTALL_CLASS;
        return generateAndSaveTaskQueue(appId, className, param, importantInfo,
                parentTaskId);
    }

    @Override
    public long addTwemproxyPikaTask(long appId, long auditId, int maxMemory, List<String> pikaMachineList,
                                     List<String> redisSentinelMachineList, List<String> nutCrackerMachineList, int masterPerMachine,
                                     int sentinelPerMachine, int nutCrackerPerMachine, Boolean isNeedFlushZkConfig, long parentTaskId) {
        Map<String, Object> paramMap = new HashMap<String, Object>();
        paramMap.put(TaskConstants.APPID_KEY, appId);
        paramMap.put(TaskConstants.AUDIT_ID_KEY, auditId);
        paramMap.put(TaskConstants.REDIS_SERVER_MAX_MEMORY_KEY, maxMemory);
        paramMap.put(TaskConstants.PIKA_MACHINE_LIST_KEY, pikaMachineList);
        paramMap.put(TaskConstants.REDIS_SENTINEL_MACHINE_LIST_KEY, redisSentinelMachineList);
        paramMap.put(TaskConstants.NUT_CRACKER_MACHINE_LIST_KEY, nutCrackerMachineList);
        paramMap.put(TaskConstants.MASTER_PER_MACHINE_KEY, masterPerMachine);
        paramMap.put(TaskConstants.SENTINEL_PER_MACHINE_KEY, sentinelPerMachine);
        paramMap.put(TaskConstants.NUT_CRACKER_PER_MACHINE_KEY, nutCrackerPerMachine);
        paramMap.put(TaskConstants.IS_NEED_FLUSH_ZK_CONFIG_KEY, isNeedFlushZkConfig);
        AppDesc appDesc = appService.getByAppId(appId);
        String importantInfo = appDesc.getName() + "-pika";//appDesc.getFullName() + "-pika"
        String param = JSONObject.toJSONString(paramMap);
        String className = null;//todo TaskConstants.TWEM_PROXY_PIKA_INSTALL_CLASS;
        return generateAndSaveTaskQueue(appId, className, param, importantInfo,
                parentTaskId);
    }

    @Override
    public long addTwemproxyAppTask(long appId, long auditId, int maxMemory, List<String> redisServerMachineList,
                                    List<String> redisSentinelMachineList, List<String> nutCrackerMachineList, int masterPerMachine,
                                    int sentinelPerMachine, int nutCrackerPerMachine, Boolean isNeedFlushZkConfig, String version,
                                    long parentTaskId) {
        Map<String, Object> paramMap = new HashMap<String, Object>();
        paramMap.put(TaskConstants.APPID_KEY, appId);
        paramMap.put(TaskConstants.AUDIT_ID_KEY, auditId);
        paramMap.put(TaskConstants.REDIS_SERVER_MAX_MEMORY_KEY, maxMemory);
        paramMap.put(TaskConstants.REDIS_MASTER_MACHINE_LIST_KEY, redisServerMachineList);
        paramMap.put(TaskConstants.REDIS_SENTINEL_MACHINE_LIST_KEY, redisSentinelMachineList);
        paramMap.put(TaskConstants.NUT_CRACKER_MACHINE_LIST_KEY, nutCrackerMachineList);
        paramMap.put(TaskConstants.MASTER_PER_MACHINE_KEY, masterPerMachine);
        paramMap.put(TaskConstants.SENTINEL_PER_MACHINE_KEY, sentinelPerMachine);
        paramMap.put(TaskConstants.NUT_CRACKER_PER_MACHINE_KEY, nutCrackerPerMachine);
        paramMap.put(TaskConstants.IS_NEED_FLUSH_ZK_CONFIG_KEY, isNeedFlushZkConfig);
        paramMap.put(TaskConstants.VERSION_KEY, version);
        AppDesc appDesc = appService.getByAppId(appId);
        String importantInfo = appDesc.getName();
        String param = JSONObject.toJSONString(paramMap);

        String className = null;//todo TaskConstants.TWEM_PROXY_APP_INSTALL_CLASS;
        return generateAndSaveTaskQueue(appId, className, param, importantInfo,
                parentTaskId);
    }

    @Override
    public long addRedisStandaloneAppTask(long appId, long appAuditId, int maxMemory, List<String> redisMasterMachineList, int masterPerMachine, String dbVersion, String moduleInfos, long parentTaskId) {
        Map<String, Object> paramMap = new HashMap<String, Object>();
        paramMap.put(TaskConstants.APPID_KEY, appId);
        paramMap.put(TaskConstants.AUDIT_ID_KEY, appAuditId);
        paramMap.put(TaskConstants.REDIS_SERVER_MAX_MEMORY_KEY, maxMemory);
        paramMap.put(TaskConstants.REDIS_MASTER_MACHINE_LIST_KEY, redisMasterMachineList);
        paramMap.put(TaskConstants.MASTER_PER_MACHINE_KEY, masterPerMachine);
        paramMap.put(TaskConstants.VERSION_KEY, dbVersion);
        paramMap.put(TaskConstants.MODULE_KEY, moduleInfos);
        AppDesc appDesc = appService.getByAppId(appId);
        String importantInfo = appDesc.getName() + "-redis-standalone";
        String param = JSONObject.toJSONString(paramMap);

        String className = TaskTypeConsts.REDIS_STANDALONE_APP_DEPLOY_CLASS;
        long taskId = generateAndSaveTaskQueue(appId, className, param, importantInfo,
                parentTaskId);
        appAuditDao.updateTaskId(appAuditId, taskId);
        return taskId;
    }

    @Override
    public long addRedisClusterAppTask(long appId, long appAuditId, int maxMemory, List<String> redisMasterMachineList, List<String> redisSlaveMachineList, String dbVersion, String moduleinfos, long parentTaskId) {
        //错开，防止与主节点同机器
        redisSlaveMachineList = new ArrayList<>(redisSlaveMachineList);
        redisSlaveMachineList.add(redisSlaveMachineList.get(0));
        redisSlaveMachineList.remove(0);

        Map<String, Object> paramMap = new HashMap<String, Object>();
        paramMap.put(TaskConstants.APPID_KEY, appId);
        paramMap.put(TaskConstants.AUDIT_ID_KEY, appAuditId);
        paramMap.put(TaskConstants.REDIS_SERVER_MAX_MEMORY_KEY, maxMemory);
        paramMap.put(TaskConstants.REDIS_MASTER_MACHINE_LIST_KEY, redisMasterMachineList);
        paramMap.put(TaskConstants.REDIS_SLAVE_MACHINE_LIST_KEY, redisSlaveMachineList);
        paramMap.put(TaskConstants.VERSION_KEY, dbVersion.replaceAll(RedisConstUtils.REDIS_VERSION_PREFIX, ""));
        paramMap.put(TaskConstants.MODULE_KEY, moduleinfos);
        AppDesc appDesc = appService.getByAppId(appId);
        String importantInfo = appDesc.getName() + "-redis-cluster";
        String param = JSONObject.toJSONString(paramMap);

        String className = TaskTypeConsts.REDIS_CLUSTER_APP_DEPLOY_CLASS;
        long taskId = generateAndSaveTaskQueue(appId, className, param, importantInfo,
                parentTaskId);
        appAuditDao.updateTaskId(appAuditId, taskId);
        return taskId;
    }

    @Override
    public long addRedisSentinelAppTask(long appId, long appAuditId, int maxMemory, List<String> redisMasterMachineList, List<String> redisSlaveMachineList,
                                        List<String> redisSentinelMachineList, String dbVersion, String moduleInfos, long parentTaskId) {
        Map<String, Object> paramMap = new HashMap<String, Object>();
        paramMap.put(TaskConstants.APPID_KEY, appId);
        paramMap.put(TaskConstants.AUDIT_ID_KEY, appAuditId);
        paramMap.put(TaskConstants.REDIS_SERVER_MAX_MEMORY_KEY, maxMemory);
        paramMap.put(TaskConstants.REDIS_MASTER_MACHINE_LIST_KEY, redisMasterMachineList);
        paramMap.put(TaskConstants.REDIS_SLAVE_MACHINE_LIST_KEY, redisSlaveMachineList);
        paramMap.put(TaskConstants.REDIS_SENTINEL_MACHINE_LIST_KEY, redisSentinelMachineList);
        paramMap.put(TaskConstants.VERSION_KEY, dbVersion);
        paramMap.put(TaskConstants.MODULE_KEY, moduleInfos);
        AppDesc appDesc = appService.getByAppId(appId);
        String importantInfo = appDesc.getName();
        String param = JSONObject.toJSONString(paramMap);

        String className = TaskTypeConsts.REDIS_SENTINEL_APP_DEPLOY_CLASS;
        long taskId = generateAndSaveTaskQueue(appId, className, param, importantInfo,
                parentTaskId);
        appAuditDao.updateTaskId(appAuditId, taskId);
        return taskId;
    }

    @Override
    public long addPikaSentinelAppTask(long appId, long appAuditId, int maxMemory, List<String> pikaMachineList,
                                       List<String> redisSentinelMachineList, int masterPerMachine, int sentinelPerMachine, long parentTaskId) {
        Map<String, Object> paramMap = new HashMap<String, Object>();
        paramMap.put(TaskConstants.APPID_KEY, appId);
        paramMap.put(TaskConstants.AUDIT_ID_KEY, appAuditId);
        paramMap.put(TaskConstants.REDIS_SERVER_MAX_MEMORY_KEY, maxMemory);
        paramMap.put(TaskConstants.PIKA_MACHINE_LIST_KEY, pikaMachineList);
        paramMap.put(TaskConstants.REDIS_SENTINEL_MACHINE_LIST_KEY, redisSentinelMachineList);
        paramMap.put(TaskConstants.MASTER_PER_MACHINE_KEY, masterPerMachine);
        paramMap.put(TaskConstants.SENTINEL_PER_MACHINE_KEY, sentinelPerMachine);
        AppDesc appDesc = appService.getByAppId(appId);
        String importantInfo = appDesc.getName() + "-pika-sentinel";
        String param = JSONObject.toJSONString(paramMap);

        String className = null;//todo TaskConstants.PIKA_SENTINEL_APP_INSTALL_CLASS;
        return generateAndSaveTaskQueue(appId, className, param, importantInfo,
                parentTaskId);
    }

    @Override
    public long addNutCrackerScaleOutTask(long appId, List<String> nutCrackerMachineList, int nutCrackerPerMachine,
                                          long parentTaskId) {
        Map<String, Object> paramMap = new HashMap<String, Object>();
        paramMap.put(TaskConstants.APPID_KEY, appId);
        paramMap.put(TaskConstants.NUT_CRACKER_MACHINE_LIST_KEY, nutCrackerMachineList);
        paramMap.put(TaskConstants.NUT_CRACKER_PER_MACHINE_KEY, nutCrackerPerMachine);
        AppDesc appDesc = appService.getByAppId(appId);
        String importantInfo = appDesc.getName();
        String param = JSONObject.toJSONString(paramMap);

        String className = null;//todo TaskConstants.NUT_CRACKER_SCALE_OUT_CLASS;
        return generateAndSaveTaskQueue(appId, className, param, importantInfo,
                parentTaskId);
    }

    @Override
    public long addNutCrackerListOfflineTask(long appId, List<NutCrackerNode> nutCrackerNodes, long parentTaskId) {
        Map<String, Object> paramMap = new HashMap<String, Object>();
        paramMap.put(TaskConstants.APPID_KEY, appId);
        paramMap.put(TaskConstants.NUT_CRACKER_NODES_KEY, nutCrackerNodes);
        String param = JSONObject.toJSONString(paramMap);
        AppDesc appDesc = appService.getByAppId(appId);
        String importantInfo = appDesc.getName();

        String className = null;//todo TaskConstants.NUT_CRACKER_LIST_OFFLINE_CLASS;
        return generateAndSaveTaskQueue(appId, className, param, importantInfo,
                parentTaskId);
    }

    @Override
    public long addRedisSentinelListOfflineTask(long appId, List<RedisSentinelNode> redisSentinelNodes,
                                                long parentTaskId) {
        Map<String, Object> paramMap = new HashMap<String, Object>();
        paramMap.put(TaskConstants.APPID_KEY, appId);
        paramMap.put(TaskConstants.REDIS_SENTINEL_NODES_KEY, redisSentinelNodes);
        String param = JSONObject.toJSONString(paramMap);
        AppDesc appDesc = appService.getByAppId(appId);
        String importantInfo = appDesc.getName();
        String className = null;//todo TaskConstants.REDIS_SENTINEL_LIST_OFFLINE_CLASS;
        return generateAndSaveTaskQueue(appId, className, param, importantInfo,
                parentTaskId);
    }

    @Override
    public long addRedisSlaveServerOfflineTask(long appId, List<RedisServerNode> redisServerNodes, long parentTaskId) {
        Map<String, Object> paramMap = new HashMap<String, Object>();
        paramMap.put(TaskConstants.APPID_KEY, appId);
        paramMap.put(TaskConstants.REDIS_SERVER_NODES_KEY, redisServerNodes);
        String param = JSONObject.toJSONString(paramMap);
        AppDesc appDesc = appService.getByAppId(appId);
        String importantInfo = appDesc.getName();
        String className = null;//todo TaskConstants.REDIS_SLAVE_SERVER_OFFLINE_CLASS;
        return generateAndSaveTaskQueue(appId, className, param, importantInfo,
                parentTaskId);
    }

    @Override
    public long addPikaSlaveOfflineTask(long appId, List<PikaNode> pikaNodes, long parentTaskId) {
        Map<String, Object> paramMap = new HashMap<String, Object>();
        paramMap.put(TaskConstants.APPID_KEY, appId);
        paramMap.put(TaskConstants.PIKA_NODES_KEY, pikaNodes);
        String param = JSONObject.toJSONString(paramMap);
        AppDesc appDesc = appService.getByAppId(appId);
        String importantInfo = appDesc.getName();
        String className = null;//todo TaskConstants.PIKA_SLAVE_OFFLINE_CLASS;
        return generateAndSaveTaskQueue(appId, className, param, importantInfo,
                parentTaskId);
    }

    @Override
    public long addRedisSentinelAddTask(long appId, List<String> redisSentinelMachineList, String dbVersion,
                                        long parentTaskId) {
        Map<String, Object> paramMap = new HashMap<String, Object>();
        paramMap.put(TaskConstants.APPID_KEY, appId);
        paramMap.put(TaskConstants.REDIS_SENTINEL_MACHINE_LIST_KEY, redisSentinelMachineList);
        paramMap.put(TaskConstants.VERSION_KEY, dbVersion);
        String param = JSONObject.toJSONString(paramMap);
        AppDesc appDesc = appService.getByAppId(appId);

        String importantInfo = appDesc.getName();
        String className = null;//todo TaskConstants.PIKA_SLAVE_OFFLINE_CLASS;
        return generateAndSaveTaskQueue(appId, className, param, importantInfo,
                parentTaskId);
    }

    @Override
    public long addTwemproxyOfflineTask(long appId, long auditId, long parentTaskId) {
        Map<String, Object> paramMap = new HashMap<String, Object>();
        paramMap.put(TaskConstants.APPID_KEY, appId);
        paramMap.put(TaskConstants.AUDIT_ID_KEY, auditId);
        String param = JSONObject.toJSONString(paramMap);
        AppDesc appDesc = appService.getByAppId(appId);
        String importantInfo = appDesc.getName();
        String className = null;//todo TaskConstants.TWEMPROXY_OFFLINE_CLASS;
        return generateAndSaveTaskQueue(appId, className, param, importantInfo,
                parentTaskId);
    }

    @Override
    public long addRedisSentinelAppOfflineTask(long appId, long auditId, long parentTaskId) {
        Map<String, Object> paramMap = new HashMap<String, Object>();
        paramMap.put(TaskConstants.APPID_KEY, appId);
        paramMap.put(TaskConstants.AUDIT_ID_KEY, auditId);
        String param = JSONObject.toJSONString(paramMap);
        AppDesc appDesc = appService.getByAppId(appId);
        String importantInfo = appDesc.getName();
        String className = null;//todo TaskConstants.REDIS_SENTINEL_APP_OFFLINE_CLASS;
        return generateAndSaveTaskQueue(appId, className, param, importantInfo,
                parentTaskId);
    }

    @Override
    public long addPikaSentinelAppOfflineTask(long appId, long auditId, long parentTaskId) {
        Map<String, Object> paramMap = new HashMap<String, Object>();
        paramMap.put(TaskConstants.APPID_KEY, appId);
        paramMap.put(TaskConstants.AUDIT_ID_KEY, auditId);
        String param = JSONObject.toJSONString(paramMap);
        AppDesc appDesc = appService.getByAppId(appId);
        String importantInfo = appDesc.getName();
        String className = null;//todo TaskConstants.PIKA_SENTINEL_APP_OFFLINE_CLASS;
        return generateAndSaveTaskQueue(appId, className, param, importantInfo,
                parentTaskId);
    }

    @Override
    public long addMemcacheClusterOfflineTask(long appId, long auditId, long parentTaskId) {
        Map<String, Object> paramMap = new HashMap<String, Object>();
        paramMap.put(TaskConstants.APPID_KEY, appId);
        paramMap.put(TaskConstants.AUDIT_ID_KEY, auditId);
        String param = JSONObject.toJSONString(paramMap);
        AppDesc appDesc = appService.getByAppId(appId);
        String importantInfo = appDesc.getName();
        String className = null;//todo TaskConstants.MEMCACHE_CLUSTER_OFFLINE_CLASS;
        return generateAndSaveTaskQueue(appId, className, param, importantInfo,
                parentTaskId);
    }

    @Override
    public long addTwemproxyPikaOfflineTask(long appId, long auditId, long parentTaskId) {
        Map<String, Object> paramMap = new HashMap<String, Object>();
        paramMap.put(TaskConstants.APPID_KEY, appId);
        paramMap.put(TaskConstants.AUDIT_ID_KEY, auditId);
        String param = JSONObject.toJSONString(paramMap);
        AppDesc appDesc = appService.getByAppId(appId);

        String importantInfo = appDesc.getName() + "-pika";
        String className = null;//todo TaskConstants.TWEMPROXY_PIKA_OFFLINE_CLASS;
        return generateAndSaveTaskQueue(appId, className, param, importantInfo,
                parentTaskId);
    }

    @Override
    public long addAppConfigFlushZkTask(long appId, Boolean appIsNew, long parentTaskId) {
        Map<String, Object> paramMap = new HashMap<String, Object>();
        paramMap.put(TaskConstants.APPID_KEY, appId);
        paramMap.put(TaskConstants.APP_IS_NEW_KEY, appIsNew);
        String param = JSONObject.toJSONString(paramMap);
        AppDesc appDesc = appService.getByAppId(appId);

        String importantInfo = appDesc.getName();
        String className = null;//todo TaskConstants.APP_CONFIG_FLUSH_ZK_CLASS;
        return generateAndSaveTaskQueue(appId, className, param, importantInfo,
                parentTaskId);
    }

    @Override
    public long addTwemproxyToTwemproxyTask(long sourceAppId, long targetAppId, long appAuditId, boolean isScaleOut,
                                            long parentTaskId) {
        Map<String, Object> paramMap = new HashMap<String, Object>();
        paramMap.put(TaskConstants.SOURCE_APP_ID_KEY, sourceAppId);
        paramMap.put(TaskConstants.TARGET_APP_ID_KEY, targetAppId);
        paramMap.put(TaskConstants.IS_SCALE_OUT_KEY, isScaleOut);
        paramMap.put(TaskConstants.AUDIT_ID_KEY, appAuditId);
        String importantInfo = String.format("%s->%s", sourceAppId, targetAppId);
        String param = JSONObject.toJSONString(paramMap);
        long appId = sourceAppId;

        String className = null;//todo TaskConstants.TWEMPROXY_TO_TWEMPROXY_CLASS;
        return generateAndSaveTaskQueue(appId, className, param, importantInfo,
                parentTaskId);
    }

    @Override
    public long addTwemproxyToTwemproxyTaskV2(long sourceAppId, long targetAppId, long appAuditId, boolean isScaleOut,
                                              boolean isOnlyMigrate, long parentTaskId) {
        AppDesc appDesc = appService.getByAppId(sourceAppId);

        Map<String, Object> paramMap = new HashMap<String, Object>();
        paramMap.put(TaskConstants.SOURCE_APP_ID_KEY, sourceAppId);
        paramMap.put(TaskConstants.TARGET_APP_ID_KEY, targetAppId);
        paramMap.put(TaskConstants.IS_SCALE_OUT_KEY, isScaleOut);
        paramMap.put(TaskConstants.IS_ONLY_MIGRATE_KEY, isOnlyMigrate);
        paramMap.put(TaskConstants.AUDIT_ID_KEY, appAuditId);
        String param = JSONObject.toJSONString(paramMap);
        String importantInfo = String.format("%s->%s(%s)", sourceAppId, targetAppId, appDesc.getName());
        long appId = sourceAppId;
        String className = null;//todo TaskConstants.TWEMPROXY_TO_TWEMPROXY_V2_CLASS;
        return generateAndSaveTaskQueue(appId, className, param, importantInfo,
                parentTaskId);
    }

    @Override
    public long addRemoveRedisMigrateToolTask(String host, int port, long sourceAppId,
                                              long targetAppId, long parentTaskId) {
        Map<String, Object> paramMap = new HashMap<String, Object>();
        paramMap.put(TaskConstants.HOST_KEY, host);
        paramMap.put(TaskConstants.PORT_KEY, port);
        paramMap.put(TaskConstants.SOURCE_APP_ID_KEY, sourceAppId);
        paramMap.put(TaskConstants.TARGET_APP_ID_KEY, targetAppId);
        String importantInfo = String.format("%s:%s(%s->%s)", host, port, sourceAppId, targetAppId);
        String param = JSONObject.toJSONString(paramMap);

        long appId = sourceAppId;
        String className = null;//todo TaskConstants.REDIS_MIGRATE_TOOL_REMOVE_CLASS;
        return generateAndSaveTaskQueue(appId, className, param, importantInfo,
                parentTaskId);
    }

    @Override
    public long addRedisServerStopTask(long appId, String host, int port, long parentTaskId) {
        Map<String, Object> paramMap = new HashMap<String, Object>();
        paramMap.put(TaskConstants.APPID_KEY, appId);
        paramMap.put(TaskConstants.HOST_KEY, host);
        paramMap.put(TaskConstants.PORT_KEY, port);
        String importantInfo = host + ":" + port;
        String param = JSONObject.toJSONString(paramMap);

        String className = null;//todo TaskConstants.REDIS_SERVER_STOP_CLASS;
        return generateAndSaveTaskQueue(appId, className, param, importantInfo,
                parentTaskId);
    }

    @Override
    public long addMemcacheStopTask(long appId, String host, int port, long parentTaskId) {
        Map<String, Object> paramMap = new HashMap<String, Object>();
        paramMap.put(TaskConstants.APPID_KEY, appId);
        paramMap.put(TaskConstants.HOST_KEY, host);
        paramMap.put(TaskConstants.PORT_KEY, port);
        String importantInfo = host + ":" + port;
        String param = JSONObject.toJSONString(paramMap);

        String className = null;//todo TaskConstants.MEMCACHE_STOP_CLASS;
        return generateAndSaveTaskQueue(appId, className, param, importantInfo,
                parentTaskId);
    }

    @Override
    public long addRedisServerStartTask(long appId, String host, int port, long parentTaskId) {
        Map<String, Object> paramMap = new HashMap<String, Object>();
        paramMap.put(TaskConstants.APPID_KEY, appId);
        paramMap.put(TaskConstants.HOST_KEY, host);
        paramMap.put(TaskConstants.PORT_KEY, port);
        String importantInfo = host + ":" + port;
        String param = JSONObject.toJSONString(paramMap);

        String className = null;//todo TaskConstants.REDIS_SERVER_START_CLASS;
        return generateAndSaveTaskQueue(appId, className, param, importantInfo,
                parentTaskId);
    }

    @Override
    public long addRedisServerFlushDataTask(long appId, String host, int port, long parentTaskId) {
        Map<String, Object> paramMap = new HashMap<String, Object>();
        paramMap.put(TaskConstants.APPID_KEY, appId);
        paramMap.put(TaskConstants.HOST_KEY, host);
        paramMap.put(TaskConstants.PORT_KEY, port);
        String importantInfo = host + ":" + port;
        String param = JSONObject.toJSONString(paramMap);

        String className = null;//todo TaskConstants.REDIS_SERVER_FLUSH_CLASS;
        return generateAndSaveTaskQueue(appId, className, param, importantInfo,
                parentTaskId);
    }

    @Override
    public long addRedisServerKeyTypeAnalysisTask(long appId, long auditId, String host, int port, long parentTaskId) {
        Map<String, Object> paramMap = new HashMap<String, Object>();
        paramMap.put(TaskConstants.APPID_KEY, appId);
        paramMap.put(TaskConstants.AUDIT_ID_KEY, auditId);
        paramMap.put(TaskConstants.HOST_KEY, host);
        paramMap.put(TaskConstants.PORT_KEY, port);
        String importantInfo = host + ":" + port;
        String param = JSONObject.toJSONString(paramMap);

        String className = RedisServerKeyTypeAnalysisTask.class.getSimpleName();
        return generateAndSaveTaskQueue(appId, className, param, importantInfo,
                parentTaskId);
    }

    @Override
    public long addRedisServerCombinedKeyAnalysisTask(long appId, long auditId, String host, int port,
            long parentTaskId, long bigKeyStringBytes, long bigKeyCollectionElements) {
        Map<String, Object> paramMap = new HashMap<String, Object>();
        paramMap.put(TaskConstants.APPID_KEY, appId);
        paramMap.put(TaskConstants.AUDIT_ID_KEY, auditId);
        paramMap.put(TaskConstants.HOST_KEY, host);
        paramMap.put(TaskConstants.PORT_KEY, port);
        paramMap.put(TaskConstants.BIG_KEY_STRING_BYTES_KEY, bigKeyStringBytes);
        paramMap.put(TaskConstants.BIG_KEY_COLLECTION_ELEMENTS_KEY, bigKeyCollectionElements);
        String importantInfo = host + ":" + port;
        String param = JSONObject.toJSONString(paramMap);
        String className = RedisServerKeyCombinedAnalysisTask.class.getSimpleName();
        return generateAndSaveTaskQueue(appId, className, param, importantInfo, parentTaskId);
    }

    @Override
    public long addRedisServerKeyTtlAnalysisTask(long appId, long auditId, String host, int port, long parentTaskId) {
        Map<String, Object> paramMap = new HashMap<String, Object>();
        paramMap.put(TaskConstants.APPID_KEY, appId);
        paramMap.put(TaskConstants.AUDIT_ID_KEY, auditId);
        paramMap.put(TaskConstants.HOST_KEY, host);
        paramMap.put(TaskConstants.PORT_KEY, port);
        String importantInfo = host + ":" + port;
        String param = JSONObject.toJSONString(paramMap);

        String className = RedisServerKeyTtlAnalysisTask.class.getSimpleName();
        return generateAndSaveTaskQueue(appId, className, param, importantInfo,
                parentTaskId);
    }

    @Override
    public long addRedisServerKeyValueAnalysisTask(long appId, long auditId, String host, int port,
                                                   long parentTaskId) {
        Map<String, Object> paramMap = new HashMap<String, Object>();
        paramMap.put(TaskConstants.APPID_KEY, appId);
        paramMap.put(TaskConstants.AUDIT_ID_KEY, auditId);
        paramMap.put(TaskConstants.HOST_KEY, host);
        paramMap.put(TaskConstants.PORT_KEY, port);
        String importantInfo = host + ":" + port;
        String param = JSONObject.toJSONString(paramMap);

        String className = RedisServerKeyValueAnalysisTask.class.getSimpleName();
        return generateAndSaveTaskQueue(appId, className, param, importantInfo,
                parentTaskId);
    }

    @Override
    @Transactional
    public long addAppKeyAnalysisTask(long appId, long auditId, long parentTaskId) {
        return addAppKeyAnalysisTask(appId, auditId, parentTaskId,
                ConstUtils.DEFAULT_STRING_MAX_LENGTH, ConstUtils.DEFAULT_HASH_MAX_LENGTH);
    }

    @Override
    @Transactional
    public long addAppKeyAnalysisTask(long appId, long auditId, long parentTaskId,
            long bigKeyStringBytes, long bigKeyCollectionElements) {
        Map<String, Object> paramMap = new HashMap<String, Object>();
        paramMap.put(TaskConstants.APPID_KEY, appId);
        paramMap.put(TaskConstants.AUDIT_ID_KEY, auditId);
        paramMap.put(TaskConstants.BIG_KEY_STRING_BYTES_KEY, bigKeyStringBytes);
        paramMap.put(TaskConstants.BIG_KEY_COLLECTION_ELEMENTS_KEY, bigKeyCollectionElements);

        AppDesc appDesc = appService.getByAppId(appId);
        String importantInfo = appDesc.getName();

        String className = AppKeyAnalysisTask.class.getSimpleName();
        AppAudit appAudit = appAuditDao.getAppAudit(auditId);

        String nodeInfos = StringUtils.isNotBlank(appAudit.getParam1()) ? appAudit.getParam1()
                : appService.listDefaultKeyAnalysisInstances(appId).stream()
                .map(InstanceInfo::getHostPort)
                .collect(Collectors.joining(","));
        paramMap.put(TaskConstants.REDIS_SERVER_NODES_KEY, JSONObject.toJSONString(parseNodeInfo(nodeInfos)));
//        if (StringUtils.isNotBlank(nodeInfo)) {
//            paramMap.put(TaskConstants.REDIS_SERVER_NODES_KEY, JSONObject.toJSONString(parseNodeInfo(nodeInfo)));
//            logger.info("node-analysis-task: {}", nodeInfo);
//        }

        appAuditDao.updateAppAudit(auditId, AppCheckEnum.APP_ALLOCATE_RESOURCE.value());

        String param = JSONObject.toJSONString(paramMap);
        long taskId = generateAndSaveTaskQueue(appId, className, param, importantInfo,
                parentTaskId);
        appAuditDao.updateTaskId(auditId, taskId);
        return taskId;
    }

    private static List<RedisServerNode> parseNodeInfo(String nodeInfo) {
        List<RedisServerNode> list = Lists.newArrayList();
        String[] nodeList = nodeInfo.split(",");
        for (String node : nodeList) {
            RedisServerNode redisNode = new RedisServerNode();
            String[] array = node.replace("{", "").replace("}", "").split(":");
            if (array.length != 2) {
                throw new RuntimeException(nodeInfo + " format error");
            }
            redisNode.setIp(array[0]);
            redisNode.setPort(NumberUtils.toInt(array[1]));
            list.add(redisNode);
        }
        return list;
    }

    @Override
    public long addTwemproxyFlushAllDataTask(long appId, long auditId, long parentTaskId) {
        Map<String, Object> paramMap = new HashMap<String, Object>();
        paramMap.put(TaskConstants.APPID_KEY, appId);
        paramMap.put(TaskConstants.AUDIT_ID_KEY, auditId);
        AppDesc appDesc = appService.getByAppId(appId);
        String importantInfo = appDesc.getName();
        String param = JSONObject.toJSONString(paramMap);

        String className = null;//todo TaskConstants.TWEMPROXY_KEY_ANALYSIS_CLASS;
        return generateAndSaveTaskQueue(appId, className, param, importantInfo,
                parentTaskId);
    }

    @Override
    public long addTwemproxyPikaFlushAllDataTask(long appId, long auditId, long parentTaskId) {
        Map<String, Object> paramMap = new HashMap<String, Object>();
        paramMap.put(TaskConstants.APPID_KEY, appId);
        paramMap.put(TaskConstants.AUDIT_ID_KEY, auditId);
        AppDesc appDesc = appService.getByAppId(appId);
        String importantInfo = appDesc.getName() + "-pika";
        String param = JSONObject.toJSONString(paramMap);

        String className = null;//todo TaskConstants.TWEMPROXY_PIKA_FLUSHALL_DATA_CLASS;
        return generateAndSaveTaskQueue(appId, className, param, importantInfo,
                parentTaskId);
    }

    @Override
    public long addRedisSentinelStopTask(long appId, String host, int port, long parentTaskId) {
        Map<String, Object> paramMap = new HashMap<String, Object>();
        paramMap.put(TaskConstants.APPID_KEY, appId);
        paramMap.put(TaskConstants.HOST_KEY, host);
        paramMap.put(TaskConstants.PORT_KEY, port);
        String importantInfo = host + ":" + port;
        String param = JSONObject.toJSONString(paramMap);

        String className = null;//todo TaskConstants.REDIS_SENTINEL_STOP_CLASS;
        return generateAndSaveTaskQueue(appId, className, param, importantInfo,
                parentTaskId);
    }

    @Override
    public long addNutCrackerStopTask(long appId, String host, int port, long parentTaskId) {
        Map<String, Object> paramMap = new HashMap<String, Object>();
        paramMap.put(TaskConstants.APPID_KEY, appId);
        paramMap.put(TaskConstants.HOST_KEY, host);
        paramMap.put(TaskConstants.PORT_KEY, port);
        String importantInfo = host + ":" + port;
        String param = JSONObject.toJSONString(paramMap);

        String className = null;//todo TaskConstants.NUT_CRACKER_STOP_CLASS;
        return generateAndSaveTaskQueue(appId, className, param, importantInfo,
                parentTaskId);
    }

    @Override
    public long addPikaStopTask(long appId, String host, int port, long parentTaskId) {
        Map<String, Object> paramMap = new HashMap<String, Object>();
        paramMap.put(TaskConstants.APPID_KEY, appId);
        paramMap.put(TaskConstants.HOST_KEY, host);
        paramMap.put(TaskConstants.PORT_KEY, port);
        String importantInfo = host + ":" + port;
        String param = JSONObject.toJSONString(paramMap);

        String className = null;//todo TaskConstants.PIKA_STOP_CLASS;
        return generateAndSaveTaskQueue(appId, className, param, importantInfo,
                parentTaskId);
    }

    @Override
    public long addSlaveRedisServerRebuildTask(long appId, String masterHost, int masterPort,
                                               String slaveMachineHost, long parentTaskId) {
        Map<String, Object> paramMap = new HashMap<String, Object>();
        paramMap.put(TaskConstants.APPID_KEY, appId);
        paramMap.put(TaskConstants.MASTER_HOST_KEY, masterHost);
        paramMap.put(TaskConstants.MASTER_PORT_KEY, masterPort);
        paramMap.put(TaskConstants.SLAVE_MACHINE_KEY, slaveMachineHost);
        String importantInfo = slaveMachineHost;
        String param = JSONObject.toJSONString(paramMap);

        String className = null;//todo TaskConstants.REDIS_SLAVE_SERVER_REBUILD_CLASS;
        return generateAndSaveTaskQueue(appId, className, param, importantInfo,
                parentTaskId);
    }

    @Override
    public long addSlavePikaRebuildTask(long appId, String masterHost, int masterPort, String slaveMachineHost,
                                        long parentTaskId) {
        Map<String, Object> paramMap = new HashMap<String, Object>();
        paramMap.put(TaskConstants.APPID_KEY, appId);
        paramMap.put(TaskConstants.MASTER_HOST_KEY, masterHost);
        paramMap.put(TaskConstants.MASTER_PORT_KEY, masterPort);
        paramMap.put(TaskConstants.SLAVE_MACHINE_KEY, slaveMachineHost);
        String importantInfo = slaveMachineHost;
        String param = JSONObject.toJSONString(paramMap);

        String className = null;//todo TaskConstants.PIKA_SLAVE_REBUILD_CLASS;
        return generateAndSaveTaskQueue(appId, className, param, importantInfo,
                parentTaskId);
    }

    @Override
    public long addSlaveRedisSentinelFailoverTask(long appId, String masterHost, int masterPort, long parentTaskId) {
        Map<String, Object> paramMap = new HashMap<String, Object>();
        paramMap.put(TaskConstants.APPID_KEY, appId);
        paramMap.put(TaskConstants.MASTER_HOST_KEY, masterHost);
        paramMap.put(TaskConstants.MASTER_PORT_KEY, masterPort);
        String importantInfo = masterHost + ":" + masterPort;
        String param = JSONObject.toJSONString(paramMap);

        String className = null;//todo TaskConstants.REDIS_SENTINEL_FAILOVER_CLASS;
        return generateAndSaveTaskQueue(appId, className, param, importantInfo,
                parentTaskId);
    }

    @Override
    public long addTwemproxyFaultMachineFailoverTask(long appId, String host, long parentTaskId) {
        Map<String, Object> paramMap = new HashMap<String, Object>();
        paramMap.put(TaskConstants.APPID_KEY, appId);
        paramMap.put(TaskConstants.HOST_KEY, host);
        String importantInfo = host;
        String param = JSONObject.toJSONString(paramMap);

        String className = null;//todo TaskConstants.TWEMPROXY_FAULT_MACHINE_FAILOVER_CLASS;
        return generateAndSaveTaskQueue(appId, className, param, importantInfo,
                parentTaskId);
    }

    @Override
    public long addMachineSlaveRebuildTask(long appId, String host, long parentTaskId) {
        Map<String, Object> paramMap = new HashMap<String, Object>();
        paramMap.put(TaskConstants.APPID_KEY, appId);
        paramMap.put(TaskConstants.HOST_KEY, host);
        String importantInfo = host;
        String param = JSONObject.toJSONString(paramMap);

        String className = null;//todo TaskConstants.MACHINE_SLAVE_REBUILD_CLASS;
        return generateAndSaveTaskQueue(appId, className, param, importantInfo,
                parentTaskId);
    }

    @Override
    public long addAppTopologyExamTask(boolean auto, int examType, long appId, long parentTaskId) {
        Map<String, Object> paramMap = Maps.newHashMap();
        paramMap.put(TaskConstants.EXAM_TYPE_KEY, examType);
        paramMap.put("auto", auto);
        if (examType == ExamToolEnum.EXAM_APPID.getValue()) {
            paramMap.put(TaskConstants.APPID_KEY, appId);
        }
        String importantInfo = "topology exam, examType:" + String.valueOf(examType);
        String className = TopologyExamTask.class.getSimpleName();
        String param = JSONObject.toJSONString(paramMap);
        long taskId = generateAndSaveTaskQueue(-1, className, param, importantInfo, parentTaskId);
        return taskId;
    }

    @Override
    public long addMachineExamTask(List<String> ipList, Integer useType, long parentTaskId) {
        Map<String, Object> paramMap = Maps.newHashMap();
        paramMap.put(TaskConstants.MACHINE_IP_LIST_KEY, ipList);
        paramMap.put(TaskConstants.USE_TYPE_KEY, useType);
        String importantInfo = "machine cpu/mem exam";
        String className = MachineExamTask.class.getSimpleName();
        String param = JSONObject.toJSONString(paramMap);
        long taskId = generateAndSaveTaskQueue(-1, className, param, importantInfo, parentTaskId);
        return taskId;
    }

    @Override
    public long addOffLineAppTask(long appId, Long auditId, long parentTaskId, AppUser userInfo) {
        Map<String, Object> paramMap = Maps.newHashMap();
        paramMap.put(TaskConstants.APPID_KEY, appId);
        paramMap.put(TaskConstants.AUDIT_ID_KEY, auditId == null ? -1 : auditId);
        paramMap.put(TaskConstants.USER_INFO_KEY, userInfo);
        String param = JSONObject.toJSONString(paramMap);
        AppDesc appDesc = appService.getByAppId(appId);
        String importantInfo = appDesc.getName();
        String className = OffLineAppTask.class.getSimpleName();
        return generateAndSaveTaskQueue(appId, className, param, importantInfo, parentTaskId);
    }

    @Override
    public long addAppScanKeyTask(long appId, long auditId, String nodes, String pattern, int size, long parentTaskId) {
        Map<String, Object> paramMap = new HashMap<String, Object>();
        paramMap.put(TaskConstants.APPID_KEY, appId);
        paramMap.put(TaskConstants.AUDIT_ID_KEY, auditId);
        String nodeInfos = StringUtils.isNotBlank(nodes) ? nodes :
                appService.getAppMasterInstanceInfoList(appId).stream().map(instanceInfo -> instanceInfo.getHostPort()).collect(Collectors.joining(","));
        paramMap.put(TaskConstants.REDIS_SERVER_NODES_KEY, JSONObject.toJSONString(parseNodeInfo(nodeInfos)));
        paramMap.put("pattern", pattern);
        paramMap.put("size", size);

        AppDesc appDesc = appService.getByAppId(appId);
        String importantInfo = appDesc.getName();
        String className = AppScanKeyTask.class.getSimpleName();

        appAuditDao.updateAppAudit(auditId, AppCheckEnum.APP_ALLOCATE_RESOURCE.value());
        String param = JSONObject.toJSONString(paramMap);
        long taskId = generateAndSaveTaskQueue(appId, className, param, importantInfo, parentTaskId);
        appAuditDao.updateTaskId(auditId, taskId);
        return taskId;
    }

    @Override
    public long addInstanceScanKeyTask(long appId, long auditId, String host, int port, String pattern, int size, long parentTaskId) {

        Map<String, Object> paramMap = new HashMap<String, Object>();
        paramMap.put(TaskConstants.APPID_KEY, appId);
        paramMap.put(TaskConstants.AUDIT_ID_KEY, auditId);

        paramMap.put("pattern", pattern);
        paramMap.put("size", size);
        paramMap.put("parentTaskId", parentTaskId);
        paramMap.put(TaskConstants.HOST_KEY, host);
        paramMap.put(TaskConstants.PORT_KEY, port);
        String importantInfo = host + ":" + port;
        String param = JSONObject.toJSONString(paramMap);

        String className = InstanceScanKeyTask.class.getSimpleName();
        return generateAndSaveTaskQueue(appId, className, param, importantInfo, parentTaskId);
    }

    @Override
    public long addInstanceScanCleanKeyTask(long appId, long auditId, String host, int port, Map<String, Object> params, long parentTaskId) {
        Map<String, Object> paramMap = new HashMap<String, Object>();
        paramMap.putAll(params);
        paramMap.put(TaskConstants.APPID_KEY, appId);
        paramMap.put(TaskConstants.AUDIT_ID_KEY, auditId);
        paramMap.put("parentTaskId", parentTaskId);
        paramMap.put(TaskConstants.HOST_KEY, host);
        paramMap.put(TaskConstants.PORT_KEY, port);
        String importantInfo = host + ":" + port;
        String param = JSONObject.toJSONString(paramMap);

        String className = InstanceScanCleanKeyTask.class.getSimpleName();
        return generateAndSaveTaskQueue(appId, className, param, importantInfo, parentTaskId);
    }

    @Override
    public long addAppDelKeyTask(long appId, String nodes, String pattern, long auditId, long parentTaskId) {
        Map<String, Object> paramMap = new HashMap<>();
        paramMap.put(TaskConstants.APPID_KEY, appId);
        String nodeInfos = StringUtils.isNotBlank(nodes) ? nodes :
                appService.getAppMasterInstanceInfoList(appId).stream().map(instanceInfo -> instanceInfo.getHostPort()).collect(Collectors.joining(","));
        paramMap.put(TaskConstants.REDIS_SERVER_NODES_KEY, JSONObject.toJSONString(parseNodeInfo(nodeInfos)));
        paramMap.put(TaskConstants.AUDIT_ID_KEY, auditId);
        paramMap.put("pattern", pattern);

        AppDesc appDesc = appService.getByAppId(appId);
        String importantInfo = appDesc.getName();
        String className = AppDelKeyTask.class.getSimpleName();

        appAuditDao.updateAppAudit(auditId, AppCheckEnum.APP_ALLOCATE_RESOURCE.value());
        String param = JSONObject.toJSONString(paramMap);
        long taskId = generateAndSaveTaskQueue(appId, className, param, importantInfo, parentTaskId);
        appAuditDao.updateTaskId(auditId, taskId);
        return taskId;
    }

    @Override
    public long addInstanceDelKeyTask(long appId, String host, int port, String pattern, long auditId, long parentTaskId) {
        Map<String, Object> paramMap = new HashMap<String, Object>();
        paramMap.put(TaskConstants.APPID_KEY, appId);
        paramMap.put(TaskConstants.AUDIT_ID_KEY, auditId);
        paramMap.put("pattern", pattern);
        paramMap.put("parentTaskId", parentTaskId);
        paramMap.put(TaskConstants.HOST_KEY, host);
        paramMap.put(TaskConstants.PORT_KEY, port);
        String importantInfo = host + ":" + port;
        String param = JSONObject.toJSONString(paramMap);

        String className = InstanceDelKeyTask.class.getSimpleName();
        return generateAndSaveTaskQueue(appId, className, param, importantInfo, parentTaskId);
    }

    @Override
    public long addInstanceDelKeyConfirmTask(long appId, String host, int port, String pattern, long auditId,
                                             long parentTaskId, long previewRecordId, String previewRedisKey) {
        Map<String, Object> paramMap = new HashMap<>();
        paramMap.put(TaskConstants.APPID_KEY, appId);
        paramMap.put(TaskConstants.AUDIT_ID_KEY, auditId);
        paramMap.put("pattern", pattern);
        paramMap.put("parentTaskId", parentTaskId);
        paramMap.put(TaskConstants.HOST_KEY, host);
        paramMap.put(TaskConstants.PORT_KEY, port);
        paramMap.put("confirmDelete", true);
        paramMap.put("previewRecordId", previewRecordId);
        paramMap.put("previewRedisKey", previewRedisKey);
        String param = JSONObject.toJSONString(paramMap);
        String importantInfo = host + ":" + port + " confirm delete preview " + previewRecordId;
        return generateAndSaveTaskQueue(appId, InstanceDelKeyTask.class.getSimpleName(), param,
                importantInfo, parentTaskId);
    }

    @Override
    public long addAppSlotAnalysisTask(long appId, String nodes, long auditId, long parentTaskId) {
        Map<String, Object> paramMap = new HashMap<>();
        paramMap.put(TaskConstants.APPID_KEY, appId);
        String nodeInfos = StringUtils.isNotBlank(nodes) ? nodes : appService.getAppMasterInstanceInfoList(appId).stream().map(instanceInfo -> instanceInfo.getHostPort()).collect(Collectors.joining(","));
        paramMap.put(TaskConstants.REDIS_SERVER_NODES_KEY, JSONObject.toJSONString(parseNodeInfo(nodeInfos)));
        paramMap.put(TaskConstants.AUDIT_ID_KEY, auditId);

        AppDesc appDesc = appService.getByAppId(appId);
        String importantInfo = appDesc.getName();
        String className = AppSlotAnalysisTask.class.getSimpleName();

        appAuditDao.updateAppAudit(auditId, AppCheckEnum.APP_ALLOCATE_RESOURCE.value());
        String param = JSONObject.toJSONString(paramMap);
        long taskId = generateAndSaveTaskQueue(appId, className, param, importantInfo, parentTaskId);
        appAuditDao.updateTaskId(auditId, taskId);
        return taskId;
    }

    @Override
    public long addInstanceSlotAnalysisTask(long appId, String host, int port, long auditId, long parentTaskId) {
        Map<String, Object> paramMap = new HashMap<String, Object>();
        paramMap.put(TaskConstants.APPID_KEY, appId);
        paramMap.put(TaskConstants.AUDIT_ID_KEY, auditId);
        paramMap.put("parentTaskId", parentTaskId);
        paramMap.put(TaskConstants.HOST_KEY, host);
        paramMap.put(TaskConstants.PORT_KEY, port);
        String importantInfo = host + ":" + port;
        String param = JSONObject.toJSONString(paramMap);

        String className = InstanceSlotAnalysisTask.class.getSimpleName();
        return generateAndSaveTaskQueue(appId, className, param, importantInfo, parentTaskId);
    }

    @Override
    public long addAppScanCleanTask(long appId, Map<String, Object> params, long auditId, long parentTaskId) {
        Map<String, Object> paramMap = new HashMap<String, Object>();
        paramMap.putAll(params);
        paramMap.put(TaskConstants.APPID_KEY, appId);
        paramMap.put(TaskConstants.AUDIT_ID_KEY, auditId);
        paramMap.put("parentTaskId", parentTaskId);
        String param = JSONObject.toJSONString(paramMap);

        AppDesc appDesc = appService.getByAppId(appId);
        String importantInfo = appDesc.getName();

        String className = AppScanCleanKeyTask.class.getSimpleName();
        return generateAndSaveTaskQueue(appId, className, param, importantInfo, parentTaskId);
    }

    /**
     * 生成并保存taskqueue
     *
     * @param appId
     * @param className
     * @param param
     * @param parentTaskId
     * @return
     */
    private long generateAndSaveTaskQueue(long appId, String className, String param, String importantInfo,
                                          long parentTaskId) {
        TaskQueue taskQueue = generateTaskQueue(appId, className, param, importantInfo, parentTaskId);
        taskQueueDao.save(taskQueue);
        return taskQueue.getId();
    }

    @Override
    public void updateTaskStepFlowChildTaskId(long taskStepFlowId, long childTaskId) {
        try {
            taskStepFlowDao.updateChildTaskId(taskStepFlowId, childTaskId);
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            throw new RuntimeException(e);
        }
    }

    @Override
    public List<TaskQueue> getTaskQueueList(TaskStatusEnum taskStatusEnum) {
        try {
            return taskQueueDao.getTaskQueueListByStatus(taskStatusEnum.getStatus());
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    @Override
    public void updateTaskQueueStatus(long taskId, TaskStatusEnum taskStatusEnum) {
        try {
            taskQueueDao.updateStatus(taskId, taskStatusEnum.getStatus());
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
        }
    }

    @Override
    public int getTaskQueueCount(TaskSearch taskSearch) {
        try {
            return taskQueueDao.getTaskQueueCount(taskSearch);
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            return 0;
        }
    }

    @Override
    public List<TaskQueue> getTaskQueueList(TaskSearch taskSearch) {
        try {
            return taskQueueDao.getTaskQueueList(taskSearch);
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    @Override
    public List<TaskQueue> getTaskQueueTreeByTaskId(long searchTaskId) {
        List<TaskQueue> taskQueueList = new ArrayList<TaskQueue>();
        fillTaskQueueList(taskQueueList, searchTaskId);
        return taskQueueList;
    }

    /**
     * 递归获取任务
     *
     * @param taskQueueList
     * @param taskId
     */
    private void fillTaskQueueList(List<TaskQueue> taskQueueList, long taskId) {
        TaskQueue parentTaskQueue = taskQueueDao.getById(taskId);
        if (parentTaskQueue != null) {
            taskQueueList.add(parentTaskQueue);
        } else {
            return;
        }
        List<TaskQueue> childTaskQueueList = taskQueueDao.getChildTaskQueueList(taskId);
        if (CollectionUtils.isEmpty(childTaskQueueList)) {
            return;
        } else {
            for (TaskQueue taskQueue : childTaskQueueList) {
                fillTaskQueueList(taskQueueList, taskQueue.getId());
            }
        }
    }

    @Override
    public OperateResult updateParam(long taskId, String param) {
        try {
            taskQueueDao.updateParam(taskId, param);
            return OperateResult.success();
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            return OperateResult.fail(e.getMessage());
        }
    }

    /**
     * 生成新的任务
     *
     * @param appId
     * @param className
     * @param param
     * @param importantInfo
     * @param parentTaskId
     * @return
     */
    private TaskQueue generateTaskQueue(long appId, String className, String param, String importantInfo,
                                        long parentTaskId) {
        TaskQueue taskQueue = new TaskQueue();
        taskQueue.setAppId(appId);
        taskQueue.setClassName(className);
        taskQueue.setParam(param);
        taskQueue.setInitParam(param);
        taskQueue.setStatus(TaskStatusEnum.NEW.getStatus());
        taskQueue.setParentTaskId(parentTaskId);
        Date now = new Date();
        taskQueue.setStartTime(now);
        taskQueue.setEndTime(now);
        taskQueue.setCreateTime(now);
        taskQueue.setUpdateTime(now);
        taskQueue.setErrorCode(TaskErrorCodeEnum.RIGHT.getCode());
        taskQueue.setTaskNote("");
        taskQueue.setErrorMsg("");
        taskQueue.setImportantInfo(importantInfo);
        return taskQueue;
    }

    @Override
    public OperateResult updateTaskFlowStatus(long taskFlowId, int status) {
        try {
            taskStepFlowDao.updateStatus(taskFlowId, status);
            return OperateResult.success();
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            return OperateResult.fail(e.getMessage());
        }
    }

    private String getThreadPoolKey() {
        return AsyncThreadPoolFactory.TASK_EXECUTE_POOL;
    }

    @Override
    public List<TaskQueue> getByAppAndClass(long appId, String className) {
        try {
            return taskQueueDao.getByAppAndClass(appId, className);
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    @Override
    public long addMachineSyncTask(String sourceIp, String targetIp, String containerIp, String important_info, long parentTaskId) {
        Map<String, Object> paramMap = new HashMap<String, Object>();
        paramMap.put(TaskConstants.SOURCE_HOST_KEY, sourceIp);
        paramMap.put(TaskConstants.TARGET_HOST_KEY, targetIp);
        paramMap.put(TaskConstants.CONTAINER_IP, containerIp);
        String param = JSONObject.toJSONString(paramMap);

        String className = TaskTypeConsts.MACHINE_SYNC_CLASS;
        return generateAndSaveTaskQueue(-1, className, param, important_info, parentTaskId);
    }

    @Override
    public long addResourceCompileTask(Integer resourceId, Integer repositoryId, String containerIp, AppUser userInfo) {
        Map<String, Object> paramMap = new HashMap<String, Object>();
        paramMap.put(TaskConstants.RESOURCE_ID, resourceId);
        paramMap.put(TaskConstants.REPOSITORY_ID, repositoryId);
        paramMap.put(TaskConstants.CONTAINER_IP, containerIp);
        paramMap.put(TaskConstants.USER_INFO_KEY, userInfo.getName());
        String param = JSONObject.toJSONString(paramMap);

        String className = TaskTypeConsts.PACK_COMPILE_TASK;
        return generateAndSaveTaskQueue(-1, className, param, "", -1);
    }

}
