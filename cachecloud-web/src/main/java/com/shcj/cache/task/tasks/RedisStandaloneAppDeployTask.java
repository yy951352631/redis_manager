package com.shcj.cache.task.tasks;

import com.alibaba.fastjson.JSONArray;
import com.shcj.cache.constant.AppCheckEnum;
import com.shcj.cache.entity.MachineStats;
import com.shcj.cache.exception.BizException;
import com.shcj.cache.task.BaseTask;
import com.shcj.cache.task.constant.InstanceInfoEnum.InstanceStatusEnum;
import com.shcj.cache.task.constant.InstanceInfoEnum.InstanceTypeEnum;
import com.shcj.cache.task.constant.TaskConstants;
import com.shcj.cache.task.constant.TaskStepFlowEnum.TaskFlowStatusEnum;
import com.shcj.cache.task.entity.RedisServerNode;
import com.shcj.cache.util.EnvUtil;
import com.shcj.cache.web.enums.AppTypeEnum;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.springframework.beans.factory.config.ConfigurableBeanFactory.SCOPE_PROTOTYPE;

/**
 * <p>
 * Description:RedisStandalone部署任务流
 * </p>
 *
 * @author chenshi
 * @version 1.0
 * @date 2019/1/9
 */
@Component("RedisStandaloneAppDeployTask")
@Scope(SCOPE_PROTOTYPE)
public class RedisStandaloneAppDeployTask extends BaseTask {
    /**
     * 应用id
     */
    private long appId;

    /**
     * 审核id
     */
    private long auditId;

    /**
     * redis server机器列表
     */
    private List<String> redisMasterMachineList;

    /**
     * 每台机器redis server实例个数
     */
    private int masterPerMachine;

    /**
     * 每个实例的最大内存(MB)
     */
    private int maxMemory;

    private List<RedisServerNode> redisServerNodes;

    /**
     * Redis版本
     */
    private String version;

    /**
     * Redis模块信息
     */
    private String moduleInfo;

    @Override
    public List<String> getTaskSteps() {
        List<String> taskStepList = new ArrayList<String>();
        //1. 参数初始化
        taskStepList.add(TaskConstants.INIT_METHOD_KEY);
        //2. 检查资源
        taskStepList.add("checkResourceAllow");
        //3. 检查机器可用性
        taskStepList.add("checkMachineConnect");
        //4. 更新机器分配状态
        taskStepList.add("updateMachineAllocateTrue");
        //5. 获取实例列表
        taskStepList.add("generateInstanceNodes");
        //6. 保存实例列表
        taskStepList.add("saveInstanceNodes");
        //7. 创建redis server job
        taskStepList.add("createRedisServerTask");
        //8. 等待redis server job完成
        taskStepList.add("waitRedisServerFinish");
        //9. 更改实例状态
        taskStepList.add("updateInstanceStatus");
        //10. 开始收集部署
        taskStepList.add("deployCollection");
        //11. 设置密码
        taskStepList.add("setPasswd");
        //12. 装载组件
        taskStepList.add("loadModule");
        //12. 审核
        taskStepList.add("updateAudit");
        //13. 更新机器分配状态
        taskStepList.add("updateMachineAllocateTrue");

        return taskStepList;
    }

    @Override
    public TaskFlowStatusEnum init() {
        super.init();

        appId = MapUtils.getLongValue(paramMap, TaskConstants.APPID_KEY);
        if (appId < 0) {
            throw new BizException("task {} appId {} is wrong", taskId, appId);
        }

        //审核id
        auditId = MapUtils.getLongValue(paramMap, TaskConstants.AUDIT_ID_KEY);

        //maxMemory
        maxMemory = MapUtils.getIntValue(paramMap, TaskConstants.REDIS_SERVER_MAX_MEMORY_KEY);
        if (maxMemory <= 0 || maxMemory > TaskConstants.MAX_MEMORY_LIMIT) {
            throw new BizException("task {} maxMemory {} is wrong", taskId, maxMemory);
        }

        //masterPerMachine
        masterPerMachine = MapUtils.getIntValue(paramMap, TaskConstants.MASTER_PER_MACHINE_KEY);
        if (masterPerMachine <= 0 || masterPerMachine > TaskConstants.MAX_MASTER_PER_MACHINE) {
            throw new BizException("task {} masterPerMachine {} is wrong", taskId, masterPerMachine);
        }

        //redis server machine
        String redisMasterMachineStr = MapUtils.getString(paramMap, TaskConstants.REDIS_MASTER_MACHINE_LIST_KEY);
        redisMasterMachineList = JSONArray.parseArray(redisMasterMachineStr, String.class);
        if (CollectionUtils.isEmpty(redisMasterMachineList)) {
            throw new BizException("task {} redisServerMachineList is empty", taskId);
        }

        //redis server list
        String redisServerNodesStr = MapUtils.getString(paramMap, TaskConstants.REDIS_SERVER_NODES_KEY);
        if (StringUtils.isNotBlank(redisServerNodesStr)) {
            redisServerNodes = JSONArray.parseArray(redisServerNodesStr, RedisServerNode.class);
            if (CollectionUtils.isEmpty(redisServerNodes)) {
                throw new BizException("standalone task {} redisServerNodes is empty", taskId);
            }
        }
        // redis版本
        version = MapUtils.getString(paramMap, TaskConstants.VERSION_KEY);

        // 模块安装
        moduleInfo = MapUtils.getString(paramMap, TaskConstants.MODULE_KEY);

        return TaskFlowStatusEnum.SUCCESS;
    }

    /**
     * 检查资源是否充足
     */
    public TaskFlowStatusEnum checkResourceAllow() {
        if (EnvUtil.isLocal(environment)) {
            return TaskFlowStatusEnum.SUCCESS;
        }
        for (String redisServerIp : redisMasterMachineList) {
            MachineStats machineStats = machineStatsDao.getMachineStatsByIp(redisServerIp);
            if (machineStats == null) {
                throw new BizException("checkResourceAllow {} redis server machineStats is null", redisServerIp);
            }

            //检查监控数据更新时间
            checkMachineStatIsUpdate(machineStats);

            //检查内存使用率，不能超过剩余内存的85%
            long memoryFree = NumberUtils.toLong(machineStats.getMemoryFree()) / 1024 / 1024;
            long memoryNeed = (long) masterPerMachine * maxMemory;
            if (memoryNeed > memoryFree * 0.85) {
                throw new BizException("checkResourceAllow standalone={} need {}MB, but memoryFree is {}MB, 不能超过剩余内存的85%", redisServerIp, memoryNeed, memoryFree);
            }
        }

        return TaskFlowStatusEnum.SUCCESS;
    }

    public TaskFlowStatusEnum checkMachineConnect() {
        // redis server
        for (String redisServerIp : redisMasterMachineList) {
            checkMachineIsConnect(redisServerIp);
        }
        return TaskFlowStatusEnum.SUCCESS;
    }

    /**
     * 生成实例
     */
    public TaskFlowStatusEnum generateInstanceNodes() {

        redisServerNodes = instancePortService.generateRedisServerNodeList(appId, redisMasterMachineList, null, maxMemory, AppTypeEnum.REDIS_STANDALONE);
        if (CollectionUtils.isEmpty(redisServerNodes)) {
            throw new BizException("redisServerNodes is empty, appId is {}, redisMasterMachineList is {},  maxMemory is {}",
                    appId, redisMasterMachineList, maxMemory);
        }

        //检查生成结果
        if (CollectionUtils.isEmpty(redisServerNodes)) {
            throw new BizException("generateInstanceNodes failed, redisServerNodes is empty");
        }

        //设置环境变量
        paramMap.put(TaskConstants.REDIS_SERVER_NODES_KEY, redisServerNodes);

        return TaskFlowStatusEnum.SUCCESS;
    }

    /**
     * 保存实例
     */
    public TaskFlowStatusEnum saveInstanceNodes() {
        for (RedisServerNode redisServerNode : redisServerNodes) {
            Integer instanceId = saveInstance(appId, redisServerNode.getIp(), redisServerNode.getPort(), maxMemory,
                    InstanceTypeEnum.REDIS_SERVER, InstanceStatusEnum.NEW_STATUS, "");
            if (instanceId == null) {
                throw new BizException("saveInstanceNodes failed, appId={}, ip={}, port={}, maxMemory={}", appId, redisServerNode.getIp(), redisServerNode.getPort(), maxMemory);
            }
        }
        return TaskFlowStatusEnum.SUCCESS;
    }

    /**
     * 创建任务
     */
    public TaskFlowStatusEnum createRedisServerTask() {
        if (CollectionUtils.isEmpty(redisServerNodes)) {
            throw new BizException("createRedisServerTask {} redisServerNodes is emtpy", taskId);
        }
        for (RedisServerNode redisServerNode : redisServerNodes) {
            String host = redisServerNode.getIp();
            int port = redisServerNode.getPort();
            try {
                long childTaskId = taskService.addRedisServerInstallTask(appId, host, port, redisServerNode.getMaxmemory(), version, false, taskId);
                if (childTaskId < 0) {
                    throw new BizException("createRedisServerTask standalone : {} {} {} redis server childTaskId is {} , childTaskId <= 0", appId, host, port, childTaskId);
                }
                redisServerNode.setTaskId(childTaskId);
                paramMap.put(TaskConstants.REDIS_SERVER_NODES_KEY, redisServerNodes);
                logger.info(marker, "{}:{} redis server task created successfully", host, port);
            } catch (Exception e) {
                throw new BizException("createRedisServerTask {}:{} redis server task created failly", host, port, e);
            }
        }
        return TaskFlowStatusEnum.SUCCESS;
    }

    /**
     * 等待任务完成
     */
    public TaskFlowStatusEnum waitRedisServerFinish() {
        for (RedisServerNode redisServerNode : redisServerNodes) {
            long childTaskId = redisServerNode.getTaskId();
            String host = redisServerNode.getIp();
            int port = redisServerNode.getPort();
            TaskFlowStatusEnum taskFlowStatusEnum = waitTaskFinish(childTaskId, TaskConstants.REDIS_SERVER_INSTALL_TIMEOUT);
            if (taskFlowStatusEnum.equals(TaskFlowStatusEnum.ABORT)) {
                throw new BizException("standalone waitRedisServerFinish : {}:{} redis server task fail，请检查子任务child_task_id={}", host, port, childTaskId);
            } else {
                logger.info(marker, "standalone : {}:{} redis server task finish successfully", host, port);
            }
        }
        return TaskFlowStatusEnum.SUCCESS;
    }

    /**
     * 更新实例状态
     */
    public TaskFlowStatusEnum updateInstanceStatus() {
        for (RedisServerNode redisServerNode : redisServerNodes) {
            String host = redisServerNode.getIp();
            int port = redisServerNode.getPort();
            instanceDao.updateStatus(appId, host, port, InstanceStatusEnum.GOOD_STATUS.getStatus());
        }
        return TaskFlowStatusEnum.SUCCESS;
    }

    /**
     * 部署实例收集
     *
     * @return
     */
    public TaskFlowStatusEnum deployCollection() {
        for (RedisServerNode redisServerNode : redisServerNodes) {
            String host = redisServerNode.getIp();
            int port = redisServerNode.getPort();
            boolean isDeploy = redisCenter.sendDeployRedisRelateCollectionMsg(appId, host, port);
            if (!isDeploy) {
                throw new BizException("{} {}:{} deployCollection fail", appId, host, port);
            } else {
                logger.info(marker, "{} {}:{} deploy sucessfully", appId, host, port);
            }
        }
        return TaskFlowStatusEnum.SUCCESS;
    }

    /**
     * 设置密码
     *
     * @return
     */
    public TaskFlowStatusEnum setPasswd() {
        try {
            if (appId >= 0) {
                // 设置密码
                redisDeployCenter.fixPassword(appId, null, null, true);
                // 密码校验逻辑
                boolean checkFlag = redisDeployCenter.checkAuths(appId);
                logger.info(marker, "check app standalone passwd success:{}", checkFlag);
                if (!checkFlag) {
                    throw new BizException("check app standalone passwd error, appId:{}!", appId);
                }
            }
        } catch (Exception ex) {
            throw new BizException("setPasswd exception appId={} ", appId, ex);
        }
        return TaskFlowStatusEnum.SUCCESS;
    }

    public TaskFlowStatusEnum loadModule() {
        return TaskFlowStatusEnum.SUCCESS;
    }

    /**
     * 通过初审：资源分配
     *
     * @return
     */
    public TaskFlowStatusEnum updateAudit() {
        try {
            finalizeAppDeployToOnline(appId, auditId);
            return TaskFlowStatusEnum.SUCCESS;
        } catch (Exception e) {
            logger.error(marker, e.getMessage(), e);
            return TaskFlowStatusEnum.ABORT;
        }
    }

    public TaskFlowStatusEnum updateMachineAllocateFalse() {
        return updateMachineAllocateStatus(0);
    }

    public TaskFlowStatusEnum updateMachineAllocateTrue() {
        return updateMachineAllocateStatus(1);
    }

    public TaskFlowStatusEnum updateMachineAllocateStatus(int status) {
        try {
            Set<String> allMachineSet = new HashSet<String>(redisMasterMachineList);
            for (String ip : allMachineSet) {
                machineDao.updateMachineAllocate(ip, status);
            }
            return TaskFlowStatusEnum.SUCCESS;
        } catch (Exception e) {
            logger.error(marker, e.getMessage(), e);
            return TaskFlowStatusEnum.ABORT;
        }
    }

}
