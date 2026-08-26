package com.shcj.cache.web.controller;

import com.shcj.cache.async.AsyncService;
import com.shcj.cache.constant.AppUserTypeEnum;
import com.shcj.cache.util.TypeUtil;
import redis.clients.jedis.HostAndPort;
import com.shcj.cache.dao.AppAuditDao;
import com.shcj.cache.dao.AppAuditLogDao;
import com.shcj.cache.dao.AppImportDao;
import com.shcj.cache.dao.InstanceDao;
import com.shcj.cache.entity.*;
import com.shcj.cache.exception.BizException;
import com.shcj.cache.machine.MachineCenter;
import com.shcj.cache.redis.AssistRedisService;
import com.shcj.cache.redis.RedisCenter;
import com.shcj.cache.redis.RedisConfigTemplateService;
import com.shcj.cache.redis.RedisDeployCenter;
import com.shcj.cache.stats.app.AppDeployCenter;
import com.shcj.cache.stats.app.AppStatsCenter;
import com.shcj.cache.stats.instance.InstanceDeployCenter;
import com.shcj.cache.web.enums.BooleanEnum;
import com.shcj.cache.web.service.AppService;
import com.shcj.cache.web.service.ResourceService;
import com.shcj.cache.web.service.UserLoginStatusService;
import com.shcj.cache.web.service.UserService;
import com.shcj.cache.web.util.AppEmailUtil;
import com.shcj.cache.constant.TimeDimensionalityEnum;
import com.shcj.cache.web.util.DateUtil;
import com.shcj.cache.web.vo.AjaxResult;
import com.shcj.cache.weixin.AppWeiXinService;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.math.NumberUtils;
import org.apache.commons.lang.time.DateUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.ui.Model;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.nio.charset.Charset;
import java.text.ParseException;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 基类controller
 *
 * @author leifu
 * @Time 2014年10月16日
 */
public class BaseController {
    protected final Logger logger = LoggerFactory.getLogger(getClass());

    /** 集群统计页最大查询跨度（与客户端异常统计等页面一致） */
    protected static final long APP_STAT_MAX_RANGE_MS = TimeUnit.DAYS.toMillis(7);

    protected TimeDimensionalityEnum resolveStatTimeDimensionality(Date startDate, Date endDate) {
        if (startDate != null && endDate != null
                && endDate.getTime() - startDate.getTime() > TimeUnit.DAYS.toMillis(1)) {
            return TimeDimensionalityEnum.HOUR;
        }
        return TimeDimensionalityEnum.MINUTE;
    }

    protected boolean clampStatTimeRange(Date[] rangeHolder) {
        Date startDate = rangeHolder[0];
        Date endDate = rangeHolder[1];
        if (startDate == null || endDate == null) {
            return false;
        }
        if (endDate.getTime() - startDate.getTime() <= APP_STAT_MAX_RANGE_MS) {
            return false;
        }
        rangeHolder[0] = new Date(endDate.getTime() - APP_STAT_MAX_RANGE_MS);
        return true;
    }

    @Autowired
    protected UserService userService;

    @Autowired
    protected AppService appService;

    @Autowired
    protected MachineCenter machineCenter;

    @Autowired
    protected UserLoginStatusService userLoginStatusService;

    @Autowired
    protected RedisCenter redisCenter;

    @Resource
    protected AppDeployCenter appDeployCenter;

    @Resource
    protected AppAuditDao appAuditDao;

    @Resource
    protected AppImportDao appImportDao;

    @Resource
    protected AppAuditLogDao appAuditLogDao;

    @Resource
    protected InstanceDao instanceDao;

    @Resource
    protected RedisDeployCenter redisDeployCenter;

    @Resource
    protected AppEmailUtil appEmailUtil;

    @Resource
    protected AsyncService asyncService;

    @Autowired
    protected AppStatsCenter appStatsCenter;

    @Autowired
    protected InstanceDeployCenter instanceDeployCenter;

    @Autowired
    AssistRedisService assistRedisService;

    @Resource
    protected RedisConfigTemplateService redisConfigTemplateService;

    @Resource
    protected ResourceService resourceService;

    @Autowired
    protected AppWeiXinService appWeiXinService;

    public Boolean saveTempResource(String resourceId, String content) {
        try {
            assistRedisService.set(getResourceKey(resourceId), content);
        } catch (Exception e) {
            return false;
        }
        return true;
    }

    private String getResourceKey(String resourceId) {
        return String.format("resource_%s", resourceId);
    }

    public boolean clearTempResource(String resourceId) {
        try {
            assistRedisService.del(getResourceKey(resourceId));
        } catch (Exception e) {
            return false;
        }
        return true;
    }

    public String getTempResource(String resourceId) {
        try {
            return assistRedisService.get(getResourceKey(resourceId));
        } catch (Exception e) {
            return null;
        }
    }

    protected TimeBetween getJsonTimeBetween(HttpServletRequest request) throws ParseException {
        String startDateParam = request.getParameter("startDate");
        String endDateParam = request.getParameter("endDate");
        Date startDate = DateUtil.parseRangeDateTime(startDateParam);
        Date endDate;
        if (StringUtils.isBlank(endDateParam)) {
            endDate = DateUtils.addDays(startDate, 1);
        } else {
            endDate = DateUtil.parseRangeDateTime(endDateParam);
            if (endDateParam.trim().length() <= 10) {
                endDate = DateUtils.addDays(endDate, 1);
            }
        }
        Date[] range = new Date[]{startDate, endDate};
        clampStatTimeRange(range);
        startDate = range[0];
        endDate = range[1];
        long beginTime = NumberUtils.toLong(DateUtil.formatYYYYMMddHHMM(startDate));
        long endTime = NumberUtils.toLong(DateUtil.formatYYYYMMddHHMM(endDate));
        return new TimeBetween(beginTime, endTime, startDate, endDate);
    }

    protected TimeBetween getTimeBetween(HttpServletRequest request, Model model, String startDateAtr,
                                         String endDateAtr) throws ParseException {
        String startDateParam = request.getParameter(startDateAtr);
        String endDateParam = request.getParameter(endDateAtr);
        Date startDate;
        Date endDate;
        if (StringUtils.isBlank(startDateParam) || StringUtils.isBlank(endDateParam)) {
            endDate = new Date();
            startDate = DateUtils.addHours(endDate, -6);
            startDateParam = DateUtil.formatRangeDateTime(startDate);
            endDateParam = DateUtil.formatRangeDateTime(endDate);
        } else {
            startDate = DateUtil.parseRangeDateTime(startDateParam);
            endDate = DateUtil.parseRangeDateTime(endDateParam);
            if (endDateParam.trim().length() <= 10) {
                endDate = DateUtils.addDays(endDate, 1);
            }
            startDateParam = DateUtil.formatRangeDateTime(startDate);
            endDateParam = DateUtil.formatRangeDateTime(endDate);
        }
        Date yesterDay = DateUtils.addDays(startDate, -1);

        long beginTime = NumberUtils.toLong(DateUtil.formatYYYYMMddHHMM(startDate));
        long endTime = NumberUtils.toLong(DateUtil.formatYYYYMMddHHMM(endDate));
        model.addAttribute(startDateAtr, startDateParam);
        model.addAttribute(endDateAtr, endDateParam);
        model.addAttribute("yesterDay", DateUtil.formatDate(yesterDay, "yyyy-MM-dd"));
        return new TimeBetween(beginTime, endTime, startDate, endDate);
    }

    protected TimeBetween clampAppStatTimeBetween(TimeBetween timeBetween, Model model) {
        Date[] range = new Date[]{timeBetween.getStartDate(), timeBetween.getEndDate()};
        if (!clampStatTimeRange(range)) {
            return timeBetween;
        }
        Date startDate = range[0];
        Date endDate = range[1];
        model.addAttribute("startDate", DateUtil.formatRangeDateTime(startDate));
        model.addAttribute("endDate", DateUtil.formatRangeDateTime(endDate));
        model.addAttribute("statRangeClamped", true);
        long beginTime = NumberUtils.toLong(DateUtil.formatYYYYMMddHHMM(startDate));
        long endTime = NumberUtils.toLong(DateUtil.formatYYYYMMddHHMM(endDate));
        return new TimeBetween(beginTime, endTime, startDate, endDate);
    }

    /**
     * 返回用户基本信息
     *
     * @param request
     * @return
     */
    public AppUser getUserInfo(HttpServletRequest request) {
        String userName = userLoginStatusService.getUserNameFromLoginStatus(request);
        return userService.getByName(userName);
    }


    /**
     * 发送json消息
     *
     * @param response
     * @param message
     */
    public void sendMessage(HttpServletResponse response, String message) {
        if (response.isCommitted()) {
            logger.debug("response is already committed, skip writing json response");
            return;
        }
        try {
            response.resetBuffer();
            response.setContentType("application/json;charset=UTF-8");
            PrintWriter printWriter = response.getWriter();
            printWriter.write(message);
            printWriter.flush();
        } catch (IOException e) {
            logger.debug("client connection closed while writing json response: {}", e.getMessage());
        } catch (IllegalStateException e) {
            logger.debug("response state changed before json response was written: {}", e.getMessage());
        }
    }

    public void sendMessageOk(HttpServletResponse response) {
        sendMessage(response, AjaxResult.ok().toString());
    }

    public void sendMessageError(HttpServletResponse response, String msg) {
        sendMessage(response, AjaxResult.error(msg).toString());
    }

    /**
     * @param response
     * @param result
     */
    protected void write(HttpServletResponse response, String result) {
        try {
            response.setContentType("text/javascript");
            response.getWriter().print(result);
            response.getWriter().flush();
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
        }
    }

    /**
     * 查看用户对于app操作的权限
     *
     * @param request
     * @param appId
     * @return
     */
    protected boolean checkAppUserProvilege(HttpServletRequest request, long appId) {
        // 当前用户
        AppUser currentUser = getUserInfo(request);
        if (currentUser == null) {
            logger.error("currentUser is empty");
            return false;
        }

        if (AppUserTypeEnum.ADMIN_USER.value().equals(currentUser.getType())) {
            return true;
        }

        // 应用用户列表
        List<AppToUser> appToUsers = appService.getAppToUserList(appId);
        if (CollectionUtils.isEmpty(appToUsers)) {
            logger.error("appId {} userList is empty", appId);
            return false;
        }

        // 应用下用户id集合
        Set<Long> appUserIdSet = new HashSet<Long>();
        for (AppToUser appToUser : appToUsers) {
            appUserIdSet.add(appToUser.getUserId());
        }

        //最终判断
        if (!appUserIdSet.contains(currentUser.getId())) {
            logger.error("currentUser {} hasn't previlege in appId {}", currentUser.getId(), appId);
            return false;
        }
        return true;
    }

    /**
     * 节点列表页轻量数据：统计读 DB；Redis 数据节点在线时实时探测角色，失败时在页面提示。
     */
    protected void fillAppInstanceStatsLite(Long appId, Model model) {
        List<InstanceInfo> instanceList = appService.getAppBasicInstanceInfo(appId);
        List<InstanceStats> appInstanceStats = appService.getAppInstanceStats(appId);
        Map<String, InstanceStats> instanceStatsMap = new HashMap<>();
        for (InstanceStats instanceStats : appInstanceStats) {
            instanceStatsMap.put(instanceStats.getIp() + ":" + instanceStats.getPort(), instanceStats);
        }
        AppDesc appDesc = appService.getByAppId(appId);
        String password = appDesc != null ? appDesc.getAppPassword() : null;
        List<String> connectErrors = new ArrayList<>();
        for (InstanceInfo instanceInfo : instanceList) {
            InstanceStats stats = instanceStatsMap.get(instanceInfo.getIp() + ":" + instanceInfo.getPort());
            if (stats != null) {
                if (stats.getRole() == 1) {
                    instanceInfo.setRoleDesc(BooleanEnum.TRUE);
                } else if (stats.getRole() == 2) {
                    instanceInfo.setRoleDesc(BooleanEnum.FALSE);
                }
            }
            int type = instanceInfo.getType();
            if (!instanceInfo.isOnline() || !TypeUtil.isRedisType(type) || TypeUtil.isRedisSentinel(type)) {
                continue;
            }
            BooleanEnum isMaster = redisCenter.isMaster(appId, instanceInfo.getIp(), instanceInfo.getPort());
            instanceInfo.setRoleDesc(isMaster);
            if (isMaster == BooleanEnum.OTHER) {
                connectErrors.add(instanceInfo.getHostPort() + "：连接或认证失败，请检查密码与网络");
            }
        }
        for (InstanceInfo instanceInfo : instanceList) {
            int type = instanceInfo.getType();
            if (!instanceInfo.isOnline() || !TypeUtil.isRedisType(type) || TypeUtil.isRedisSentinel(type)) {
                continue;
            }
            if ("master".equals(instanceInfo.getRoleDesc())) {
                instanceInfo.setMasterInstanceId(0);
                continue;
            }
            if (!"slave".equals(instanceInfo.getRoleDesc()) || StringUtils.isBlank(password)) {
                continue;
            }
            HostAndPort master = redisCenter.getMaster(instanceInfo.getIp(), instanceInfo.getPort(), password);
            if (master != null) {
                instanceInfo.setMasterHost(master.getHost());
                instanceInfo.setMasterPort(master.getPort());
                int masterId = appService.resolveReplicationMasterInstanceId(
                        master.getHost(), master.getPort(), instanceInfo.getId(), instanceList);
                if (masterId > 0) {
                    instanceInfo.setMasterInstanceId(masterId);
                }
            }
        }
        if (!connectErrors.isEmpty()) {
            model.addAttribute("instanceConnectErrors", connectErrors);
        }
        model.addAttribute("instanceList", instanceList);
        model.addAttribute("instanceStatsMap", instanceStatsMap);
    }

    /**
     * 节点统计信息
     *
     * @param appId
     * @param model
     */
    protected void fillAppInstanceStats(Long appId, Model model) {
        // 节点列表
        List<InstanceInfo> instanceList = appService.getAppInstanceInfo(appId);
        model.addAttribute("instanceList", instanceList);
        Map<Integer, List<InstanceInfo>> instanceListMap = instanceGroupByMaster(instanceList);
        model.addAttribute("instanceListMap", instanceListMap);


        // 实例Map
        Map<Integer, InstanceInfo> instanceInfoMap = new HashMap<Integer, InstanceInfo>();
        for (InstanceInfo instanceInfo : instanceList) {
            instanceInfoMap.put(instanceInfo.getId(), instanceInfo);
        }
        model.addAttribute("instanceInfoMap", instanceInfoMap);

        // 实例统计
        List<InstanceStats> appInstanceStats = appService.getAppInstanceStats(appId);
        Map<String, InstanceStats> instanceStatsMap = new HashMap<String, InstanceStats>();
        for (InstanceStats instanceStats : appInstanceStats) {
            instanceStatsMap.put(instanceStats.getIp() + ":" + instanceStats.getPort(), instanceStats);
        }
        model.addAttribute("instanceStatsMap", instanceStatsMap);

        //slot分布
        Map<String, InstanceSlotModel> clusterSlotsMap = redisCenter.getClusterSlotsMap(appId);
        model.addAttribute("clusterSlotsMap", clusterSlotsMap);

        //机器列表
        long startTime = System.currentTimeMillis();
        List<MachineStats> machineList = machineCenter.getMachineStats(null, null, null, null, null, null, null);
        Map<String, MachineStats> machineMap = machineList.stream().collect(Collectors.toMap(MachineStats::getIp, machineStats -> machineStats));
        model.addAttribute("machineMap", machineMap);
        logger.info("getMachineStats cost: {}, appId: {}", System.currentTimeMillis() - startTime, appId);

        Map<String, Integer> machineInstanceCountMap = machineCenter.getMachineInstanceCountMap();
        model.addAttribute("machineInstanceCountMap", machineInstanceCountMap);
    }

    private Map<Integer, List<InstanceInfo>> instanceGroupByMaster(List<InstanceInfo> instanceList) {
        Map<Integer, List<InstanceInfo>> resultMap = new HashMap<Integer, List<InstanceInfo>>();
        for (InstanceInfo info : instanceList) {
            String roleDesc = info.getRoleDesc();
            if (roleDesc != null && "master".equals(roleDesc)) {
                List<InstanceInfo> list = (ArrayList<InstanceInfo>) MapUtils.getObject(resultMap, info.getId(), new ArrayList<InstanceInfo>());
                list.add(info);
                resultMap.put(info.getId(), list);
            } else if (roleDesc != null && "slave".equals(roleDesc)) {
                List<InstanceInfo> list = (ArrayList<InstanceInfo>) MapUtils.getObject(resultMap, info.getMasterInstanceId(), new ArrayList<InstanceInfo>());
                list.add(info);
                resultMap.put(info.getMasterInstanceId(), list);
            } else if (roleDesc != null && "sentinel".equals(roleDesc)) {
                List<InstanceInfo> list = (ArrayList<InstanceInfo>) MapUtils.getObject(resultMap, -2, new ArrayList<InstanceInfo>());
                list.add(info);
                resultMap.put(-2, list);
            } else {//offline
                List<InstanceInfo> list = (ArrayList<InstanceInfo>) MapUtils.getObject(resultMap, -1, new ArrayList<InstanceInfo>());
                list.add(info);
                resultMap.put(-1, list);
            }
        }
        return resultMap;
    }

    /**
     * 应用机器实例分布图
     *
     * @param appId
     * @param model
     */
    protected void fillAppMachineInstanceTopology(Long appId, Model model) {
        try {
            appService.syncAppTopology(appId);
        } catch (Exception e) {
            logger.warn("topology sync on page load failed appId={}: {}", appId, e.getMessage());
        }
        List<InstanceInfo> instanceList = appService.getAppInstanceInfo(appId);
        resolveTopologyMasterLinks(instanceList);
        assignTopologyGroups(instanceList);

        Map<String, List<InstanceInfo>> machineInstanceMap = new HashMap<String, List<InstanceInfo>>();
        for (InstanceInfo instance : instanceList) {
            String ip = instance.getIp();
            if (machineInstanceMap.containsKey(ip)) {
                machineInstanceMap.get(ip).add(instance);
            } else {
                List<InstanceInfo> tempInstanceList = new ArrayList<InstanceInfo>();
                tempInstanceList.add(instance);
                machineInstanceMap.put(ip, tempInstanceList);
            }
        }

        int maxGroupId = 0;
        for (InstanceInfo instance : instanceList) {
            if (instance.getGroupId() > maxGroupId) {
                maxGroupId = instance.getGroupId();
            }
        }

        model.addAttribute("machineInstanceMap", machineInstanceMap);
        model.addAttribute("instancePairCount", maxGroupId);
        model.addAttribute("topologyWarnings", collectTopologyWarnings(instanceList));
    }

    private void resolveTopologyMasterLinks(List<InstanceInfo> instanceList) {
        if (CollectionUtils.isEmpty(instanceList)) {
            return;
        }
        Map<Integer, InstanceInfo> byId = new HashMap<>();
        for (InstanceInfo instance : instanceList) {
            byId.put(instance.getId(), instance);
        }
        for (InstanceInfo instance : instanceList) {
            if (instance.isOffline() || !"slave".equals(instance.getRoleDesc())) {
                continue;
            }
            int masterId = instance.getMasterInstanceId();
            if (masterId > 0 && byId.containsKey(masterId)) {
                continue;
            }
            int resolved = appService.resolveReplicationMasterInstanceId(
                    instance.getMasterHost(), instance.getMasterPort(), instance.getId(), instanceList);
            if (resolved > 0) {
                instance.setMasterInstanceId(resolved);
            }
        }
    }

    private void assignTopologyGroups(List<InstanceInfo> instanceList) {
        Map<Integer, Integer> idToGroup = new HashMap<>();
        Map<Integer, InstanceInfo> byId = new HashMap<>();
        for (InstanceInfo instance : instanceList) {
            byId.put(instance.getId(), instance);
        }
        int nextGroup = 1;

        for (InstanceInfo instance : instanceList) {
            if (instance.isOffline()) {
                continue;
            }
            if ("master".equals(instance.getRoleDesc())) {
                if (!idToGroup.containsKey(instance.getId())) {
                    idToGroup.put(instance.getId(), nextGroup++);
                }
            }
        }

        for (InstanceInfo instance : instanceList) {
            if (instance.isOffline()) {
                continue;
            }
            int masterId = instance.getMasterInstanceId();
            if (masterId > 0 && byId.containsKey(masterId)) {
                Integer masterGroup = idToGroup.get(masterId);
                if (masterGroup == null) {
                    masterGroup = nextGroup++;
                    idToGroup.put(masterId, masterGroup);
                }
                idToGroup.put(instance.getId(), masterGroup);
            }
        }

        for (InstanceInfo instance : instanceList) {
            if (instance.isOffline()) {
                continue;
            }
            if (!idToGroup.containsKey(instance.getId())) {
                idToGroup.put(instance.getId(), nextGroup++);
            }
            instance.setGroupId(idToGroup.get(instance.getId()));
        }
    }

    private List<String> collectTopologyWarnings(List<InstanceInfo> instanceList) {
        Map<Integer, List<InstanceInfo>> groupMap = new HashMap<>();
        for (InstanceInfo instance : instanceList) {
            if (instance.isOffline() || instance.getGroupId() <= 0 || TypeUtil.isRedisSentinel(instance.getType())) {
                continue;
            }
            groupMap.computeIfAbsent(instance.getGroupId(), key -> new ArrayList<>()).add(instance);
        }
        List<String> warnings = new ArrayList<>();
        for (Map.Entry<Integer, List<InstanceInfo>> entry : groupMap.entrySet()) {
            int groupId = entry.getKey();
            List<InstanceInfo> nodes = entry.getValue();
            int masterCount = 0;
            int slaveCount = 0;
            StringBuilder slaveHosts = new StringBuilder();
            for (InstanceInfo node : nodes) {
                String role = node.getRoleDesc();
                if ("master".equals(role)) {
                    masterCount++;
                } else if ("slave".equals(role)) {
                    slaveCount++;
                    if (slaveHosts.length() > 0) {
                        slaveHosts.append("、");
                    }
                    slaveHosts.append(node.getHostPort());
                }
            }
            if (masterCount == 0 && slaveCount > 0) {
                warnings.add("主从组 " + groupId + " 未能关联 Master（从节点 "
                        + slaveHosts + " 的复制源未匹配到本集群节点，可刷新页面重试或检查纳管 IP/端口）");
            } else if (masterCount > 1) {
                warnings.add("主从组 " + groupId + " 存在多个 Master 节点，请核对 Redis 复制关系");
            }
        }
        return warnings;
    }

    /**
     * 修改用户资料的时候，不能修改用户名
     * ref: 后面密码校验按照用户名、密码校验
     *
     * @param userId
     * @param name
     */
    public void checkIdName(Long userId, String name) {
        AppUser appUser = userService.get(userId);
        if (appUser == null || !appUser.getName().equals(name)) {
            throw new BizException("输入参数错误，id/name不匹配");
        }
    }

    /**
     * 新增用户的时候，校验用户是否存在
     *
     * @param name
     */
    public void assertUserNotExist(String name) {
        if (userService.getByName(name) != null) {
            throw new BizException("输入参数错误，已存在该用户");
        }
    }


}
