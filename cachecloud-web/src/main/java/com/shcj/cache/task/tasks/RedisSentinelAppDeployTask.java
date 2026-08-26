package com.shcj.cache.task.tasks;

import com.alibaba.fastjson.JSONArray;
import com.shcj.cache.constant.AppCheckEnum;
import com.shcj.cache.entity.AppDesc;
import com.shcj.cache.entity.MachineInfo;
import com.shcj.cache.entity.MachineStats;
import com.shcj.cache.exception.BizException;
import com.shcj.cache.task.BaseTask;
import com.shcj.cache.task.constant.InstanceInfoEnum.InstanceStatusEnum;
import com.shcj.cache.task.constant.InstanceInfoEnum.InstanceTypeEnum;
import com.shcj.cache.task.constant.TaskConstants;
import com.shcj.cache.task.constant.TaskStepFlowEnum.TaskFlowStatusEnum;
import com.shcj.cache.task.entity.RedisSentinelNode;
import com.shcj.cache.task.entity.RedisServerNode;
import com.shcj.cache.util.EnvUtil;
import com.shcj.cache.web.enums.AppTypeEnum;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.math.NumberUtils;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.util.*;

import static org.springframework.beans.factory.config.ConfigurableBeanFactory.SCOPE_PROTOTYPE;

/**
 * <p>
 * Description: Redis Sentinel应用部署任务流
 * </p>
 *
 * @author chenshi
 * @version 1.0
 * @date 2019/1/10
 */
@Component("RedisSentinelAppDeployTask")
@Scope(SCOPE_PROTOTYPE)
public class RedisSentinelAppDeployTask extends BaseTask {

    /**
     * 应用id
     */
    private long appId;

    /**
     * 审核id
     */
    private long auditId;

    /**
     * redis server机器列表-主
     */
    private List<String> redisMasterMachineList;

    /**
     * redis server机器列表-从
     */
    private List<String> redisSlaveMachineList;

    /**
     * redis sentinel机器列表
     */
    private List<String> redisSentinelMachineList;

    /**
     * 每个实例的最大内存(MB)
     */
    private int maxMemory;

    private List<RedisServerNode> redisServerNodes;

    private List<RedisSentinelNode> redisSentinelNodes;

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
        taskStepList.add("saveRedisInstanceNodes");
        taskStepList.add("saveSentinelInstanceNodes");
        //7. 创建redis server job
        taskStepList.add("createRedisServerTask");
        //8. 等待redis server job完成
        taskStepList.add("waitRedisServerFinish");
        //9. 主从复制
        taskStepList.add("configReplication");
        //10. 创建redis sentinel job
        taskStepList.add("createRedisSentinelTask");
        //11. 等待redis sentinel job完成
        taskStepList.add("waitRedisSentinelFinish");
        //12. 更改实例状态
        taskStepList.add("updateInstanceStatus");
        //13. 开始收集部署
        taskStepList.add("deployCollection");
        //14. 设置密码
        taskStepList.add("setPasswd");
        //14. 装载组件
        taskStepList.add("loadModule");
        //15. 重置sentinel实例状态
        taskStepList.add("sentinelReset");
        //16. 审核
        taskStepList.add("updateAudit");
        //17. 更新机器分配状态
        taskStepList.add("updateMachineAllocateFalse");
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
            throw new BizException("task {} maxMemory {} is wrong， maxMemory<=0 OR maxMemory>20G", taskId, maxMemory);
        }

        //redis server machine 主
        String redisMasterMachineStr = MapUtils.getString(paramMap, TaskConstants.REDIS_MASTER_MACHINE_LIST_KEY);
        redisMasterMachineList = JSONArray.parseArray(redisMasterMachineStr, String.class);
        if (CollectionUtils.isEmpty(redisMasterMachineList)) {
            throw new BizException("task {} redisServerMachineList is empty", taskId);
        }

        //redis server machine 从
        String redisSlaveMachineStr = MapUtils.getString(paramMap, TaskConstants.REDIS_SLAVE_MACHINE_LIST_KEY);
        redisSlaveMachineList = JSONArray.parseArray(redisSlaveMachineStr, String.class);
        if (CollectionUtils.isEmpty(redisSlaveMachineList)) {
            throw new BizException("task {} redisServerMachineList is empty", taskId);
        }

        //redis server list
        String redisServerNodesStr = MapUtils.getString(paramMap, TaskConstants.REDIS_SERVER_NODES_KEY);
        if (StringUtils.isNotBlank(redisServerNodesStr)) {
            redisServerNodes = JSONArray.parseArray(redisServerNodesStr, RedisServerNode.class);
            if (CollectionUtils.isEmpty(redisServerNodes)) {
                throw new BizException("task {} redisServerNodes is empty", taskId);
            }
        }

        //redis sentinel machine
        String redisSentinelMachineStr = MapUtils.getString(paramMap, TaskConstants.REDIS_SENTINEL_MACHINE_LIST_KEY);
        redisSentinelMachineList = JSONArray.parseArray(redisSentinelMachineStr, String.class);
        if (CollectionUtils.isEmpty(redisSentinelMachineList)) {
            throw new BizException("task {} redisSentinelMachineList is empty", taskId);
        }

        //redis sentinel list
        String redisSentinelNodesStr = MapUtils.getString(paramMap, TaskConstants.REDIS_SENTINEL_NODES_KEY);
        if (StringUtils.isNotBlank(redisSentinelNodesStr)) {
            redisSentinelNodes = JSONArray.parseArray(redisSentinelNodesStr, RedisSentinelNode.class);
            if (CollectionUtils.isEmpty(redisSentinelNodes)) {
                throw new BizException("task {} redisSentinelNodes is empty", taskId);
            }
        }
        version = MapUtils.getString(paramMap, TaskConstants.VERSION_KEY);

        // 模块安装
        moduleInfo = MapUtils.getString(paramMap, TaskConstants.MODULE_KEY);

        return TaskFlowStatusEnum.SUCCESS;
    }

    /**
     * 检查资源是否充足
     *
     * @return
     */
    public TaskFlowStatusEnum checkResourceAllow() {
        if (EnvUtil.isLocal(environment)) {
            return TaskFlowStatusEnum.SUCCESS;
        }
        // 容量和代理
        for (String redisServerIp : redisMasterMachineList) {
            checkResourceAllowPerIp(redisServerIp);
        }
        for (String redisServerIp : redisSlaveMachineList) {
            checkResourceAllowPerIp(redisServerIp);
        }

        // sentinel
        for (String redisSentinelIp : redisSentinelMachineList) {
            MachineStats machineStats = machineStatsDao.getMachineStatsByIp(redisSentinelIp);
            if (machineStats == null) {
                throw new BizException("{} redis sentinel machineStats is null", redisSentinelIp);
            }
            MachineInfo machineInfo = machineDao.getMachineInfoByIp(redisSentinelIp);
            if (machineInfo == null || machineInfo.getIsAllocating() == 1) {
                throw new BizException("redis sentinel machine {} allocating is 1");
            }
            checkMachineStatIsUpdate(machineStats);
        }

        return TaskFlowStatusEnum.SUCCESS;
    }

    private void checkResourceAllowPerIp(String redisServerIp) {

        MachineStats machineStats = machineStatsDao.getMachineStatsByIp(redisServerIp);
        if (machineStats == null) {
            throw new BizException("{} redis server machineStats is null", redisServerIp);
        }
        MachineInfo machineInfo = machineDao.getMachineInfoByIp(redisServerIp);
        if (machineInfo == null) {
            throw new BizException("redis server machine info is null");
        }
        // 机器是否分配 isAllocate
        if (machineInfo.getIsAllocating() == 1) {
            throw new BizException("redis server machine info {} {} allocating is 1", machineInfo.getIp(), redisServerIp);
        }
        if (!checkMachineStatIsUpdate(machineStats)) {
            throw new BizException("redis server machine stats {} update_time is {}, may be not updated recently", machineInfo.getIp(), machineStats.getUpdateTimeFormat());
        }
        //兆
        long memoryFree = NumberUtils.toLong(machineStats.getMemoryFree()) / 1024 / 1024;
        long memoryNeed = (long) maxMemory;
        if (memoryNeed > memoryFree * 0.7) {
            throw new BizException("{} need {} MB, but memoryFree is {} MB", redisServerIp, memoryNeed, memoryFree);
        }
    }


    public TaskFlowStatusEnum checkMachineConnect() {
        // redis server
        for (String redisServerIp : redisMasterMachineList) {
            boolean isConnected = checkMachineIsConnect(redisServerIp);
            if (!isConnected) {
                throw new BizException("sentinelRedisServer {} is not connected", redisServerIp);
            }
        }
        for (String redisServerIp : redisSlaveMachineList) {
            boolean isConnected = checkMachineIsConnect(redisServerIp);
            if (!isConnected) {
                throw new BizException("sentinelRedisServer {} is not connected", redisServerIp);
            }
        }

        // redis sentinel
        for (String redisSentinelIp : redisSentinelMachineList) {
            boolean isConnected = checkMachineIsConnect(redisSentinelIp);
            if (!isConnected) {
                throw new BizException("redisSentinel {} is not connected", redisSentinelIp);
            }
        }
        return TaskFlowStatusEnum.SUCCESS;
    }

    /**
     * 生成实例
     *
     * @return
     */
    public TaskFlowStatusEnum generateInstanceNodes() {

        redisServerNodes = instancePortService.generateRedisServerNodeList(appId, redisMasterMachineList, redisSlaveMachineList, maxMemory, AppTypeEnum.REDIS_SENTINEL);
        if (CollectionUtils.isEmpty(redisServerNodes) || redisServerNodes.size() < 2) {
            throw new BizException("redisServerNodes is empty or <2, appId is {}, redisServerMachineList is {}, redisSlaveMachineList is {}, maxMemory is {}",
                    appId, redisMasterMachineList, redisSlaveMachineList, maxMemory);
        }
        //只取一对
        redisServerNodes = redisServerNodes.subList(0, 2);

        redisSentinelNodes = instancePortService.generateRedisSentinelNodeList(appId, redisSentinelMachineList);
        if (CollectionUtils.isEmpty(redisSentinelNodes)) {
            throw new BizException("redisSentinelNodes is empty, appId is {}, redisSentinelMachineList is {}",
                    appId, redisSentinelMachineList);
        }

        //设置环境变量
        paramMap.put(TaskConstants.REDIS_SERVER_NODES_KEY, redisServerNodes);
        paramMap.put(TaskConstants.REDIS_SENTINEL_NODES_KEY, redisSentinelNodes);

        return TaskFlowStatusEnum.SUCCESS;
    }

    /**
     * 保存redis实例信息
     */
    public TaskFlowStatusEnum saveRedisInstanceNodes() {
        if (CollectionUtils.isEmpty(redisServerNodes)) {
            throw new BizException("redisServerNodes is empty");
        }
        for (RedisServerNode redisServerNode : redisServerNodes) {
            Integer instanceId = saveInstance(appId, redisServerNode.getIp(), redisServerNode.getPort(), maxMemory,
                    InstanceTypeEnum.REDIS_SERVER, InstanceStatusEnum.NEW_STATUS, "");
            if (instanceId == null) {
                throw new BizException("instanceId == null");
            }
        }
        return TaskFlowStatusEnum.SUCCESS;
    }

    /**
     * 保存sentinel实例信息
     */
    public TaskFlowStatusEnum saveSentinelInstanceNodes() {
        if (CollectionUtils.isEmpty(redisSentinelNodes)) {
            throw new BizException("redisSentinelNodes is empty");
        }
        // 获取sentinel masterName
        String masterName = "";
        List<RedisServerNode> masterRedisServerNodes = getMasterRedisServerNodes();
        if (masterRedisServerNodes.size() == 1) {
            masterName = masterRedisServerNodes.get(0).getMasterName();
            logger.info("sentinel masterName :" + masterName);
        } else {
            throw new BizException("appId {} masterRedisServerNodes {} is empty", appId, masterRedisServerNodes);
        }
        // 保存sentinel实例信息
        if (!StringUtils.isEmpty(masterName)) {
            for (RedisSentinelNode redisSentinelNode : redisSentinelNodes) {
                Integer instanceId = saveInstance(appId, redisSentinelNode.getIp(), redisSentinelNode.getPort(), 0,
                        InstanceTypeEnum.REDIS_SENTINEL, InstanceStatusEnum.NEW_STATUS, masterName);
                if (instanceId == null) {
                    throw new BizException("instanceId == null ");
                }
            }
        } else {
            throw new BizException("appId {} masterName {} is empty", appId, masterName);
        }
        return TaskFlowStatusEnum.SUCCESS;
    }

    /**
     * 创建任务
     *
     * @return
     */
    public TaskFlowStatusEnum createRedisServerTask() {
        if (CollectionUtils.isEmpty(redisServerNodes)) {
            throw new BizException("{} redisServerNodes is emtpy", taskId);
        }
        for (RedisServerNode redisServerNode : redisServerNodes) {
            String host = redisServerNode.getIp();
            int port = redisServerNode.getPort();
            try {
                long childTaskId = taskService.addRedisServerInstallTask(appId, host, port, redisServerNode.getMaxmemory(), version, false, taskId);
                if (childTaskId < 0) {
                    throw new BizException("{} {} {} redis server childTaskId is {}", appId, host, port, childTaskId);
                }
                redisServerNode.setTaskId(childTaskId);
                paramMap.put(TaskConstants.REDIS_SERVER_NODES_KEY, redisServerNodes);
                logger.info(marker, "{}:{} redis server task created successfully", host, port);
            } catch (Exception e) {
                throw new BizException("{}:{} redis server task created failly", host, port, e);
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
                throw new BizException("{}:{} redis server task fail，请检查子任务childTaskId={}", host, port, childTaskId);
            } else {
                logger.info(marker, "{}:{} redis server task finish successfully", host, port);
            }
        }
        return TaskFlowStatusEnum.SUCCESS;
    }

    /**
     * 建立主从关系
     */
    public TaskFlowStatusEnum configReplication() {
        for (RedisServerNode redisServerNode : redisServerNodes) {
            String masterHost = redisServerNode.getMasterHost();
            int masterPort = redisServerNode.getMasterPort();
            if (masterHost == null || masterPort <= 0) {
                continue;
            }
            String slaveHost = redisServerNode.getIp();
            int slavePort = redisServerNode.getPort();
            //幂等
            boolean isSlaveOf = redisDeployCenter.slaveOf(appId, masterHost, masterPort, slaveHost, slavePort);
            if (!isSlaveOf) {
                throw new BizException("{}:{} slaveof {}:{} fail", slaveHost, slavePort, masterHost, masterPort);
            }
            logger.info(marker, "{}:{} slaveof {}:{} successfully", slaveHost, slavePort, masterHost, masterPort);
        }
        return TaskFlowStatusEnum.SUCCESS;
    }

    /**
     * 创建redis sentinel任务
     */
    public TaskFlowStatusEnum createRedisSentinelTask() {
        List<RedisServerNode> masterRedisServerNodes = getMasterRedisServerNodes();

        // 计算quorum
        int quorum = getQuorum(redisSentinelNodes.size());
        for (RedisSentinelNode redisSentinelNode : redisSentinelNodes) {
            String sentinelHost = redisSentinelNode.getIp();
            int sentinelPort = redisSentinelNode.getPort();
            try {
                long childTaskId = taskService.addRedisSentinelInstallTask(appId, sentinelHost, sentinelPort, masterRedisServerNodes, quorum, version, taskId);
                if (childTaskId < 0) {
                    throw new BizException("{} {} {} sentinel childTaskId is {}", appId, sentinelHost, sentinelPort, childTaskId);
                }
                redisSentinelNode.setTaskId(childTaskId);
                paramMap.put(TaskConstants.REDIS_SENTINEL_NODES_KEY, redisSentinelNodes);
                logger.info(marker, "{}:{} redis sentinel task created successfully, childTaskId is {}", sentinelHost, sentinelPort, childTaskId);
            } catch (Exception e) {
                throw new BizException("{}:{} redis sentinel task created failly", sentinelHost, sentinelPort, e);
            }
        }
        return TaskFlowStatusEnum.SUCCESS;
    }

    /**
     * 等待所有redis sentinel启动
     */
    public TaskFlowStatusEnum waitRedisSentinelFinish() {
        for (RedisSentinelNode redisSentinelNode : redisSentinelNodes) {
            long childTaskId = redisSentinelNode.getTaskId();
            String host = redisSentinelNode.getIp();
            int port = redisSentinelNode.getPort();
            TaskFlowStatusEnum taskFlowStatusEnum = waitTaskFinish(childTaskId, TaskConstants.REDIS_SENTINEL_INSTALL_TIMEOUT);
            if (taskFlowStatusEnum.equals(TaskFlowStatusEnum.ABORT)) {
                throw new BizException("{} {}:{} redis sentinel task fail，请检查子任务childTaskId={}", appId, host, port, childTaskId);
            } else {
                logger.info(marker, "{} {}:{} redis sentinel task success", appId, host, port);
            }
        }
        return TaskFlowStatusEnum.SUCCESS;
    }

    /**
     * 获取所有master节点
     *
     * @return
     */
    private List<RedisServerNode> getMasterRedisServerNodes() {
        AppDesc appDesc = appDao.getAppDescById(appId);
        String appFullName = appDesc.getName();
        if (StringUtils.isBlank(appFullName)) {
            logger.error(marker, "appId {} fullName is empty", appId);
            return Collections.emptyList();
        }
        List<RedisServerNode> masterRedisServerNodes = new ArrayList<RedisServerNode>();
        for (RedisServerNode redisServerNode : redisServerNodes) {
            if (!redisServerNode.isMaster()) {
                continue;
            }
            if (StringUtils.isEmpty(appDesc.getMasterName())) {
                String masterName = "Sentinel_" + appDesc.getAppId() + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
                appDesc.setMasterName(masterName);
                if (appDao.updateMasterName(appDesc) < 0) {
                    throw new BizException("getMasterRedisServerNodes failed. appDesc.setMasterName(masterName) except." + masterName);
                }
            }
            redisServerNode.setMasterName(appDesc.getMasterName());
            masterRedisServerNodes.add(redisServerNode);
        }
        return masterRedisServerNodes;
    }

    /**
     * 更新实例状态
     *
     * @return
     */
    public TaskFlowStatusEnum updateInstanceStatus() {
        for (RedisServerNode redisServerNode : redisServerNodes) {
            String host = redisServerNode.getIp();
            int port = redisServerNode.getPort();
            instanceDao.updateStatus(appId, host, port, InstanceStatusEnum.GOOD_STATUS.getStatus());

        }
        for (RedisSentinelNode redisSentinelNode : redisSentinelNodes) {
            String host = redisSentinelNode.getIp();
            int port = redisSentinelNode.getPort();
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
                throw new BizException("{} {}:{} deploy fail", appId, host, port);
            } else {
                logger.info(marker, "{} {}:{} deploy sucessfully", appId, host, port);
            }
        }
        return TaskFlowStatusEnum.SUCCESS;
    }

    public TaskFlowStatusEnum setPasswd() {

        try {
            if (appId >= 0) {
                // 设置密码
                redisDeployCenter.fixPassword(appId, null, null, true);
                // 密码校验逻辑
                boolean checkFlag = redisDeployCenter.checkAuths(appId);
                logger.info(marker, "check app sentinel passwd:{}", checkFlag);
                if (!checkFlag) {
                    throw new BizException("check app sentinel passwd error, appId:{}!", appId);
                }
            }
        } catch (Exception e) {
            throw new BizException("setPasswd failed", e);
        }
        return TaskFlowStatusEnum.SUCCESS;
    }

    public TaskFlowStatusEnum loadModule() {
        //删除module
        return TaskFlowStatusEnum.SUCCESS;
    }

    /**
     * 清理sentinel实例状态
     *
     * @return
     */
    public TaskFlowStatusEnum sentinelReset() {

        try {
            redisDeployCenter.sentinelReset(appId);
        } catch (Exception e) {
            throw new BizException("sentinelReset failed", e);
        }

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
            throw new BizException("updateAudit failed", e);
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
            allMachineSet.addAll(redisSlaveMachineList);
            for (String ip : allMachineSet) {
                machineDao.updateMachineAllocate(ip, status);
            }
            return TaskFlowStatusEnum.SUCCESS;
        } catch (Exception e) {
            throw new BizException("updateMachineAllocateStatus failed", e);
        }
    }

}
