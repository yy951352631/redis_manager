package com.shcj.cache.stats.instance.impl;

import com.shcj.cache.alert.EmailComponent;
import com.shcj.cache.constant.AppCheckEnum;
import com.shcj.cache.constant.InstanceStatusEnum;
import com.shcj.cache.dao.AppAuditDao;
import com.shcj.cache.dao.AppDao;
import com.shcj.cache.dao.InstanceDao;
import com.shcj.cache.dao.MachineRelationDao;
import com.shcj.cache.entity.*;
import com.shcj.cache.exception.BizException;
import com.shcj.cache.machine.MachineCenter;
import com.shcj.cache.machine.MachineDeployCenter;
import com.shcj.cache.redis.AssistRedisService;
import com.shcj.cache.redis.RedisCenter;
import com.shcj.cache.redis.RedisConfigTemplateService;
import com.shcj.cache.redis.RedisDeployCenter;
import com.shcj.cache.stats.instance.InstanceDeployCenter;
import com.shcj.cache.task.TaskService;
import com.shcj.cache.task.constant.InstanceInfoEnum;
import com.shcj.cache.task.constant.MachineSyncEnum;
import com.shcj.cache.task.constant.TaskQueueEnum;
import com.shcj.cache.task.entity.TaskQueue;
import com.shcj.cache.util.ConstUtils;
import com.shcj.cache.util.TypeUtil;
import com.shcj.cache.web.enums.AlertTypeEnum;
import com.shcj.cache.web.enums.MachineTaskEnum;
import com.shcj.cache.web.enums.PodStatusEnum;
import com.shcj.cache.web.service.AppAlertRecordService;
import com.shcj.cache.web.service.AppService;
import com.shcj.cache.web.service.ResourceService;
import com.shcj.cache.web.util.DateUtil;
import com.shcj.cache.web.util.FreemakerUtils;
import freemarker.template.Configuration;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;
import org.springframework.web.client.RestTemplate;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.exceptions.JedisDataException;

import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Created by yijunzhang on 14-11-26.
 */
@Service("instanceDeployCenter")
public class InstanceDeployCenterImpl implements InstanceDeployCenter {

    private final Logger logger = LoggerFactory.getLogger(this.getClass());
    @Autowired
    private InstanceDao instanceDao;
    @Autowired
    private MachineRelationDao machineRelationDao;
    @Autowired
    @Lazy
    private RedisCenter redisCenter;
    @Autowired
    private RedisDeployCenter redisDeployCenter;
    @Autowired
    @Lazy
    private MachineCenter machineCenter;
    @Autowired
    private AppAuditDao appAuditDao;
    @Autowired
    private AppDao appDao;
    @Autowired
    private RedisConfigTemplateService redisConfigTemplateService;
    @Autowired
    EmailComponent emailComponent;
    @Autowired(required = false)
    RestTemplate restTemplate;
    @Autowired
    MachineDeployCenter machineDeployCenter;
    @Autowired
    TaskService taskService;
    @Autowired
    AssistRedisService assistRedisService;
    @Autowired
    private Configuration configuration;
    @Autowired
    private AppService appService;
    @Autowired
    private ResourceService resourceService;
    @Autowired
    private AppAlertRecordService appAlertRecordService;

    private final static String LINE_SEP = "\\n";
    private final static String FAIL = "fail";

    @Override
    public boolean startExistInstance(long appId, int instanceId) {
        AppDesc appDesc = appDao.getAppDescById(appId);
        if (!appDesc.isOnline()) {
            throw new BizException("该应用非上线状态，无法进行后续操作 appId={}", appId);
        }
        return startInstance(appId, instanceId);
    }

    public boolean startInstance(long appId, int instanceId) {
        Assert.isTrue(instanceId > 0L);
        InstanceInfo instanceInfo = instanceDao.getInstanceInfoById(instanceId);
        Assert.isTrue(instanceInfo != null);
        AppDesc appDesc = appDao.getAppDescById(appId);
        int type = instanceInfo.getType();
        String host = instanceInfo.getIp();
        int port = instanceInfo.getPort();
        // 获取redis路径
        SystemResource redisResource = resourceService.getResourceById(appDesc.getVersionId());
        String redisDir = redisResource == null ? ConstUtils.REDIS_DEFAULT_DIR : ConstUtils.getRedisDir(redisResource.getName());
        // Redis资源校验&推包
        Boolean installStatus = redisConfigTemplateService.checkAndInstallRedisResource(host, redisResource);
        if (!installStatus) {
            logger.info("{} is install :{}", host, redisResource.getName());
            return false;
        }
        //校验是否需要推送module
        if (TypeUtil.isRedisDataType(type)) {
            List<ModuleVersion> appToModuleList = appService.getAppToModuleList(appId);
            redisCenter.checkAndDownloadModule(host, appToModuleList);
        }
        boolean isRun;
        if (TypeUtil.isRedisType(type)) {
            if (TypeUtil.isRedisSentinel(type)) {
                isRun = redisCenter.isSentinelRun(appId, host, port);
            } else {
                isRun = redisCenter.isRun(appId, host, port);
            }
            if (isRun) {
                logger.warn("{}:{} instance is Running", host, port);
            } else {
                String runShell = "";
                if (TypeUtil.isRedisCluster(type)) {
                    runShell = redisDeployCenter.getRedisRunShell(true, host, port, redisDir);
                } else if (TypeUtil.isRedisSentinel(type)) {
                    runShell = redisDeployCenter.getSentinelRunShell(host, port, redisDir);
                } else {
                    runShell = redisDeployCenter.getRedisRunShell(false, host, port, redisDir);
                }
                boolean isRunShell = machineCenter.startProcessAtPort(host, port, runShell);
                if (!isRunShell) {
                    logger.error("startProcessAtPort-> {}:{} shell= {} failed", host, port, runShell);
                    return false;
                } else {
                    logger.warn("{}:{} instance has Run", host, port);
                }
                if (TypeUtil.isRedisSentinel(type)) {
                    isRun = redisCenter.isSentinelRun(appId, host, port);
                } else {
                    isRun = redisCenter.isRun(appId, host, port);
                }
            }
        } else {
            logger.error("type={} not match!", type);
            isRun = false;
        }
        if (isRun) {
            instanceInfo.setStatus(InstanceStatusEnum.GOOD_STATUS.getStatus());
            instanceDao.update(instanceInfo);
        }
        // 重置所有sentinel实例状态
        if (TypeUtil.isRedisSentinel(type)) {
            try {
                redisDeployCenter.sentinelReset(appId);
            } catch (Exception e) {
                logger.error("redis sentinel reset error :{}", e.getMessage(), e);
            }
        }

        return isRun;
    }

    @Override
    public boolean startExistInstanceWithoutResourceCheck(long appId, int instanceId) {
        Assert.isTrue(instanceId > 0L);
        InstanceInfo instanceInfo = instanceDao.getInstanceInfoById(instanceId);
        Assert.isTrue(instanceInfo != null);
        int type = instanceInfo.getType();
        String host = instanceInfo.getIp();
        int port = instanceInfo.getPort();
        // 获取redis路径
        SystemResource redisResource = resourceService.getResourceById(appDao.getAppDescById(appId).getVersionId());
        String redisDir = redisResource == null ? ConstUtils.REDIS_DEFAULT_DIR : ConstUtils.getRedisDir(redisResource.getName());
        boolean isRun;
        if (TypeUtil.isRedisType(type)) {
            if (TypeUtil.isRedisSentinel(type)) {
                isRun = redisCenter.isSentinelRun(appId,host, port);
            } else {
                isRun = redisCenter.isRun(appId, host, port);
            }
            if (isRun) {
                logger.warn("{}:{} instance is Running", host, port);
            } else {
                String runShell = "";
                if (TypeUtil.isRedisCluster(type)) {
                    runShell = redisDeployCenter.getRedisRunShell(true, host, port, redisDir);
                } else if (TypeUtil.isRedisSentinel(type)) {
                    runShell = redisDeployCenter.getSentinelRunShell(host, port, redisDir);
                } else {
                    runShell = redisDeployCenter.getRedisRunShell(false, host, port, redisDir);
                }
                boolean isRunShell = machineCenter.startProcessAtPort(host, port, runShell);
                if (!isRunShell) {
                    logger.error("startProcessAtPort-> {}:{} shell= {} failed", host, port, runShell);
                    return false;
                } else {
                    logger.warn("{}:{} instance has Run", host, port);
                }
                if (TypeUtil.isRedisSentinel(type)) {
                    isRun = redisCenter.isSentinelRun(appId,host, port);
                } else {
                    isRun = redisCenter.isRun(appId, host, port);
                }
            }
        } else {
            logger.error("type={} not match!", type);
            isRun = false;
        }
        if (isRun) {
            instanceInfo.setStatus(InstanceStatusEnum.GOOD_STATUS.getStatus());
            instanceDao.update(instanceInfo);
        }
        // 重置所有sentinel实例状态
        if (TypeUtil.isRedisSentinel(type)) {
            try {
                redisDeployCenter.sentinelReset(appId);
            } catch (Exception e) {
                logger.error("redis sentinel reset error :{}", e.getMessage(), e);
            }
        }
        return isRun;
    }

    @Override
    public boolean shutdownExistInstance(long appId, int instanceId) {
        Assert.isTrue(instanceId > 0L);
        InstanceInfo instanceInfo = instanceDao.getInstanceInfoById(instanceId);
        Assert.isTrue(instanceInfo != null);
        int type = instanceInfo.getType();
        String host = instanceInfo.getIp();
        int port = instanceInfo.getPort();
        boolean isShutdown;
        if (TypeUtil.isRedisType(type)) {
            if (TypeUtil.isRedisSentinel(type)) {
                isShutdown = redisCenter.shutdownSentinel(appId,host, port);
                // 重置reset sentinel实例状态
                try {
                    redisDeployCenter.sentinelReset(appId);
                } catch (Exception e) {
                    logger.error("redis sentinel reset error :{}", e.getMessage(), e);
                }
            } else {
                isShutdown = redisCenter.shutdown(appId, host, port);
            }
            if (isShutdown) {
                logger.warn("{}:{} redis is shutdown", host, port);
            } else {
                logger.error("{}:{} redis shutdown error", host, port);
            }
        } else {
            logger.error("type={} not match!", type);
            isShutdown = false;
        }

        //check shutdown success
        if (isShutdown) {
            isShutdown = redisCenter.checkShutdownSuccess(instanceInfo);
        }

        if (isShutdown) {
            instanceInfo.setStatus(InstanceStatusEnum.OFFLINE_STATUS.getStatus());
            instanceDao.update(instanceInfo);
        }
        return isShutdown;
    }

    @Override
    public boolean forgetInstance(long appId, int instanceId) {
        Assert.isTrue(instanceId >= 0L, "instanceId无效");
        InstanceInfo instanceInfo = instanceDao.getInstanceInfoById(instanceId);
        Assert.isTrue(instanceInfo != null, "没有instanceId=" + instanceId + "的instanceInfo");
        if (InstanceInfoEnum.InstanceTypeEnum.REDIS_CLUSTER.getType() == instanceInfo.getType()) {
            return forgetClusterInstance(appId, instanceInfo);
        }
        if (InstanceInfoEnum.InstanceTypeEnum.REDIS_SERVER.getType() == instanceInfo.getType()) {
            return forgetRedisServer(appId, instanceInfo);
        }
        if (InstanceInfoEnum.InstanceTypeEnum.REDIS_SENTINEL.getType() == instanceInfo.getType()) {
            return forgetSentinelServer(appId, instanceInfo);
        }
        // 之前这里直接 return false，调用方只能报一句「下线失败」，排查时看不出原因
        throw new BizException("不支持下线该类型节点，instanceId={}, type={}",
                instanceInfo.getId(), instanceInfo.getType());
    }

    private boolean forgetSentinelServer(long appId, InstanceInfo instanceInfo) {
        String host = instanceInfo.getIp();
        int port = instanceInfo.getPort();
        String nodeId = null;
        //获取应用下所有在线实例
        List<InstanceInfo> instanceList = appService.getAppOnlineInstanceInfo(appId);
        if (instanceList == null || instanceList.isEmpty()) {
            throw new BizException("该应用无剩余可用实例，无法进行下线操作，appId={}, instanceId={}", appId, instanceInfo.getId());
        }
        // 至少需要保留 3 个存活的哨兵。
        // 注意排除待下线的这一个：原先把它也算进来，3 个哨兵时删掉一个只剩 2 个，
        // 低于 quorum，恰好破坏了这条校验想守住的东西。
        int sentinelCount = 0;
        for (InstanceInfo instance : instanceList) {
            if (instance.getId() == instanceInfo.getId()) {
                continue;
            }
            if (InstanceInfoEnum.InstanceTypeEnum.REDIS_SENTINEL.getType() == instance.getType() &&
                    redisCenter.isRun(appId, instance.getIp(), instance.getPort())) {
                sentinelCount++;
            }
        }
        if (sentinelCount < 3) {
            throw new BizException("下线后剩余存活哨兵不足 3 个（当前剩余 {}），拒绝下线", sentinelCount);
        }
        instanceDao.updateStatus(appId, host, port, InstanceStatusEnum.FORGET_STATUS.getStatus());
        logger.warn("appId {} instanceId {} host {} port {} 永久下线.", appId, instanceInfo.getId(), host, port);
        return true;
    }

    private boolean forgetRedisServer(long appId, InstanceInfo instanceInfo) {
        String host = instanceInfo.getIp();
        int port = instanceInfo.getPort();
        String nodeId = null;
        //获取应用下所有在线实例
        List<InstanceInfo> instanceList = appService.getAppOnlineInstanceInfo(appId);
        if (instanceList == null || instanceList.isEmpty()) {
            throw new BizException("该应用无剩余可用实例，无法进行下线操作，appId={}, instanceId={}", appId, instanceInfo.getId());
        }
        // 必须排除待下线节点本身：否则删最后一个节点时它自己就是 firstRunning，
        // 校验形同虚设，集群会被删空。
        InstanceInfo firstRunning = null;
        for (InstanceInfo instance : instanceList) {
            if (instance.getId() == instanceInfo.getId()) {
                continue;
            }
            if (InstanceInfoEnum.InstanceTypeEnum.REDIS_SERVER.getType() == instance.getType()
                    && redisCenter.isRun(appId, instance.getIp(), instance.getPort())) {
                firstRunning = instance;
                break;
            }
        }
        if (firstRunning == null) {
            throw new BizException("下线后集群将没有可用节点，拒绝下线");
        }
        instanceDao.updateStatus(appId, host, port, InstanceStatusEnum.FORGET_STATUS.getStatus());
        logger.warn("appId {} instanceId {} host {} port {} 永久下线.", appId, instanceInfo.getId(), host, port);
        return true;
    }

    private boolean forgetClusterInstance(long appId, InstanceInfo instanceInfo) {
        String host = instanceInfo.getIp();
        int port = instanceInfo.getPort();
        String nodeId = null;
        //获取应用下所有在线实例
        List<InstanceInfo> instanceList = appService.getAppOnlineInstanceInfo(appId);
        if (instanceList == null || instanceList.isEmpty()) {
            throw new BizException("该应用无剩余可用实例，无法进行下线操作，appId={}, instanceId={}", appId, instanceInfo.getId());
        }
        //找到一个运行的节点用来执行cluster nodes
        // 同样要排除待 forget 的节点：节点无法 FORGET 自己，拿它当执行方必然失败
        InstanceInfo firstRunning = null;
        for (InstanceInfo instance : instanceList) {
            if (instance.getId() == instanceInfo.getId()) {
                continue;
            }
            //todo: 节点cluster node不能包含handshake？否则nodeId一直在变
            if (redisCenter.isRun(appId, instance.getIp(), instance.getPort())) {
                firstRunning = instance;
                break;
            }
        }
        if (firstRunning == null) {
            throw new BizException("除待下线节点外没有其它存活节点，拒绝下线");
        }

        //先获取被forget节点的node id(cluster myid), 从其他在线节点拿到cluster nodes
        String clusterNodes = redisCenter.getClusterNodes(appId, firstRunning.getIp(), firstRunning.getPort());
        nodeId = getNodeIdFromClusterNodes(clusterNodes, host, port);
        logger.warn("app {} instance {}:{} instanceId {} cluster node id {} will be forgot. cluster nodes\n{}", appId, host, port,
                instanceInfo.getId(), nodeId, clusterNodes);
        if (StringUtils.isNotBlank(nodeId)) {
            int forgetCount = 0;
            String instanceIp;
            int instancePort;
            for (InstanceInfo instanceInfo1 : instanceList) {
                instanceIp = instanceInfo1.getIp();
                instancePort = instanceInfo1.getPort();
                if (redisCenter.forget(appId, instanceIp, instancePort, nodeId)) {
                    forgetCount++;
                    logger.warn("instance {}:{} in app {} forget instance {}:{}-{} successfully", instanceIp,
                            instancePort, appId, host, port, nodeId);
                } else {
                    logger.error("instance {}:{} in app {} forget instance {}:{}-{} failed", instanceIp,
                            instancePort, appId, host, port, nodeId);
                }
            }
            if (forgetCount == instanceList.size()) { //所有在线节点forget成功
                instanceDao.updateStatus(appId, host, port, InstanceStatusEnum.FORGET_STATUS.getStatus());
                logger.warn("app {} forget&update instance {}:{} successfully. current cluster nodes\n{}", appId,
                        host, port, redisCenter.getClusterNodes(appId, firstRunning.getIp(), firstRunning.getPort()));
                return true;
            }
        } else {        //cluster nodes已经没有该节点，则直接将数据库状态改为InstanceStatusEnum.FORGET_STATUS.getStatus()
            instanceDao.updateStatus(appId, host, port, InstanceStatusEnum.FORGET_STATUS.getStatus());
            logger.warn("app {} update instance(nodeId is null) {}:{} successfully. current cluster nodes\n{}", appId,
                    host, port, redisCenter.getClusterNodes(appId, firstRunning.getIp(), firstRunning.getPort()));
            return true;
        }
        return false;
    }

    @Override
    public boolean clearFailInstances(long appId) {
        try {
            //验证app类型是cluster
            AppDesc appDesc = appService.getByAppId(appId);
            if (appDesc.getType() != ConstUtils.CACHE_TYPE_REDIS_CLUSTER) {
                logger.warn("app {} is not cluster!", appId);
                return false;
            }
            //找到一个运行的节点用来执行cluster nodes
            List<InstanceInfo> instanceList = appService.getAppOnlineInstanceInfo(appId);
            InstanceInfo firstRun = null;
            for (InstanceInfo instanceInfo : instanceList) {
                if (redisCenter.isRun(appId, instanceInfo.getIp(), instanceInfo.getPort())) {
                    firstRun = instanceInfo;
                    break;
                }
            }
            if (firstRun == null) {
                logger.warn("app {} can not find an running instance!", appId);
                return false;
            }
            String clusterNodes = redisCenter.getClusterNodes(appId, firstRun.getIp(), firstRun.getPort());     //拿到cluster nodes
            if (StringUtils.isNotBlank(clusterNodes)) {
                String nodeId, hostPort, host, role;
                int port, total = 0;      //total统计该应用共forget的节点数
                String[] lines = clusterNodes.split(LINE_SEP);
                if (lines != null && lines.length > 0) {
                    for (String line : lines) {
                        if (line.contains(FAIL)) {        //todo:其他判断条件
                            int count = 0;
                            String[] items = line.split(" ");
                            if (items != null && items.length > 0) {
                                nodeId = items[0];
                                hostPort = items[1].split("@")[0];
                                host = hostPort.split(":")[0];
                                port = Integer.parseInt(hostPort.split(":")[1]);
                                role = items[2];
                                for (InstanceInfo instance : instanceList) {
                                    if (redisCenter.forget(appId, instance.getIp(), instance.getPort(), nodeId)) {
                                        count++;
                                    }
                                }
                                if (count == instanceList.size()) {   //所有节点都forget了该nodeId
                                    total++;
                                    instanceDao.updateStatus(appId, host, port, InstanceStatusEnum.FORGET_STATUS.getStatus());
                                    logger.warn("instance {}:{} id {} role {} was forgot by all online instances", host, port, nodeId, role);
                                }
                            }
                        }
                    }
                }
                clusterNodes = redisCenter.getClusterNodes(appId, firstRun.getIp(), firstRun.getPort());     //拿到cluster nodes
                logger.warn("app {} clear total {} fail nodes. current cluster nodes\n{}", appId, total, clusterNodes);
                return true;
            }
        } catch (Exception e) {
            logger.error("app {} clear fail nodes error!", appId, e);
        }
        return false;
    }

    private String getNodeIdFromClusterNodes(String clusterNodes, String host, int port) {
        String nodeId = null;
        String hostPort = host + ":" + port;
        if (StringUtils.isNotBlank(clusterNodes)) {
            String[] lines = clusterNodes.split(LINE_SEP);
            if (lines != null && lines.length > 0) {
                for (String line : lines) {
                    if (line.contains(hostPort)) {
                        String[] items = line.split(" ");
                        if (items != null && items.length > 0) {
                            nodeId = items[0];
                            break;
                        }
                    }
                }
            }
        }
        return nodeId;
    }

    @Override
    public String showInstanceRecentLog(int instanceId, int maxLineNum) {
        Assert.isTrue(instanceId > 0L);
        InstanceInfo instanceInfo = instanceDao.getInstanceInfoById(instanceId);
        Assert.isTrue(instanceInfo != null);
        try {
            return machineCenter.showInstanceRecentLog(instanceInfo, maxLineNum);
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            return "";
        }
    }

    @Override
    public boolean modifyInstanceConfig(long appId, Long appAuditId, String host, int port, String instanceConfigKey,
                                        String instanceConfigValue) {
        Assert.isTrue(StringUtils.isNotBlank(host));
        Assert.isTrue(port > 0);
        Assert.isTrue(StringUtils.isNotBlank(instanceConfigKey));
        boolean isModify = redisDeployCenter.modifyInstanceConfig(appId, host, port, instanceConfigKey, instanceConfigValue);
        if (isModify && appAuditId != null) {
            // 改变审核状态
            appAuditDao.updateAppAudit(appAuditId, AppCheckEnum.APP_ALLOCATE_RESOURCE.value());
        }
        return isModify;
    }

    @Override
    public MachineSyncEnum podChangeStatus(String ip) {

        // 获取pod变更历史
        MachineSyncEnum syncEnum = null;
        List<MachineRelation> relationList = machineRelationDao.getOnlinePodList(ip, PodStatusEnum.ONLINE.getValue());
        if (CollectionUtils.isEmpty(relationList) || relationList.size() == 1) {
            return MachineSyncEnum.NO_CHANGE;
        } else {
            // 1.当前pod的变更信息
            MachineRelation current_podinfo = relationList.get(0);
            // 2.上一次pod的变更信息
            MachineRelation pre_podinfo = relationList.get(1);
            // 3.判定宿主机是否变更
            String sourceIp = pre_podinfo.getRealIp();//源宿主机
            String targetIp = current_podinfo.getRealIp();//目标宿主机
            if (!sourceIp.equals(targetIp)) {
                int relationId = pre_podinfo.getId();

                String key = String.format("%s-%s", sourceIp, targetIp);
                try {
                    // 3.1 保证只有同一个宿主机/目标宿主机执行同步任务 (多个pod ip对应一个宿主机)
                    boolean setlock = assistRedisService.setNx(key, "sync");
                    if (setlock) {
                        long taskId = taskService.addMachineSyncTask(sourceIp, targetIp, ip, String.format("sourceMachine:%s tagetMachine:%s ", sourceIp, targetIp), 0);
                        if (taskId > 0) {
                            logger.info("add machine sync task tashid:{} ", taskId);
                            machineDeployCenter.updateMachineRelation(relationId, taskId, MachineTaskEnum.SYNCING.getValue());
                            // 3.2 探测 taskId 执行情况  10s轮训一次
                            long start = System.currentTimeMillis();

                            while (true) {
                                TaskQueue taskQueue = taskService.getTaskQueueById(taskId);
                                if (taskQueue.getStatus() == TaskQueueEnum.TaskStatusEnum.SUCCESS.getStatus()) {
                                    // a).同步任务执行完成退出
                                    syncEnum = MachineSyncEnum.SYNC_SUCCESS;
                                    break;
                                }
                                if (taskQueue.getStatus() == TaskQueueEnum.TaskStatusEnum.ABORT.getStatus()) {
                                    // b).机器同步数据异常 发送邮件通知管理员
                                    syncEnum = MachineSyncEnum.SYNC_ABORT;
                                    break;
                                }
                                logger.info("machine sync taskid:{} status:{} waitting .... sleep 10s ", taskId, taskQueue.getStatus());
                                TimeUnit.SECONDS.sleep(10);
                            }
                            String emailTitle = String.format("Pod重启机器同步任务报警");
                            String emailContent = String.format("Pod ip:%s 发生重启, 宿主机发生变更[%s]->[%s],机器同步任务id:(%s) 状态:(%s), 数据同步时间开销:(%s s)", ip, sourceIp, targetIp, taskId, syncEnum.getDesc(), (System.currentTimeMillis() - start) / 1000);
                            appAlertRecordService.saveAlertInfoByType(AlertTypeEnum.POD_RESTART_SYNC_TASK, emailTitle, emailContent, ip);
                            emailComponent.sendMailToAdmin(emailTitle, emailContent);
                        }
                    } else {
                        return MachineSyncEnum.SYNC_EXECUTING;
                    }
                } catch (Exception e) {
                    logger.error("machine sync get lock error :{}", e.getMessage(), e);
                    return MachineSyncEnum.SYNC_ERROR;
                } finally {
                    assistRedisService.del(key);
                }
            } else {
                syncEnum = MachineSyncEnum.NO_CHANGE;
                String emailTitle = String.format("Pod重启机器同步任务报警");
                String emailContent = String.format("Pod ip:%s 发生重启, 宿主机未变更[%s]->[%s] 状态:(%s)", ip, sourceIp, targetIp, syncEnum.getDesc());
                appAlertRecordService.saveAlertInfoByType(AlertTypeEnum.POD_RESTART_SYNC_TASK, emailTitle, emailContent, ip);
                emailComponent.sendMailToAdmin(emailTitle, emailContent);
            }
            return syncEnum;
        }
    }

    @Override
    public List<InstanceAlertValueResult> checkAndStartExceptionInstance(String ip, Boolean isAlert) {

        // 1.获取容器心跳停止/运行中的实例列表
        List<InstanceInfo> instanceInfos = instanceDao.checkHeartStopInstance(ip);
        List<InstanceAlertValueResult> recoverInstInfo = new ArrayList<>();
        // 2.滚动检测实例列表
        if (!CollectionUtils.isEmpty(instanceInfos)) {
            for (InstanceInfo instance : instanceInfos) {
                long appId = instance.getAppId();
                int instanceId = instance.getId();
                String host = instance.getIp();
                int port = instance.getPort();
                int type = instance.getType();

                logger.info("checkAndStartExceptionInstance scroll check instance {}:{} ", host, port);
                //2.1 检测实例是否启动
                if (redisCenter.isRun(appId, ip, port)) {
                    continue;
                }
                // 2.2 异常实例恢复
                startExistInstance(appId, instanceId);
                // 2.3 检测异常实例启动成功 & 加载完成数据 & 启动下一个实例
                Jedis jedis = null;
                int retry = 1;//重试次数
                try {
                    if (TypeUtil.isRedisSentinel(type)) {
                        jedis = redisCenter.getSentinelJedis(appId,host, port);
                    } else {
                        jedis = redisCenter.getJedis(appId, host, port);
                    }
                    while (true) {
                        // 等待节点加载数据 & PONG
                        String ping = "";
                        try {
                            ping = jedis.ping();
                        } catch (JedisDataException e) {
                            String message = e.getMessage();
                            logger.warn(e.getMessage());
                            if (StringUtils.isNotBlank(message) && message.startsWith("LOADING")) {
                                logger.warn("scroll restart {}:{} waiting loading data ,sleep 2s", host, port);
                                TimeUnit.SECONDS.sleep(2);
                            }
                        } catch (Exception e) {
                            logger.error("scroll restart {}:{} ping exception sleep 200ms :{} ", host, port, e.getMessage(), e);
                            TimeUnit.MILLISECONDS.sleep(200);
                        }
                        if ("PONG".equalsIgnoreCase(ping) || retry++ >= 15) {
                            InstanceAlertValueResult instanceAlertValueResult = new InstanceAlertValueResult();
                            instanceAlertValueResult.setInstanceInfo(instance);
                            instanceAlertValueResult.setOtherInfo(DateUtil.formatYYYYMMddHHMMSS(new Date()));//实例恢复时间

                            recoverInstInfo.add(instanceAlertValueResult);
                            break;
                        }
                    }
                } catch (Exception e) {
                    logger.error("checkAndStartExceptionInstance {}:{} {} ", host, port, e.getMessage(), e);
                } finally {
                    if (jedis != null) {
                        try {
                            jedis.close();
                        } catch (Exception e) {
                            logger.error("jedis close exception {}:{} {} ", host, port, e.getMessage(), e);
                        }
                    }
                }
            }

            if (isAlert) {
                // 邮件通知：实例信息 恢复时间
                String emailTitle = String.format("Pod重启探测Redis实例报警");
                Map<String, Object> context = new HashMap<>();
                context.put("instanceAlertValueResultList", recoverInstInfo);
                String emailContent = FreemakerUtils.createText("instanceRecover.ftl", configuration, context);
                appAlertRecordService.saveAlertInfoByType(AlertTypeEnum.POD_RESTART_INSTANCE_RECOVER, emailTitle, null, recoverInstInfo);
                emailComponent.sendMailToAdmin(emailTitle, emailContent.toString());
            }
        } else {
            logger.info("checkAndStartExceptionInstance ip:{} has instances is empty ", ip);
        }
        return recoverInstInfo;
    }
}
