package com.shcj.cache.task;

import com.google.common.collect.Lists;
import com.shcj.cache.constant.AppCheckEnum;
import com.shcj.cache.constant.AppStatusEnum;
import com.shcj.cache.exception.BizException;
import com.shcj.cache.dao.*;
import com.shcj.cache.entity.*;
import com.shcj.cache.machine.MachineCenter;
import com.shcj.cache.protocol.RedisProtocol;
import com.shcj.cache.redis.AssistRedisService;
import com.shcj.cache.redis.RedisCenter;
import com.shcj.cache.redis.RedisConfigTemplateService;
import com.shcj.cache.redis.RedisDeployCenter;
import com.shcj.cache.ssh.SSHService;
import com.shcj.cache.ssh.SSHTemplate;
import com.shcj.cache.stats.app.AppStatsCenter;
import com.shcj.cache.stats.instance.InstanceDeployCenter;
import com.shcj.cache.task.constant.InstanceInfoEnum;
import com.shcj.cache.task.constant.InstanceInfoEnum.InstanceStatusEnum;
import com.shcj.cache.task.constant.InstanceInfoEnum.InstanceTypeEnum;
import com.shcj.cache.task.constant.TaskConstants;
import com.shcj.cache.task.constant.TaskStepFlowEnum.TaskFlowStatusEnum;
import com.shcj.cache.task.entity.NutCrackerNode;
import com.shcj.cache.task.entity.RedisSentinelNode;
import com.shcj.cache.task.entity.RedisServerNode;
import com.shcj.cache.task.entity.TaskQueue;
import com.shcj.cache.task.util.AppWechatUtil;
import com.shcj.cache.web.service.AppService;
import com.shcj.cache.web.service.InstancePortService;
import com.shcj.cache.web.service.ModuleService;
import com.shcj.cache.web.service.ResourceService;
import com.shcj.cache.web.util.AppEmailUtil;
import com.shcj.cache.web.util.SimpleFileUtil;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang3.time.DateUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.Marker;
import org.slf4j.MarkerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;

import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.TimeUnit;

/**
 * @author fulei
 */
public abstract class BaseTask {

    protected Logger logger = LoggerFactory.getLogger(this.getClass());

    public final static String MARKER_NAME = "task_logger";

    public final static Marker marker = MarkerFactory.getMarker(MARKER_NAME);

    @Value("${spring.profiles.active}")
    protected String appEnvName;

    /**
     * 任务参数
     */
    protected Map<String, Object> paramMap;

    @Autowired
    protected InstanceBigKeyDao instanceBigKeyDao;

    @Autowired
    protected InstanceStatsDao instanceStatsDao;

    @Autowired
    protected AppEmailUtil appEmailUtil;

    @Autowired
    protected AppWechatUtil appWechatUtil;

    @Autowired
    protected MachineCenter machineCenter;

    @Autowired
    protected RedisConfigTemplateService redisConfigTemplateService;

    @Autowired
    protected AppService appService;

    @Autowired
    protected AppStatsCenter appStatsCenter;

    @Autowired
    protected SSHService sshService;

    @Autowired
    protected AppDao appDao;

    @Autowired
    protected RedisCenter redisCenter;

    @Autowired
    protected MachineDao machineDao;

    @Autowired
    protected InstanceDao instanceDao;

    @Autowired
    protected AppAuditDao appAuditDao;

    @Autowired
    protected TaskService taskService;

    @Autowired
    protected RedisDeployCenter redisDeployCenter;

    @Autowired
    protected MachineRoomDao machineRoomDao;

    @Autowired
    protected MachineStatsDao machineStatsDao;

    @Autowired
    protected MachineRelationDao machineRelationDao;

    @Autowired
    protected AssistRedisService assistRedisService;

    @Autowired
    protected InstancePortService instancePortService;

    @Autowired
    protected Environment environment;

    @Autowired
    protected InstanceDeployCenter instanceDeployCenter;

    @Autowired
    protected DiagnosticTaskRecordDao diagnosticTaskRecordDao;

    @Autowired
    protected ResourceService resourceService;

    @Autowired
    protected ResourceDao resourceDao;

    @Autowired
    protected ModuleService moduleService;

    /**
     * 任务id
     */
    protected long taskId;

    /**
     * redis common启动模板文件
     */
    private final static String REDIS_COMMON_TEMPLATE_FILE = "scripts/redis_common_control.txt";
    private final static String REDIS_COMMON_TEMPLATE_NO_CPUIDX_FILE = "scripts/redis_common_control_no_cpuidx.txt";

    /**
     * nutcracker启动模板文件
     */
    private final static String NUT_CRACKER_TEMPLATE_FILE = "scripts/nutcracker_control.txt";
    private final static String NUT_CRACKER_TEMPLATE_NO_CPUIDX_FILE = "scripts/nutcracker_control_no_cpuidx.txt";

    /**
     * redis port启动模板文件
     */
    private final static String REDIS_PORT_TEMPLATE_FILE = "scripts/redis-port.txt";

    /**
     * redis migrate启动模板文件
     */
    private final static String REDIS_MIGRATE_TOOL_TEMPLATE_FILE = "scripts/redis-migrate-tool.txt";

    /**
     * pika启动模板文件
     */
    private final static String PIKA_TEMPLATE_FILE = "scripts/pika.txt";

    public abstract List<String> getTaskSteps();

    public TaskFlowStatusEnum init() {
        taskId = MapUtils.getLongValue(paramMap, TaskConstants.TASK_ID_KEY);
        return TaskFlowStatusEnum.SUCCESS;
    }

    public Map<String, Object> getParamMap() {
        return paramMap;
    }

    public void setParamMap(Map<String, Object> paramMap) {
        this.paramMap = paramMap;
    }

    /**
     * 准备实例相关目录
     *
     * @param appId
     * @param host
     * @param port
     * @param instanceTypeEnum
     * @return
     */
    protected TaskFlowStatusEnum prepareRelateDir(long appId, String host, int port,
                                                  InstanceInfoEnum.InstanceTypeEnum instanceTypeEnum) {
        //准备实例基准目录 todo
        return TaskFlowStatusEnum.SUCCESS;
    }

    /**
     * @param cpuidx
     * @param startCmd
     * @param runCmd
     * @param serviceShellFileName
     * @return
     */
    private List<String> generateRedisPortServiceShell(int cpuidx, String startCmd, String runCmd,
                                                       String serviceShellFileName) {
        Map<String, String> replaceMap = new HashMap<String, String>();
        replaceMap.put("${cpuidx}", String.valueOf(cpuidx));
        replaceMap.put("${startcmd}", startCmd);
        replaceMap.put("${runcmd}", runCmd);
        replaceMap.put("${PID_FILE}", RedisProtocol.getRedisPortPidFilePath());

        List<String> resultList = generateRealShell(REDIS_PORT_TEMPLATE_FILE, replaceMap);
        if (CollectionUtils.isEmpty(resultList)) {
            logger.error(marker, "{} {} {} service shell is empty!", cpuidx, startCmd, runCmd);
        }
        return resultList;
    }

    /**
     * 生成实际shell
     *
     * @param replaceMap
     * @return
     */
    private List<String> generateRealShell(String templateFile, Map<String, String> replaceMap) {
        List<String> resultList = new ArrayList<String>();
        List<String> lines = SimpleFileUtil.getListFromFile(templateFile, "utf-8");
        for (String line : lines) {
            for (Entry<String, String> entry : replaceMap.entrySet()) {
                String key = entry.getKey();
                String value = entry.getValue();
                if (line.contains(key)) {
                    line = line.replace(key, value);
                }
            }
            resultList.add(line);
        }
        return resultList;
    }

    /**
     * @param host
     * @param cpuidx
     * @param startCmd
     * @param runCmd
     * @param serviceShellFileName
     * @return
     */
    private List<String> generateRedisCommonServiceShell(String host, int cpuidx, String startCmd, String runCmd,
                                                         String serviceShellFileName) {
        Map<String, String> replaceMap = new HashMap<String, String>();
        replaceMap.put("${cpuidx}", String.valueOf(cpuidx));
        replaceMap.put("${startcmd}", startCmd);
        replaceMap.put("${runcmd}", runCmd);

        MachineInfo machineInfo = machineDao.getMachineInfoByIp(host);
        if (machineInfo == null) {
            logger.error(marker, "machine {} is empty", host);
            return Collections.emptyList();
        }

        String templateFile = machineInfo.isYunMachine() ?
                REDIS_COMMON_TEMPLATE_NO_CPUIDX_FILE :
                REDIS_COMMON_TEMPLATE_FILE;
        List<String> resultList = generateRealShell(templateFile, replaceMap);
        if (CollectionUtils.isEmpty(resultList)) {
            logger.error(marker, "{} {} {} service shell is empty!", cpuidx, startCmd, runCmd);
        }
        return resultList;
    }

    /**
     * @param host
     * @param cpuidx
     * @param startCmd
     * @param runCmd
     * @param serviceShellFileName
     * @return
     */
    private List<String> generateNutCrackerServiceShell(String host, int cpuidx, String startCmd, String runCmd,
                                                        String serviceShellFileName) {
        Map<String, String> replaceMap = new HashMap<String, String>();
        replaceMap.put("${cpuidx}", String.valueOf(cpuidx));
        replaceMap.put("${startcmd}", startCmd);
        replaceMap.put("${runcmd}", runCmd);

        MachineInfo machineInfo = machineDao.getMachineInfoByIp(host);
        if (machineInfo == null) {
            logger.error(marker, "machine {} is empty", host);
            return Collections.emptyList();
        }

        String templateFile = machineInfo.isYunMachine() ?
                NUT_CRACKER_TEMPLATE_NO_CPUIDX_FILE :
                NUT_CRACKER_TEMPLATE_FILE;
        List<String> resultList = generateRealShell(templateFile, replaceMap);
        if (CollectionUtils.isEmpty(resultList)) {
            logger.error(marker, "{} {} {} service shell is empty!", cpuidx, startCmd, runCmd);
        }
        return resultList;
    }

    /**
     * @param cpuidx
     * @param startCmd
     * @param runCmd
     * @param serviceShellFileName
     * @return
     */
    private List<String> generateRedisMigrateServiceShell(int cpuidx, String startCmd, String runCmd,
                                                          String serviceShellFileName) {
        Map<String, String> replaceMap = new HashMap<String, String>();
        replaceMap.put("${cpuidx}", String.valueOf(cpuidx));
        replaceMap.put("${startcmd}", startCmd);
        replaceMap.put("${runcmd}", runCmd);

        List<String> resultList = generateRealShell(REDIS_MIGRATE_TOOL_TEMPLATE_FILE, replaceMap);
        if (CollectionUtils.isEmpty(resultList)) {
            logger.error(marker, "{} {} {} service shell is empty!", cpuidx, startCmd, runCmd);
        }
        return resultList;
    }

    /**
     * @param cpuidx
     * @param startCmd
     * @param runCmd
     * @param serviceShellFileName
     * @return
     */
    private List<String> generatePikaServiceShell(int cpuidx, String startCmd, String runCmd,
                                                  String serviceShellFileName) {
        Map<String, String> replaceMap = new HashMap<String, String>();
        replaceMap.put("${cpuidx}", String.valueOf(cpuidx));
        replaceMap.put("${startcmd}", startCmd);
        replaceMap.put("${runcmd}", runCmd);

        List<String> resultList = generateRealShell(PIKA_TEMPLATE_FILE, replaceMap);
        if (CollectionUtils.isEmpty(resultList)) {
            logger.error(marker, "{} {} {} service shell is empty!", cpuidx, startCmd, runCmd);
        }
        return resultList;
    }

    /**
     * 生成并推service
     *
     * @param appId
     * @param host
     * @param port
     * @param instanceRemoteBasePath
     * @param instanceLocalTmpPath
     * @param cpuidx
     * @param startCmd
     * @param runCmd
     * @param serviceShellFileName
     * @return
     */
    protected TaskFlowStatusEnum pushService(long appId, InstanceTypeEnum instanceTypeEnum, String host, int port,
                                             String instanceRemoteBasePath,
                                             String instanceLocalTmpPath, int cpuidx, String startCmd, String runCmd, String serviceShellFileName) {
        //生成和推送脚本
        List<String> serviceShell;
        if (instanceTypeEnum.equals(InstanceTypeEnum.REDIS_PORT)) {
            serviceShell = generateRedisPortServiceShell(cpuidx, startCmd, runCmd, serviceShellFileName);
        } else if (instanceTypeEnum.equals(InstanceTypeEnum.REDIS_MIGRATE_TOOL)) {
            serviceShell = generateRedisMigrateServiceShell(cpuidx, startCmd, runCmd, serviceShellFileName);
        } else if (instanceTypeEnum.equals(InstanceTypeEnum.NUTCRACKER)) {
            serviceShell = generateNutCrackerServiceShell(host, cpuidx, startCmd, runCmd, serviceShellFileName);
        } else if (instanceTypeEnum.equals(InstanceTypeEnum.PIKA)) {
            serviceShell = generatePikaServiceShell(cpuidx, startCmd, runCmd, serviceShellFileName);
        } else {
            serviceShell = generateRedisCommonServiceShell(host, cpuidx, startCmd, runCmd, serviceShellFileName);
        }

        String remoteFile = machineCenter.createRemoteFile(host, serviceShellFileName, serviceShell);
        if (StringUtils.isBlank(remoteFile)) {
            logger.error(marker, "{} {}:{} {} push fail", appId, host, port, remoteFile);
            return TaskFlowStatusEnum.ABORT;
        }

        //修改执行权限
        //todo
        //ChmodEnum chmodEnum = ChmodEnum.EXECUTE;
        //String remoteFilePath = instanceRemoteBasePath + "/" + serviceShellFileName;
        //boolean isSuccess = machineCenter.chmod(host, remoteFilePath, chmodEnum);
        //        if (!isSuccess) {
        //            logger.error(marker, "{} {}:{} {} chmod +x fail", appId, host, port, remoteFilePath);
        //            return TaskFlowStatusEnum.ABORT;
        //        }
        return TaskFlowStatusEnum.SUCCESS;
    }

    /**
     * 准备相关bin
     *
     * @param appId
     * @param host
     * @param port
     * @param instanceTypeEnum
     * @param version
     * @return
     */
    protected TaskFlowStatusEnum prepareRelateBin(long appId, String host, int port, InstanceTypeEnum instanceTypeEnum,
                                                  String version) {

        AppDesc appDesc = appService.getByAppId(appId);
        if (appDesc == null) {
            logger.error(marker, "appId {} get appDesc is empty", appId);
            return TaskFlowStatusEnum.ABORT;
        }
        return TaskFlowStatusEnum.SUCCESS;
    }

    /**
     * 准备相关bin
     *
     * @param appId
     * @param host
     * @param port
     * @return
     */
    protected TaskFlowStatusEnum preparePikaBin(long appId, String host, int port, InstanceTypeEnum instanceTypeEnum) {
        // todo
        return TaskFlowStatusEnum.SUCCESS;
    }

    /**
     * 实例进程是否存在
     *
     * @param appId
     * @param host
     * @param port
     * @param instanceTypeEnum
     * @return
     */
    protected TaskFlowStatusEnum checkInstanceIsExist(long appId, String host, int port,
                                                      InstanceTypeEnum instanceTypeEnum) {
        //1. 检查目录是否存在
        //        String remoteBasePath = machineCenter.getInstanceRemoteBasePath(appId, port, instanceTypeEnum);
        //        if (StringUtils.isBlank(remoteBasePath)) {
        //            logger.error(marker, "appId {} host {} port {} remoteBasePath is empty", appId, host, port);
        //            return TaskFlowStatusEnum.ABORT;
        //        }
        //        boolean isExist = machineCenter.checkExistDir(host, remoteBasePath);
        //        if (isExist) {
        //            logger.error(marker, "{} {} already exists", host, remoteBasePath);
        //            return TaskFlowStatusEnum.ABORT;
        //        }
        //2. 检查进程是否存在
        if (instanceTypeEnum.equals(InstanceTypeEnum.REDIS_PORT)) {
            if (redisCenter.isRun(host, port)) {
                throw new BizException("{} {}:{} is already run", instanceTypeEnum.getInfo(), host, port);
            }
        } else if (instanceTypeEnum.equals(InstanceTypeEnum.REDIS_SERVER)
                || instanceTypeEnum.equals(InstanceTypeEnum.REDIS_CLUSTER)
                || instanceTypeEnum.equals(InstanceTypeEnum.REDIS_SENTINEL)
                || instanceTypeEnum.equals(InstanceTypeEnum.PIKA)
                || instanceTypeEnum.equals(InstanceTypeEnum.NUTCRACKER)
                || instanceTypeEnum.equals(InstanceTypeEnum.CODIS_SERVER)
                || instanceTypeEnum.equals(InstanceTypeEnum.CODIS_PROXY)) {
            if (redisCenter.isRun(host, port)) {
                throw new BizException("{} {}:{} is already run", instanceTypeEnum.getInfo(), host, port);
            }
        }
        return TaskFlowStatusEnum.SUCCESS;
    }

    /**
     * 实例进程是否不存在（不和上面写一起了，虽然冗余了）
     *
     * @param appId
     * @param host
     * @param port
     * @param instanceTypeEnum
     * @return
     */
    protected TaskFlowStatusEnum checkInstanceIsNotExist(long appId, String host, int port,
                                                         InstanceTypeEnum instanceTypeEnum) {
        //todo
        return TaskFlowStatusEnum.SUCCESS;
    }

    /**
     * 保存实例信息
     *
     * @param appId
     * @param host
     * @param port
     * @param maxMemory
     * @param instanceTypeEnum
     * @param instanceStatusEnum
     * @param cmd
     * @return
     */
    protected Integer saveInstance(long appId, String host, int port, int maxMemory,
                                   InstanceTypeEnum instanceTypeEnum, InstanceStatusEnum instanceStatusEnum, String cmd) {
        InstanceInfo instanceInfo;
        try {
            instanceInfo = new InstanceInfo();
            instanceInfo.setAppId(appId);
            MachineInfo machineInfo = machineDao.getMachineInfoByIp(host);
            if (machineInfo == null) {
                throw new BizException("host {} machineInfo is empty", host);
            }
            instanceInfo.setHostId(machineInfo.getId());
            instanceInfo.setMem(maxMemory);
            instanceInfo.setStatus(InstanceStatusEnum.NEW_STATUS.getStatus());
            instanceInfo.setPort(port);
            instanceInfo.setType(instanceTypeEnum.getType());
            instanceInfo.setCmd(cmd);
            instanceInfo.setIp(host);

            instanceDao.saveInstance(instanceInfo);
            logger.info(marker, "{} {}:{} save success", instanceTypeEnum.getInfo(), host, port);
            return instanceInfo.getId();
        } catch (Exception e) {
            throw new BizException("{} {}:{} save error" + e.getMessage(), instanceTypeEnum.getInfo(), host, port, e);
        }
    }

    /**
     * low b 版本
     *
     * @param currentTaskId
     * @param timeoutSeconds 超时时间
     * @return
     */
    protected TaskFlowStatusEnum waitTaskFinish(long currentTaskId, int timeoutSeconds) {
        // 耗时
        int totalSeconds = 0;
        while (true) {
            // 简单的计时器
            long startTime = System.currentTimeMillis();
            TaskQueue taskQueue = taskService.getTaskQueueById(currentTaskId);
            logger.warn("task {} totalSeconds is {}, timeout is {}, status:{}", currentTaskId, totalSeconds, timeoutSeconds, taskQueue.getStatusDesc());
            if (taskQueue.isSuccess()) {
                return TaskFlowStatusEnum.SUCCESS;
            } else if (taskQueue.isAbort()) {
                return TaskFlowStatusEnum.ABORT;
            }
            try {
                TimeUnit.SECONDS.sleep(1);
            } catch (InterruptedException e) {
                logger.error(marker, e.getMessage(), e);
            }
            // 简单的计时器(秒)
            long costTime = System.currentTimeMillis() - startTime;
            totalSeconds += (costTime / 1000);
            if (totalSeconds > timeoutSeconds) {
                return TaskFlowStatusEnum.ABORT;
            }
        }
    }

    protected void sleepSeconds(int seconds) {
        try {
            TimeUnit.SECONDS.sleep(seconds);
        } catch (InterruptedException e) {
            logger.error(marker, e.getMessage(), e);
        }
    }

    /**
     * 获取所有master节点
     *
     * @return
     */
    protected List<RedisServerNode> getAppMasterRedisServerNodes(long appId) {
        AppDesc appDesc = appDao.getAppDescById(appId);
        String appFullName = appDesc.getName();
        if (StringUtils.isBlank(appFullName)) {
            logger.error(marker, "appId {} fullName is empty", appId);
            return Collections.emptyList();
        }
        List<InstanceInfo> masterRedisServerInfoList = appService.getAppMasterInstanceInfoList(appId);
        List<RedisServerNode> masterRedisServerNodes = new ArrayList<RedisServerNode>();
        for (InstanceInfo instanceInfo : masterRedisServerInfoList) {
            RedisServerNode masterRedisServerNode = new RedisServerNode();
            masterRedisServerNode.setIp(instanceInfo.getIp());
            masterRedisServerNode.setPort(instanceInfo.getPort());
            String masterName = generateMasterName(appDesc.getName(), instanceInfo.getPort());
            masterRedisServerNode.setMasterName(masterName);
            masterRedisServerNodes.add(masterRedisServerNode);
        }
        return masterRedisServerNodes;
    }

    /**
     * 清理falcon
     *
     * @param instanceTypeEnum
     * @return
     */
    protected TaskFlowStatusEnum clearFalconConfig(String host, InstanceTypeEnum instanceTypeEnum) {
        String config = "";
        if (instanceTypeEnum.equals(InstanceTypeEnum.REDIS_SERVER)) {
            config = "/tmp/redisPort.txt";
        } else if (instanceTypeEnum.equals(InstanceTypeEnum.NUTCRACKER)) {
            config = "/tmp/nutcrackerPort.txt";
        } else if (instanceTypeEnum.equals(InstanceTypeEnum.REDIS_SENTINEL)) {
            config = "/tmp/redis_sentinel_port.txt";
        } else if (instanceTypeEnum.equals(InstanceTypeEnum.PIKA)) {
            config = "/tmp/pika_port.txt";
        } else if (instanceTypeEnum.equals(InstanceTypeEnum.MEMCACHE)) {
            config = "/tmp/memcache_port.txt";
        }
        if (StringUtils.isNotBlank(config)) {
            try {
                String command = "echo '' > " + config;
                logger.info(marker, "{} execute command {}", host, command);
                machineCenter.executeShell(host, command);
            } catch (Exception e) {
                logger.error(marker, e.getMessage(), e);
            }
        } else {
            logger.info(marker, "instanceTypeEnum {} falcon config is empty", instanceTypeEnum.getInfo());
        }
        return TaskFlowStatusEnum.SUCCESS;
    }

    /**
     * 机器统计更新判断
     *
     * @param machineStats
     * @return
     */
    protected boolean checkMachineStatIsUpdate(MachineStats machineStats) {

        //统计信息时间，必须10分钟之类
        if (machineStats.getModifyTime().getTime() < DateUtils.addMinutes(new Date(), -10).getTime()) {
            throw new BizException("redis server machine stats {} update_time is {}, may be not updated recently in 10 minutes", machineStats.getIp(), machineStats.getUpdateTimeFormat());
        }

        return true;
    }

    /**
     * 计算quorum
     *
     * @return
     */
    protected int getQuorum(int sentinelSize) {
        int quorum;
        if (sentinelSize % 2 == 0) {
            quorum = sentinelSize / 2;
        } else {
            quorum = sentinelSize / 2 + 1;
        }
        return Math.max(quorum, 1);
    }

    /**
     * 执行一个ls命令，确认ssh以及不是只读盘
     *
     * @return
     */
    protected boolean checkMachineIsConnect(String ip) {
        try {
            SSHTemplate.Result result = sshService.executeWithResult(ip, "ls / | wc -l");
            if (result.isSuccess()) {
                return true;
            }
        } catch (Exception e) {
            logger.error(marker, e.getMessage());
            e.printStackTrace();
        }
        throw new BizException("checkMachineIsConnect failed, ip={}", ip);
    }

    /**
     * 获取复制master需要等待的秒数
     *
     * @param masterHost
     * @param masterPort
     * @return
     */
    protected int getSlaveOfSleepSeconds(String masterHost, int masterPort) {
        InstanceStats instanceStats = instanceStatsDao.getInstanceStatsByHost(masterHost, masterPort);
        long usedMemoryMB = instanceStats.getUsedMemory() / 1024 / 1024;
        //40MB一秒，一分钟2GB
        int seconds = (int) (usedMemoryMB / 40 + 5);
        logger.info(BaseTask.marker, "{}:{} usedMemory is {}MB need sleep {} seconds", masterHost, masterPort,
                usedMemoryMB, seconds);
        return seconds;
    }

    protected List<NutCrackerNode> transformNutCrackerFromInstance(List<InstanceInfo> instanceInfoList) {
        List<NutCrackerNode> nutCrackerNodes = new ArrayList<NutCrackerNode>();
        for (InstanceInfo instanceInfo : instanceInfoList) {
            NutCrackerNode nutCrackerNode = new NutCrackerNode(instanceInfo.getIp(), instanceInfo.getPort());
            nutCrackerNodes.add(nutCrackerNode);
        }
        return nutCrackerNodes;
    }

    protected List<RedisSentinelNode> transformRedisSentinelFromInstance(List<InstanceInfo> instanceInfoList) {
        List<RedisSentinelNode> redisSentinelNodes = new ArrayList<RedisSentinelNode>();
        for (InstanceInfo instanceInfo : instanceInfoList) {
            RedisSentinelNode redisSentinelNode = new RedisSentinelNode(instanceInfo.getIp(), instanceInfo.getPort());
            redisSentinelNodes.add(redisSentinelNode);
        }
        return redisSentinelNodes;
    }

    /**
     * 生成masterName
     *
     * @param appFullName
     * @param port
     * @return
     */
    public static String generateMasterName(String appFullName, int port) {
        return appFullName + "-" + port;
    }


    /**
     * @param redisServerNodes
     * @param appId
     * @return
     */
    protected List<RedisServerNode> buildRedisServerNodes(List<RedisServerNode> redisServerNodes, long appId) {
        List<RedisServerNode> list = Lists.newArrayList();
        if (CollectionUtils.isEmpty(redisServerNodes)) {
            List<InstanceInfo> instanceInfoList = appService.getAppMasterInstanceInfoList(appId);
            redisServerNodes = transformRedisServerFromInstance(instanceInfoList);
            list.addAll(redisServerNodes);
        } else {
            for (RedisServerNode redisServerNode : redisServerNodes) {
                list.add(new RedisServerNode(redisServerNode.getIp(), redisServerNode.getPort()));
            }
        }
        return list;
    }

    private List<RedisServerNode> transformRedisServerFromInstance(List<InstanceInfo> instanceInfoList) {
        List<RedisServerNode> redisServerNodes = new ArrayList<>();
        for (int i = 0; i < instanceInfoList.size(); i++) {
            InstanceInfo instanceInfo = instanceInfoList.get(i);
            RedisServerNode redisServerNode = new RedisServerNode();
            redisServerNode.setIp(instanceInfo.getIp());
            redisServerNode.setPort(instanceInfo.getPort());
            redisServerNodes.add(redisServerNode);
        }
        return redisServerNodes;
    }

    /**
     * 键值分析：批量写入 assist Redis zset 并校验确实落盘（失败则抛错，供旧版子任务使用）
     */
    protected void flushAssistZsetCounts(String redisKey, Map<String, Long> counts, String statsLabel) {
        flushAssistZsetCounts(redisKey, counts, statsLabel, -1, -1);
    }

    /**
     * 键值分析：写入 assist Redis zset；若指定 appId/auditId，写入失败时记录风险而不中断任务
     */
    protected void flushAssistZsetCounts(String redisKey, Map<String, Long> counts, String statsLabel,
            long appId, long auditId) {
        if (counts == null || counts.isEmpty()) {
            return;
        }
        int writeErrors = 0;
        for (Map.Entry<String, Long> entry : counts.entrySet()) {
            try {
                assistRedisService.zincrby(redisKey, entry.getValue(), entry.getKey());
            } catch (Exception e) {
                writeErrors++;
                logger.warn(marker, "zincrby failed key={} member={} err={}", redisKey, entry.getKey(),
                        e.getMessage());
            }
        }
        if (assistRedisService.hasZsetData(redisKey)) {
            return;
        }
        String message = statsLabel + " 统计未能写入辅助 Redis（endpoint="
                + assistRedisService.getAssistRedisEndpoint() + ", key=" + redisKey
                + "），可能为 WRONGTYPE、密码错误或连接异常";
        if (writeErrors > 0) {
            message += "（写入失败 " + writeErrors + " 次）";
        }
        if (appId > 0 && auditId > 0) {
            assistRedisService.appendKeyAnalysisRisk(appId, auditId, message);
            logger.warn(marker, "appId={} auditId={} {}", appId, auditId, message);
            return;
        }
        throw new BizException(message);
    }

    protected void requireLocalStatsWhenDbNonEmpty(long dbSize, boolean hasLocalStats, String host, int port,
            String statsLabel) {
        requireLocalStatsWhenDbNonEmpty(dbSize, hasLocalStats, host, port, statsLabel, -1, -1);
    }

    protected void requireLocalStatsWhenDbNonEmpty(long dbSize, boolean hasLocalStats, String host, int port,
            String statsLabel, long appId, long auditId) {
        if (dbSize <= 0 || hasLocalStats) {
            return;
        }
        String message = statsLabel + " " + host + ":" + port + " dbsize=" + dbSize + " 但扫描统计为空";
        if (appId > 0 && auditId > 0) {
            assistRedisService.appendKeyAnalysisRisk(appId, auditId, message);
            logger.warn(marker, "appId={} auditId={} {}", appId, auditId, message);
            return;
        }
        throw new BizException(message);
    }

    /**
     * 部署任务完成后将应用置为运行中（管理员直部署无需再手工审批通过）
     */
    protected void finalizeAppDeployToOnline(long appId, long auditId) {
        AppDesc appDesc = appDao.getAppDescById(appId);
        if (appDesc != null) {
            appDesc.setStatus(AppStatusEnum.STATUS_PUBLISHED.getStatus());
            appDesc.setPassedTime(new Date());
            appDao.update(appDesc);
        }
        if (auditId > 0) {
            appAuditDao.updateAppAudit(auditId, AppCheckEnum.APP_PASS.value());
        }
    }

}
