package com.shcj.cache.task.tasks.daily;

import com.google.common.collect.Maps;
import com.shcj.cache.constant.InstanceStatusEnum;
import com.shcj.cache.entity.AppClientStatisticGather;
import com.shcj.cache.entity.AppDesc;
import com.shcj.cache.entity.InstanceInfo;
import com.shcj.cache.entity.InstanceSlotModel;
import com.shcj.cache.entity.InstanceStats;
import com.shcj.cache.entity.MachineInfo;
import com.shcj.cache.stats.app.impl.AppDailyDataCenterImpl;
import com.shcj.cache.task.BaseTask;
import com.shcj.cache.task.constant.InstanceRoleEnum;
import com.shcj.cache.task.constant.TaskConstants;
import com.shcj.cache.task.constant.TaskStepFlowEnum.TaskFlowStatusEnum;
import com.shcj.cache.task.constant.TopoloyExamContants;
import com.shcj.cache.util.ConstUtils;
import com.shcj.cache.web.enums.BooleanEnum;
import com.shcj.cache.web.enums.ExamToolEnum;
import com.shcj.cache.web.util.DateUtil;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.time.DateUtils;
import com.google.common.collect.Lists;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.text.MessageFormat;
import java.util.*;

import static org.springframework.beans.factory.config.ConfigurableBeanFactory.SCOPE_PROTOTYPE;

/**
 * Created by rucao on 2019/1/17
 */
@Component("TopologyExamTask")
@Scope(SCOPE_PROTOTYPE)
public class TopologyExamTask extends BaseTask {
    private Logger logger = LoggerFactory.getLogger(TopologyExamTask.class);

    @Resource
    private AppDailyDataCenterImpl appDailyDataCenter;

    private boolean auto;
    private int examType;
    private List<AppDesc> appDescList = Lists.newArrayList();
    private List<Map> examResult = Lists.newArrayList();
    private Map<String, Object> examInfo = Maps.newHashMap();

    private int appType;
    private long appId;
    /**
     * instance list
     */
    private List<InstanceInfo> instances;
    /**
     * entry <masterInst,slaveInstList>
     */
    private Map<InstanceInfo, List<InstanceInfo>> master_slaves;
    /**
     * machine realip / 机房信息
     */
    private Map<String, List<InstanceInfo>> machineInstancesMap;
    private Map<String, MachineInfo> instanceInfoMap;
    private Map<String, MachineInfo> machineInfoMap;

    private int slaveNum;
    /**
     * sentinelInst
     */
    private List<InstanceInfo> sentinels;

    private static final String DOT = ".";

    @Override
    public List<String> getTaskSteps() {
        List<String> taskStepList = Lists.newArrayList();
        taskStepList.add(TaskConstants.INIT_METHOD_KEY);
        // 1. 检查应用参数
        taskStepList.add("prepareAppParam");
        // 2. 执行应用拓扑故障检查
        taskStepList.add("executeAppTopologyExam");
        // 3. 检查结果展示/发送结果邮件
        taskStepList.add("showAppTopologyExamResult");

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
        auto = MapUtils.getBooleanValue(paramMap, "auto");
        examType = MapUtils.getIntValue(paramMap, TaskConstants.EXAM_TYPE_KEY);
        appDescList.clear();
        examResult.clear();
        return TaskFlowStatusEnum.SUCCESS;
    }

    /**
     * 1.准备应用参数
     *
     * @return
     */
    public TaskFlowStatusEnum prepareAppParam() {
        logger.info("prepareAppParam");
        if (examType == ExamToolEnum.EXAM_NON_TEST.getValue()) {
            appDescList = appDao.getOnlineAppsNonTest();
        } else if (examType == ExamToolEnum.EXAM_ALL.getValue()) {
            appDescList = appDao.getOnlineApps();
        } else if (examType == ExamToolEnum.EXAM_APPID.getValue()) {
            long appId = MapUtils.getIntValue(paramMap, TaskConstants.APPID_KEY);
            appDescList.add(appDao.getAppDescById(appId));
        }
        if (CollectionUtils.isEmpty(appDescList)) {
            logger.error(marker, "task {} appDesc is empty", taskId);
            return TaskFlowStatusEnum.ABORT;
        }
        return TaskFlowStatusEnum.SUCCESS;
    }

    /**
     * 2. 执行应用拓扑故障检查
     *
     * @return
     */
    public TaskFlowStatusEnum executeAppTopologyExam() {
        logger.info("executeAppTopologyExam");
        check(appDescList);
        return TaskFlowStatusEnum.SUCCESS;
    }


    public Map<String, Object> check(List<AppDesc> appDescList) {
        examResult.clear();
        examInfo.clear();

        if (!CollectionUtils.isEmpty(appDescList)) {
            for (AppDesc appDesc : appDescList) {
                try {
                    getAppParam(appDesc);
                    switch (appType) {
                        case ConstUtils.CACHE_REDIS_STANDALONE:
                            standaloneExam();
                            break;
                        case ConstUtils.CACHE_TYPE_REDIS_CLUSTER:
                            clusterExam();
                            break;
                        case ConstUtils.CACHE_REDIS_SENTINEL:
                            sentinelExam();
                            break;
                        default:
                            break;
                    }
                } catch (Exception e) {
                    logger.error("executeAppTopologyExam error. appId:{}", appDesc.getAppId());
                    logger.error(e.getMessage(), e);
                }
            }
            examInfo.put("appDesc", appDescList.get(0));
            examInfo.put("tips", examResult);
        }
        return examInfo;
    }

    public List<AppClientStatisticGather> checkAppsTopology(Date date) {
        List<AppClientStatisticGather> result = new ArrayList<>();
        if (date == null) {
            //获取前一天日期
            date = new Date();
            Calendar calendar = Calendar.getInstance();
            calendar.setTime(date);
            calendar.add(Calendar.DAY_OF_MONTH, -1);
            date = calendar.getTime();
        }
        Date checkDate = date;

        List<AppDesc> appDescList = appDao.getOnlineApps();
        appDescList.forEach(appDesc -> {
            AppClientStatisticGather gather = new AppClientStatisticGather();
            Map<String, Object> info = check(new ArrayList<AppDesc>() {{
                add(appDesc);
            }});
            gather.setGatherTime(DateUtil.formatYYYY_MM_dd(checkDate));

            gather.setAppId(appDesc.getAppId());
            if (MapUtils.isNotEmpty(info)) {
                List tips = (List) info.get("tips");
                int topologyExamResult = CollectionUtils.isNotEmpty(tips) && tips.size() > 0 ? 1 : 0;
                gather.setTopologyExamResult(topologyExamResult);
            } else {
                gather.setTopologyExamResult(-1);
            }
            result.add(gather);
        });
        return result;
    }

    /**
     * 3. 检查结果显示
     *
     * @return
     */
    public TaskFlowStatusEnum showAppTopologyExamResult() {
        logger.info("showAppTopologyExamResult");
        Date startDate = DateUtils.addDays(new Date(), -1);
        appDailyDataCenter.noteAppTopologyDaily(startDate, examResult);
        return TaskFlowStatusEnum.SUCCESS;
    }


    /**
     * getAppParam 获取应用检查参数
     */
    private boolean getAppParam(AppDesc appDesc) {
        if (appDesc == null) {
            logger.error(marker, "appId {} appDesc is null", appId);
            return false;
        }

        instances = Lists.newArrayList();
        master_slaves = Maps.newHashMap();
        sentinels = Lists.newArrayList();
        machineInfoMap = Maps.newHashMap();
        instanceInfoMap = Maps.newHashMap();
        machineInstancesMap = Maps.newHashMap();
        slaveNum = 0;

        appId = appDesc.getAppId();

        if (!appDesc.isOnline()) {
            logger.error(marker, "appId {} is must be online, ", appId);
            return false;
        }
        appType = appDesc.getType();
        int topologySynced = appService.syncAppTopology(appId);
        if (topologySynced > 0) {
            logger.warn(marker, "topology exam auto-synced parent_id appId={} instancesUpdated={}", appId,
                    topologySynced);
        }
        instances = appService.getAppOnlineInstanceInfo(appId);
        if (instances.isEmpty()) {
            logger.error(marker, "appId {} : instance list is null ", appId);
            return false;
        }
        int remainingDrift = appService.countAppTopologyDrift(appId);
        if (remainingDrift > 0) {
            examResult.add(new HashMap<String, String>() {{
                put(TopoloyExamContants.APPID, String.valueOf(appId));
                put(TopoloyExamContants.TYPE, resolveTopologyExamTypeLabel(appType));
                put(TopoloyExamContants.STATUS, TopoloyExamContants.PARENT_ID_DRIFT_DESC);
                put(TopoloyExamContants.DESC,
                        MessageFormat.format(TopoloyExamContants.PARENT_ID_DRIFT_FORMAT, remainingDrift));
            }});
        }
        for (InstanceInfo inst : instances) {
            if (!isGoodInstance(inst)) {
                continue;
            }
            String roleDesc = resolveTopologyRole(inst);
            if (InstanceRoleEnum.MASTER.getInfo().equals(roleDesc)) {
                master_slaves.put(inst, Lists.newArrayList());
            } else if (InstanceRoleEnum.SENTINEL.getInfo().equals(roleDesc)) {
                sentinels.add(inst);
            }
        }
        for (InstanceInfo inst : instances) {
            if (isGoodInstance(inst) && InstanceRoleEnum.SLAVE.getInfo().equals(resolveTopologyRole(inst))) {
                slaveNum++;
                for (Map.Entry<InstanceInfo, List<InstanceInfo>> entry : master_slaves.entrySet()) {
                    InstanceInfo masterInst = entry.getKey();
                    if (isSlaveOfMaster(inst, masterInst)) {
                        List<InstanceInfo> slaveInstList = entry.getValue();
                        slaveInstList.add(inst);
                        master_slaves.put(masterInst, slaveInstList);
                    }
                }
            }
        }
        // machineInfo
        for (InstanceInfo inst : instances) {
            if (isGoodInstance(inst)) {
                MachineInfo machineInfo = resolveMachineInfo(inst.getIp());
                String realIp = resolveRealIp(machineInfo, inst.getIp());
                List<InstanceInfo> instanceInfos = new ArrayList<>();
                if (!CollectionUtils.isEmpty(machineInstancesMap.get(realIp))) {
                    instanceInfos = machineInstancesMap.get(realIp);
                }
                instanceInfos.add(inst);
                machineInstancesMap.put(realIp, instanceInfos);
                machineInfoMap.put(realIp, machineInfo);
                instanceInfoMap.put(inst.getIp(), machineInfo);
            }
        }
        // master-slave validate
        boolean msFlag = false;
        for (Map.Entry<InstanceInfo, List<InstanceInfo>> ms : master_slaves.entrySet()) {
            InstanceInfo masterNode = ms.getKey();
            if (!CollectionUtils.isEmpty(ms.getValue())) {
                for (InstanceInfo slaveNode : ms.getValue()) {
                    String masterRealIp = getRealIp(masterNode.getIp());
                    String slaveRealIp = getRealIp(slaveNode.getIp());
                    if (!StringUtils.isEmpty(masterRealIp) && !StringUtils.isEmpty(slaveRealIp) && masterRealIp.equals(slaveRealIp)) {
                        msFlag = true;
                        break;
                    }
                }
            }
            if (msFlag) {
                break;
            }
        }

        examInfo.put("instances", instances);
        examInfo.put("master_slaves", master_slaves);
        examInfo.put("sentinels", sentinels);
        examInfo.put("sameNetSegment", checkSameNetSegment());
        examInfo.put("slaveNum", slaveNum);
        examInfo.put("machineInfoMap", machineInfoMap);
        examInfo.put("instanceInfoMap", instanceInfoMap);
        examInfo.put("machineInstancesMap", machineInstancesMap);
        examInfo.put("msFlag", msFlag);

        return true;
    }

    private boolean isGoodInstance(InstanceInfo inst) {
        return inst != null && inst.getStatus() == InstanceStatusEnum.GOOD_STATUS.getStatus();
    }

    private boolean isSlaveOfMaster(InstanceInfo slave, InstanceInfo master) {
        if (slave == null || master == null || master.getId() == null) {
            return false;
        }
        if (slave.getMasterInstanceId() == master.getId()) {
            return true;
        }
        return slave.getMasterInstanceId() <= 0 && master_slaves.size() == 1;
    }

    private String resolveTopologyRole(InstanceInfo inst) {
        if (inst == null) {
            return "";
        }
        String roleDesc = StringUtils.trimToEmpty(inst.getRoleDesc());
        if (InstanceRoleEnum.SENTINEL.getInfo().equals(roleDesc)
                || InstanceRoleEnum.MASTER.getInfo().equals(roleDesc)
                || InstanceRoleEnum.SLAVE.getInfo().equals(roleDesc)) {
            return roleDesc;
        }
        if (inst.getType() == ConstUtils.CACHE_REDIS_SENTINEL) {
            return InstanceRoleEnum.SENTINEL.getInfo();
        }
        if (!inst.isRedisData()) {
            return roleDesc;
        }
        InstanceStats stats = instanceStatsDao.getInstanceStatsByHost(inst.getIp(), inst.getPort());
        if (stats != null) {
            if (stats.getRole() == 1) {
                inst.setRoleDesc(BooleanEnum.TRUE);
                return InstanceRoleEnum.MASTER.getInfo();
            }
            if (stats.getRole() == 2) {
                inst.setRoleDesc(BooleanEnum.FALSE);
                return InstanceRoleEnum.SLAVE.getInfo();
            }
        }
        if (inst.getMasterInstanceId() > 0) {
            inst.setRoleDesc(BooleanEnum.FALSE);
            return InstanceRoleEnum.SLAVE.getInfo();
        }
        return roleDesc;
    }

    private MachineInfo resolveMachineInfo(String ip) {
        MachineInfo machineInfo = machineCenter.getMachineInfoByIp(ip);
        if (machineInfo != null) {
            return machineInfo;
        }
        MachineInfo externalMachine = new MachineInfo();
        externalMachine.setIp(ip);
        externalMachine.setRealIp(ip);
        externalMachine.setRoom("外部纳管");
        externalMachine.setRack("-");
        return externalMachine;
    }

    private String resolveRealIp(MachineInfo machineInfo, String fallbackIp) {
        if (machineInfo == null) {
            return fallbackIp;
        }
        if (StringUtils.isNotBlank(machineInfo.getRealIp())) {
            return machineInfo.getRealIp();
        }
        if (StringUtils.isNotBlank(machineInfo.getIp())) {
            return machineInfo.getIp();
        }
        return fallbackIp;
    }

    private boolean checkSameNetSegment(){
        boolean sameFlag = true;
        if (appType == ConstUtils.CACHE_TYPE_REDIS_CLUSTER  && CollectionUtils.isNotEmpty(instances)) {
            Optional<InstanceInfo> masterOptional = instances.stream().filter(instanceInfo -> instanceInfo.getRoleDesc().equals(InstanceRoleEnum.MASTER.getInfo())).findFirst();
            if(masterOptional.isPresent()){
                String ip = masterOptional.get().getIp();
                String[] split = ip.split("\\.");
                if(split != null && split.length > 2){
                    String netSegment = split[0] + DOT  + split[1];
                    Optional<InstanceInfo> notMatchInstance = instances.stream().filter(instanceInfo -> InstanceRoleEnum.MASTER.getInfo().equals(instanceInfo.getRoleDesc()) || InstanceRoleEnum.SLAVE.getInfo().equals(instanceInfo.getRoleDesc())).filter(instanceInfo -> !instanceInfo.getIp().startsWith(netSegment)).findFirst();
                    if(notMatchInstance.isPresent()){
                        String notMatchIp = notMatchInstance.get().getIp();
                        String notMatchNetSegment = notMatchIp;
                        if(notMatchIp != null){
                            String[] split1 = notMatchIp.split("\\.");
                            notMatchNetSegment = split1[0] + DOT + split1[1];
                        }
                        String diffNetSegment = notMatchNetSegment;
                        sameFlag = false;
                        examResult.add(
                                new HashMap<String, String>() {{
                                    put(TopoloyExamContants.APPID, String.valueOf(appId));
                                    put(TopoloyExamContants.TYPE, appType == ConstUtils.CACHE_TYPE_REDIS_CLUSTER ? TopoloyExamContants.REDIS_CLUSTER : TopoloyExamContants.REDIS_SENTINEL);
                                    put(TopoloyExamContants.STATUS, TopoloyExamContants.NETSEGMENT_DESC);
                                    put(TopoloyExamContants.DESC, MessageFormat.format(TopoloyExamContants.NETSEGMENT_FORMAT, netSegment, diffNetSegment));
                                }}
                        );
                    }
                }
            }
        }
        return sameFlag;
    }

    private String resolveTopologyExamTypeLabel(int type) {
        if (type == ConstUtils.CACHE_TYPE_REDIS_CLUSTER) {
            return TopoloyExamContants.REDIS_CLUSTER;
        }
        if (type == ConstUtils.CACHE_REDIS_SENTINEL) {
            return TopoloyExamContants.REDIS_SENTINEL;
        }
        return TopoloyExamContants.REDIS_STANDALONE;
    }

    /**
     * redis-standalone类型应用故障检查
     */
    private void standaloneExam() {
        for (Map.Entry<InstanceInfo, List<InstanceInfo>> entry : master_slaves.entrySet()) {
            masterSlaveExam(TopoloyExamContants.REDIS_STANDALONE, entry.getKey(), entry.getValue());
            break;
        }
    }

    /**
     * redis-cluster类型应用故障检查
     */
    private void clusterExam() {
        Map<String, List<InstanceInfo>> masterMealIpMap = Maps.newHashMap();
        for (Map.Entry<InstanceInfo, List<InstanceInfo>> entry : master_slaves.entrySet()) {
            InstanceInfo masterInst = entry.getKey();
            //1.master-slave检查
            masterSlaveExam(TopoloyExamContants.REDIS_CLUSTER, masterInst, entry.getValue());

            //for failover exam
            String realIp = getRealIp(masterInst.getIp());
            List<InstanceInfo> masterInstList = (List) MapUtils.getObject(masterMealIpMap, realIp, Lists.newArrayList());
            masterInstList.add(masterInst);
            masterMealIpMap.put(realIp, masterInstList);
        }
        //2.master节点数检查
        if (masterMealIpMap.size() < 3) {
            StringBuilder tmpBuilder = new StringBuilder();
            for (Map.Entry<InstanceInfo, List<InstanceInfo>> entry : master_slaves.entrySet()) {
                InstanceInfo masterInst = entry.getKey();
                tmpBuilder.append(MessageFormat.format(TopoloyExamContants.INSTANCE_FORMAT, masterInst.getRoleDesc(), masterInst.getIp(), String.valueOf(masterInst.getPort()), getRealIp(masterInst.getIp())));
            }
            final String descStr = tmpBuilder.toString();
            examResult.add(
                    new HashMap<String, String>() {{
                        put(TopoloyExamContants.APPID, String.valueOf(appId));
                        put(TopoloyExamContants.TYPE, TopoloyExamContants.REDIS_CLUSTER);
                        put(TopoloyExamContants.STATUS, TopoloyExamContants.NODESNUM_DESC);
                        put(TopoloyExamContants.DESC, descStr);
                    }}
            );
        }
        //3.failover检查
        failoverExam(masterMealIpMap);
        //4.槽位检查
        clusterSlotExam();
    }

    private void clusterSlotExam() {
        final int totalSlots = 16384;
        Map<String, InstanceSlotModel> clusterSlotsMap = redisCenter.getClusterSlotsMap(appId);
        Map<String, String> lossSlotsSegmentMap = redisCenter.getClusterLossSlots(appId);

        int coveredSlots = 0;
        int minCount = Integer.MAX_VALUE;
        int maxCount = 0;
        List<Map<String, Object>> distributionRows = Lists.newArrayList();

        if (MapUtils.isNotEmpty(clusterSlotsMap)) {
            for (Map.Entry<String, InstanceSlotModel> entry : clusterSlotsMap.entrySet()) {
                InstanceSlotModel model = entry.getValue();
                int slotCount = (model.getSlotList() != null) ? model.getSlotList().size() : 0;
                coveredSlots += slotCount;
                minCount = Math.min(minCount, slotCount);
                maxCount = Math.max(maxCount, slotCount);

                Map<String, Object> row = Maps.newHashMap();
                row.put("hostPort", entry.getKey());
                if (model.getSlotDistributeList() != null && !model.getSlotDistributeList().isEmpty()) {
                    row.put("slotRanges", StringUtils.join(model.getSlotDistributeList(), ", "));
                } else {
                    row.put("slotRanges", "-");
                }
                row.put("slotCount", slotCount);
                row.put("percent", String.format("%.1f", slotCount * 100.0 / totalSlots));
                distributionRows.add(row);
            }
            distributionRows.sort((a, b) -> Integer.compare(
                    (Integer) b.get("slotCount"), (Integer) a.get("slotCount")));
        } else {
            minCount = 0;
            maxCount = 0;
        }

        int lostSlotsCount = Math.max(0, totalSlots - coveredSlots);
        boolean fetchOk = MapUtils.isNotEmpty(clusterSlotsMap);
        boolean slotComplete = fetchOk && lostSlotsCount == 0 && coveredSlots == totalSlots;
        boolean zeroSlotMaster = fetchOk && minCount == 0;
        boolean imbalance = fetchOk && clusterSlotsMap.size() > 1 && minCount > 0
                && ((double) maxCount / minCount) > 1.5;
        String imbalanceRatio = (fetchOk && minCount > 0)
                ? String.format("%.2f", (double) maxCount / minCount) : "-";

        if (!fetchOk) {
            examResult.add(new HashMap<String, String>() {{
                put(TopoloyExamContants.APPID, String.valueOf(appId));
                put(TopoloyExamContants.TYPE, TopoloyExamContants.REDIS_CLUSTER);
                put(TopoloyExamContants.STATUS, TopoloyExamContants.SLOT_FETCH_FAIL_DESC);
                put(TopoloyExamContants.DESC, "CLUSTER SLOTS 获取失败，请检查集群连通性与节点状态");
            }});
        } else {
            if (MapUtils.isNotEmpty(lossSlotsSegmentMap)) {
                for (Map.Entry<String, String> lossEntry : lossSlotsSegmentMap.entrySet()) {
                    final String hostPort = lossEntry.getKey();
                    final String segments = lossEntry.getValue();
                    examResult.add(new HashMap<String, String>() {{
                        put(TopoloyExamContants.APPID, String.valueOf(appId));
                        put(TopoloyExamContants.TYPE, TopoloyExamContants.REDIS_CLUSTER);
                        put(TopoloyExamContants.STATUS, TopoloyExamContants.SLOT_LOSS_DESC);
                        put(TopoloyExamContants.DESC, MessageFormat.format(
                                TopoloyExamContants.SLOT_LOSS_FORMAT, hostPort, segments));
                    }});
                }
            } else if (!slotComplete) {
                final String incompleteDesc = MessageFormat.format(
                        TopoloyExamContants.SLOT_INCOMPLETE_FORMAT,
                        String.valueOf(coveredSlots), String.valueOf(lostSlotsCount));
                examResult.add(new HashMap<String, String>() {{
                    put(TopoloyExamContants.APPID, String.valueOf(appId));
                    put(TopoloyExamContants.TYPE, TopoloyExamContants.REDIS_CLUSTER);
                    put(TopoloyExamContants.STATUS, TopoloyExamContants.SLOT_INCOMPLETE_DESC);
                    put(TopoloyExamContants.DESC, incompleteDesc);
                }});
            }
            if (zeroSlotMaster || imbalance) {
                final int finalMin = minCount == Integer.MAX_VALUE ? 0 : minCount;
                final String imbalanceDesc = MessageFormat.format(
                        TopoloyExamContants.SLOT_IMBALANCE_FORMAT,
                        String.valueOf(finalMin), String.valueOf(maxCount), imbalanceRatio);
                examResult.add(new HashMap<String, String>() {{
                    put(TopoloyExamContants.APPID, String.valueOf(appId));
                    put(TopoloyExamContants.TYPE, TopoloyExamContants.REDIS_CLUSTER);
                    put(TopoloyExamContants.STATUS, TopoloyExamContants.SLOT_IMBALANCE_DESC);
                    put(TopoloyExamContants.DESC, imbalanceDesc);
                }});
            }
        }

        Map<String, Object> slotExam = Maps.newHashMap();
        slotExam.put("totalSlots", totalSlots);
        slotExam.put("coveredSlots", coveredSlots);
        slotExam.put("lostSlotsCount", lostSlotsCount);
        slotExam.put("fetchOk", fetchOk);
        slotExam.put("slotComplete", slotComplete);
        slotExam.put("imbalance", imbalance);
        slotExam.put("zeroSlotMaster", zeroSlotMaster);
        slotExam.put("imbalanceRatio", imbalanceRatio);
        slotExam.put("minSlotCount", minCount == Integer.MAX_VALUE ? 0 : minCount);
        slotExam.put("maxSlotCount", maxCount);
        slotExam.put("masterCount", clusterSlotsMap != null ? clusterSlotsMap.size() : 0);
        slotExam.put("distributionRows", distributionRows);
        slotExam.put("lossSlotsSegmentMap", lossSlotsSegmentMap != null ? lossSlotsSegmentMap : Collections.emptyMap());
        List<Map<String, String>> lossRows = Lists.newArrayList();
        if (MapUtils.isNotEmpty(lossSlotsSegmentMap)) {
            for (Map.Entry<String, String> lossEntry : lossSlotsSegmentMap.entrySet()) {
                Map<String, String> lossRow = Maps.newHashMap();
                lossRow.put("hostPort", lossEntry.getKey());
                lossRow.put("segments", lossEntry.getValue());
                lossRows.add(lossRow);
            }
        }
        slotExam.put("lossRows", lossRows);
        slotExam.put("ok", fetchOk && slotComplete && !imbalance && !zeroSlotMaster);
        examInfo.put("slotExam", slotExam);
    }

    /**
     * redis-sentinel类型应用故障检查
     */
    private void sentinelExam() {
        Set<String> sentinelRealIpSet = new HashSet<>();
        for (InstanceInfo sentinelInst : sentinels) {
            sentinelRealIpSet.add(getRealIp(sentinelInst.getIp()));
        }
        //sentinel result
        if (sentinelRealIpSet.size() < 3) {
            logger.info(marker, "appId：{}, sentinel节点分布少于3台物理机", appId);
            StringBuilder tmpBuilder = new StringBuilder();
            for (InstanceInfo sentinelInst : sentinels) {
                tmpBuilder.append(MessageFormat.format(TopoloyExamContants.INSTANCE_FORMAT, sentinelInst.getRoleDesc(), sentinelInst.getIp(), String.valueOf(sentinelInst.getPort()), getRealIp(sentinelInst.getIp())));
            }
            final String descStr = tmpBuilder.toString();
            examResult.add(
                    new HashMap<String, String>() {{
                        put(TopoloyExamContants.APPID, String.valueOf(appId));
                        put(TopoloyExamContants.TYPE, TopoloyExamContants.REDIS_SENTINEL);
                        put(TopoloyExamContants.STATUS, TopoloyExamContants.NODESNUM_DESC);
                        put(TopoloyExamContants.DESC, descStr);
                    }}
            );
        }
        //master-slave result
        for (Map.Entry<InstanceInfo, List<InstanceInfo>> entry : master_slaves.entrySet()) {
            masterSlaveExam(TopoloyExamContants.REDIS_SENTINEL, entry.getKey(), entry.getValue());
            break;
        }
    }

    private void masterSlaveExam(String appType, InstanceInfo masterInst, List<InstanceInfo> slaveInstList) {

        String masterIp = masterInst.getIp();
        String port = String.valueOf(masterInst.getPort());
        String masterRealIp = getRealIp(masterIp);

        StringBuilder tmpBuilder = new StringBuilder(MessageFormat.format(TopoloyExamContants.CLUSTER_INSTANCE_FORMAT, masterInst.getRoleDesc(), masterIp, port, masterRealIp));
        if (slaveNum != 0 && slaveInstList.size() == 0) {
            //double check
            BooleanEnum hasSlaves = redisCenter.hasSlaves(appId, masterInst.getIp(), masterInst.getPort());
            if (hasSlaves == BooleanEnum.FALSE) {
                final String descStr = tmpBuilder.toString();
                examResult.add(
                        new HashMap<String, String>() {{
                            put(TopoloyExamContants.APPID, String.valueOf(appId));
                            put(TopoloyExamContants.TYPE, appType);
                            put(TopoloyExamContants.STATUS, TopoloyExamContants.SLAVE_NOT_EXIST);
                            put(TopoloyExamContants.DESC, descStr);
                        }}
                );
                logger.info(marker, "appId：{}, master没有对应的slave", appId);
            }
        }


        boolean flag = false;
        for (InstanceInfo slaveInst : slaveInstList) {
            String slaveIp = slaveInst.getIp();
            String slaveRealIp = getRealIp(slaveIp);
            if (masterRealIp.equals(slaveRealIp)) {
                flag = true;
                tmpBuilder.append(MessageFormat.format(TopoloyExamContants.INSTANCE_FORMAT, InstanceRoleEnum.SLAVE.getInfo(), slaveIp, String.valueOf(slaveInst.getPort()), slaveRealIp));
            }
        }
        if (flag) {
            final String descStr = tmpBuilder.toString();
            examResult.add(
                    new HashMap<String, String>() {{
                        put(TopoloyExamContants.APPID, String.valueOf(appId));
                        put(TopoloyExamContants.TYPE, appType);
                        put(TopoloyExamContants.STATUS, TopoloyExamContants.MASTER_SLAVE_DESC);
                        put(TopoloyExamContants.DESC, descStr);
                    }}
            );
        }
    }

    private void failoverExam(Map<String, List<InstanceInfo>> masterMealIpMap) {
        for (Map.Entry<String, List<InstanceInfo>> entry : masterMealIpMap.entrySet()) {
            String masterRealIp = entry.getKey();
            StringBuilder tmpBuilder = new StringBuilder("");
            for (InstanceInfo masterInst : entry.getValue()) {
                tmpBuilder.append(MessageFormat.format(TopoloyExamContants.CLUSTER_INSTANCE_FORMAT, masterInst.getRoleDesc(), masterInst.getIp(), String.valueOf(masterInst.getPort()), masterRealIp));
            }
            final String descStr = tmpBuilder.toString();
            if (master_slaves.size() - entry.getValue().size() < master_slaves.size() / 2 + 1) {
                examResult.add(
                        new HashMap<String, String>() {{
                            put(TopoloyExamContants.APPID, String.valueOf(appId));
                            put(TopoloyExamContants.TYPE, TopoloyExamContants.REDIS_CLUSTER);
                            put(TopoloyExamContants.STATUS, TopoloyExamContants.CLUSTER_FAILOVER_DESC);
                            put(TopoloyExamContants.DESC, descStr);
                        }}
                );
                examInfo.put("failoverStatus", false);
            }
        }
    }

    private String getRealIp(String ip) {
        if (StringUtils.isBlank(ip)) {
            return "";
        }
        MachineInfo machineInfo = null;
        if (instanceInfoMap != null) {
            machineInfo = instanceInfoMap.get(ip);
        }
        if (machineInfo == null) {
            machineInfo = machineDao.getMachineInfoByIp(ip);
        }
        return resolveRealIp(machineInfo, ip);
    }


}
