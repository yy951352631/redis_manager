package com.shcj.cache.task.tasks.install;

import com.alibaba.fastjson.JSONArray;
import com.shcj.cache.entity.AppDesc;
import com.shcj.cache.entity.SystemResource;
import com.shcj.cache.protocol.RedisProtocol;
import com.shcj.cache.task.BaseTask;
import com.shcj.cache.exception.BizException;
import com.shcj.cache.task.constant.InstanceInfoEnum;
import com.shcj.cache.task.constant.TaskConstants;
import com.shcj.cache.task.constant.TaskStepFlowEnum.TaskFlowStatusEnum;
import com.shcj.cache.task.entity.RedisServerNode;
import com.shcj.cache.util.ConstUtils;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang.StringUtils;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

import static org.springframework.beans.factory.config.ConfigurableBeanFactory.SCOPE_PROTOTYPE;

/**
 * <p>
 * Description: Redis sentinel安装
 * </p>
 *
 * @author chenshi
 * @version 1.0
 * @date 2019/1/11
 */
@Component("RedisSentinelInstallTask")
@Scope(SCOPE_PROTOTYPE)
public class RedisSentinelInstallTask extends BaseTask {

    private long appId;

    private String host;

    private int port;

    private int quorum;

    SystemResource redisResource;

    private List<RedisServerNode> masterRedisServerNodes;

    /**
     * 当前实例类型
     */
    private final InstanceInfoEnum.InstanceTypeEnum currentInstanceTypeEnum = InstanceInfoEnum.InstanceTypeEnum.REDIS_SENTINEL;

    @Override
    public List<String> getTaskSteps() {
        List<String> taskStepList = new ArrayList<String>();
        taskStepList.add(TaskConstants.INIT_METHOD_KEY);
        taskStepList.add("checkIsExist");
        taskStepList.add("prepareRelateDir");
        taskStepList.add("prepareRelateBin");
        taskStepList.add("pushService");
        taskStepList.add("pushConfig");
        taskStepList.add("startServer");
        taskStepList.add("checkIsRun");
        return taskStepList;
    }

    /**
     * 初始化参数
     *
     * @return
     */
    @Override
    public TaskFlowStatusEnum init() {
        super.init();

        appId = MapUtils.getLongValue(paramMap, TaskConstants.APPID_KEY);
        if (appId <= 0) {
            throw new BizException("task {} appId {} is wrong", taskId, appId);
        }

        host = MapUtils.getString(paramMap, TaskConstants.HOST_KEY);
        if (StringUtils.isBlank(host)) {
            throw new BizException("task {} host is empty", taskId);
        }

        port = MapUtils.getIntValue(paramMap, TaskConstants.PORT_KEY);
        if (port <= 0) {
            throw new BizException("task {} port {} is wrong", taskId, port);
        }

        quorum = MapUtils.getIntValue(paramMap, TaskConstants.REDIS_SENTINEL_QUORUM_KEY);
        if (quorum <= 0) {
            throw new BizException("task {} quorum {} is wrong", taskId, quorum);
        }

        //parse
        String masterRedisNodeStr = MapUtils.getString(paramMap, TaskConstants.MASTER_REDIS_SERVER_NODES);
        masterRedisServerNodes = JSONArray.parseArray(masterRedisNodeStr, RedisServerNode.class);
        if (CollectionUtils.isEmpty(masterRedisServerNodes)) {
            throw new BizException("task {} masterRedisServerNodes is empty", taskId);
        }
        redisResource = resourceService.getResourceById(appService.getByAppId(appId).getVersionId());
        return TaskFlowStatusEnum.SUCCESS;
    }

    /**
     * 检查实例是否已经存在
     *
     * @return
     */
    public TaskFlowStatusEnum checkIsExist() {
        return checkInstanceIsExist(appId, host, port, currentInstanceTypeEnum);
    }

    /**
     * 准备相关目录
     *
     * @return
     */
    public TaskFlowStatusEnum prepareRelateDir() {
        return prepareRelateDir(appId, host, port, currentInstanceTypeEnum);
    }

    /**
     * 准备二进制执行文件
     *
     * @return
     */
    public TaskFlowStatusEnum prepareRelateBin() {
        return prepareRelateBin(appId, host, port, currentInstanceTypeEnum, redisResource.getName());
    }

    /**
     * 启动服务
     *
     * @return
     */
    public TaskFlowStatusEnum pushService() {
        //远程和本地目录
        /*String instanceRemoteBasePath = machineCenter.getInstanceRemoteBasePath(appId, port, currentInstanceTypeEnum);
        String instanceLocalTmpPath = machineCenter.getInstanceLocalTempBasePath(appId, host, port, currentInstanceTypeEnum);

        //相关参数
        int cpuidx = MachineProtocol.getCpuIdx(port);
        String startCmd = RedisProtocol.getRedisSentinelStartCmd();
        String runCmd = RedisProtocol.getRedisSentinelRunCmd(port);
        String serviceShellFileName = RedisProtocol.getServiceShellFileName(currentInstanceTypeEnum);

        logger.info(marker, "cpuidx is {}", cpuidx);
        logger.info(marker, "startCmd is {}", startCmd);
        logger.info(marker, "runCmd is {}", runCmd);

        return pushService(appId, currentInstanceTypeEnum, host, port, instanceRemoteBasePath, instanceLocalTmpPath, cpuidx, startCmd, runCmd, serviceShellFileName);
        */
        return TaskFlowStatusEnum.SUCCESS;
    }

    /**
     * 推配置
     *
     * @return
     */
    public TaskFlowStatusEnum pushConfig() {
        //资源是否存在
        redisConfigTemplateService.checkAndInstallRedisResource(host, redisResource);
        AppDesc appDesc = appService.getByAppId(appId);

        List<String> masterSentinelConfigs;
        // 获取masterName
        if (masterRedisServerNodes.size() == 1) {
            RedisServerNode redisServerNode = masterRedisServerNodes.get(0);
            masterSentinelConfigs = handleSentinelConfig(redisServerNode.getMasterName(), redisServerNode.getIp(), redisServerNode.getPort(), host, port, appDesc.getVersionId());
            logger.info("sentinel configs :" + masterSentinelConfigs);
        } else {
            throw new BizException("appId {} masterRedisServerNodes {} is empty", appId, masterRedisServerNodes);
        }

        if (CollectionUtils.isEmpty(masterSentinelConfigs)) {
            throw new BizException("appId {} host:{} port:{} versionId:{} instanceRemoteBasePath {} configList is empty，需要在【模板配置】中增加预置的配置", appId, host, port, appDesc.getVersionId(), ConstUtils.REDIS_BASE_DIR);
        }

        String masterSentinelFileName = RedisProtocol.getConfig(port, false);
        String sentinelPathFile = machineCenter
                .createRemoteFile(host, masterSentinelFileName, masterSentinelConfigs);
        if (StringUtils.isBlank(sentinelPathFile)) {
            throw new BizException("sentinelPathFile is blank, {}, {} {}", host, port, redisResource.getName());
        }

        return TaskFlowStatusEnum.SUCCESS;
    }

    private String getMasterName(String host, int port) {
        return String.format("sentinel-%s-%s", host, port);
    }

    private void printConfig(List<String> masterConfigs) {
        logger.info("==================redis-{}-config==================", masterConfigs);
        for (String line : masterConfigs) {
            logger.info(line);
        }
    }

    /**
     * 启动服务
     *
     * @return
     */
    public TaskFlowStatusEnum startServer() {
        redisConfigTemplateService.checkAndInstallRedisResource(host, redisResource);
        String redisDir = redisResource == null ? ConstUtils.REDIS_DEFAULT_DIR : ConstUtils.getRedisDir(redisResource.getName());
        String sentinelShell = redisDeployCenter.getSentinelRunShell(host, port, redisDir);
        logger.info(marker, "sentinelMasterShell:{}", sentinelShell);
        boolean isSentinelMasterShell = machineCenter.startProcessAtPort(host, port, sentinelShell);
        if (!isSentinelMasterShell) {
            throw new BizException("sentinelMasterShell={} error", sentinelShell);
        }
        return TaskFlowStatusEnum.SUCCESS;
    }

    /**
     * @return
     */
    public TaskFlowStatusEnum checkIsRun() {
        if (!redisCenter.isRun(host, port)) {
            throw new BizException("sentinel {}:{} is not run", host, port);
        } else {
            logger.info(marker, "sentinel {}:{} is run", host, port);
            return TaskFlowStatusEnum.SUCCESS;
        }
    }

    /**
     * <p>
     * Description: 获取sentinel config
     * </p>
     *
     * @author chenshi
     * @version 1.0
     * @date 2019/1/11
     */
    private List<String> handleSentinelConfig(String masterName, String host, int port, String sentinelHost, int sentinelPort, int versionId) {
        try {
            // todo  masterHost
            return redisConfigTemplateService.handleSentinelConfig(masterName, host, port, sentinelHost, sentinelPort, versionId);
        } catch (Exception ex) {
            throw new BizException("handleSentinelConfig failed, {}", ex.getMessage());
        }
    }

}
