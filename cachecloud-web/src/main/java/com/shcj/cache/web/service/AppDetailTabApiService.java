package com.shcj.cache.web.service;

import com.shcj.cache.constant.AppDescEnum;
import com.shcj.cache.constant.InstanceStatusEnum;
import com.shcj.cache.constant.TimeDimensionalityEnum;
import com.shcj.cache.dao.ExternalRedisDao;
import com.shcj.cache.dao.InstanceDao;
import com.shcj.cache.dao.InstanceLatencyHistoryDao;
import com.shcj.cache.entity.*;
import com.shcj.cache.exception.BizException;
import com.shcj.cache.redis.RedisCenter;
import com.shcj.cache.stats.app.AppDailyDataCenter;
import com.shcj.cache.stats.app.AppStatsCenter;
import com.shcj.cache.stats.app.ExternalRedisCenter;
import com.shcj.cache.util.AppClusterNoSupport;
import com.shcj.cache.util.TypeUtil;
import com.shcj.cache.util.ConstUtils;
import com.shcj.cache.stats.instance.InstanceStatsCenter;
import com.shcj.cache.web.chart.model.HighchartDoublePoint;
import com.shcj.cache.web.chart.model.HighchartPoint;
import com.shcj.cache.web.chart.model.SimpleChartData;
import com.shcj.cache.web.controller.api.dto.*;
import com.shcj.cache.web.enums.BooleanEnum;
import com.shcj.cache.web.enums.SuccessEnum;
import com.shcj.cache.web.support.AppRouteIdSupport;
import com.shcj.cache.web.util.DateUtil;
import com.shcj.cache.web.vo.AppDetailVO;
import com.shcj.cache.entity.TimeBetween;
import com.shcj.cache.constant.AppUserTypeEnum;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.math.NumberUtils;
import org.apache.commons.lang.time.DateUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import redis.clients.jedis.HostAndPort;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Service
public class AppDetailTabApiService {

    private static final org.slf4j.Logger logger =
            org.slf4j.LoggerFactory.getLogger(AppDetailTabApiService.class);

    private static final long APP_STAT_MAX_RANGE_MS = TimeUnit.DAYS.toMillis(7);
    private static final long TOPOLOGY_CACHE_TTL_MS = 15_000L;

    private final ConcurrentHashMap<Long, TopologyCacheEntry> topologyCache = new ConcurrentHashMap<>();

    @Autowired
    private AppService appService;

    @Autowired
    private RedisCenter redisCenter;

    @Autowired
    private AppStatsCenter appStatsCenter;

    @Autowired
    private AppDailyDataCenter appDailyDataCenter;

    @Autowired
    private InstanceStatsCenter instanceStatsCenter;

    @Autowired
    private InstanceDao instanceDao;

    @Autowired
    private InstanceLatencyHistoryDao instanceLatencyHistoryDao;

    @Autowired
    private com.shcj.cache.dao.InstanceSlowLogDao instanceSlowLogDao;

    @Autowired
    private UserService userService;

    @Autowired
    private ExternalRedisDao externalRedisDao;

    @Autowired
    private ExternalRedisCenter externalRedisCenter;

    private static final Set<String> APP_OPS_MAX_STATS = new HashSet<String>(Arrays.asList(
            "latest_fork_usec", "repl_lag_max", "master_repl_offset"));

    @Autowired
    private AppRouteIdSupport appRouteIdSupport;

    private long resolveRouteAppId(long idOrClusterNo) {
        return appRouteIdSupport.requireAppId(idOrClusterNo);
    }

    public AppTopologyDto getTopology(long appId) {
        return getTopology(appId, false);
    }

    public AppTopologyDto getTopology(long appId, boolean liveProbe) {
        appId = resolveRouteAppId(appId);
        if (!liveProbe) {
            TopologyCacheEntry cached = topologyCache.get(appId);
            if (cached != null && cached.expireAt > System.currentTimeMillis()) {
                return cached.dto;
            }
        }

        List<InstanceInfo> instanceList = appService.getAppBasicInstanceInfo(appId);
        List<InstanceStats> appInstanceStats = appService.getAppInstanceStats(appId);
        Map<String, InstanceStats> statsMap = new HashMap<>();
        if (appInstanceStats != null) {
            for (InstanceStats s : appInstanceStats) {
                statsMap.put(s.getIp() + ":" + s.getPort(), s);
            }
        }
        AppDesc appDesc = appService.getByAppId(appId);
        String password = appDesc != null ? appDesc.getAppPassword() : null;
        List<String> connectErrors = new ArrayList<>();

        if (instanceList != null) {
            resolveTopologyRoles(appId, instanceList, statsMap, connectErrors, liveProbe);
            resolveTopologyMasters(instanceList, password, liveProbe);
        }

        AppTopologyDto dto = new AppTopologyDto();
        dto.setConnectErrors(connectErrors);
        if (instanceList != null) {
            for (InstanceInfo inst : instanceList) {
                if (inst.getHostId() == 0) {
                    dto.setHasExternalInstance(true);
                }
                if (!shouldShowInTopologyList(inst)) {
                    continue;
                }
                dto.getInstances().add(toTopologyItem(inst, statsMap.get(inst.getIp() + ":" + inst.getPort())));
            }
        }

        if (!liveProbe) {
            topologyCache.put(appId, new TopologyCacheEntry(System.currentTimeMillis() + TOPOLOGY_CACHE_TTL_MS, dto));
        }
        return dto;
    }

    private void resolveTopologyRoles(long appId, List<InstanceInfo> instanceList, Map<String, InstanceStats> statsMap,
            List<String> connectErrors, boolean liveProbe) {
        for (InstanceInfo inst : instanceList) {
            int type = inst.getType();
            if (!inst.isOnline() || !TypeUtil.isRedisType(type) || TypeUtil.isRedisSentinel(type)) {
                continue;
            }
            BooleanEnum role = resolveRoleFromStats(statsMap.get(inst.getIp() + ":" + inst.getPort()));
            if (role == null && liveProbe) {
                role = redisCenter.isMaster(appId, inst.getIp(), inst.getPort());
            } else if (role == null) {
                role = inst.getMasterInstanceId() == 0 ? BooleanEnum.TRUE : BooleanEnum.FALSE;
            }
            inst.setRoleDesc(role);
            if (liveProbe && role == BooleanEnum.OTHER) {
                connectErrors.add(inst.getHostPort() + "：连接或认证失败");
            }
        }
    }

    private void resolveTopologyMasters(List<InstanceInfo> instanceList, String password, boolean liveProbe) {
        for (InstanceInfo inst : instanceList) {
            int type = inst.getType();
            if (!inst.isOnline() || !TypeUtil.isRedisType(type) || TypeUtil.isRedisSentinel(type)) {
                continue;
            }
            if ("master".equals(inst.getRoleDesc())) {
                inst.setMasterInstanceId(0);
                continue;
            }
            if (!"slave".equals(inst.getRoleDesc())) {
                continue;
            }
            if (inst.getMasterInstanceId() > 0) {
                continue;
            }
            if (!liveProbe || StringUtils.isBlank(password)) {
                continue;
            }
            HostAndPort master = redisCenter.getMaster(inst.getIp(), inst.getPort(), password);
            if (master != null) {
                int masterId = appService.resolveReplicationMasterInstanceId(
                        master.getHost(), master.getPort(), inst.getId(), instanceList);
                if (masterId > 0) {
                    inst.setMasterInstanceId(masterId);
                }
            }
        }
    }

    private BooleanEnum resolveRoleFromStats(InstanceStats stats) {
        if (stats == null) {
            return null;
        }
        if (stats.getRole() == 1) {
            return BooleanEnum.TRUE;
        }
        if (stats.getRole() == 2) {
            return BooleanEnum.FALSE;
        }
        return null;
    }

    private static class TopologyCacheEntry {
        private final long expireAt;
        private final AppTopologyDto dto;

        private TopologyCacheEntry(long expireAt, AppTopologyDto dto) {
            this.expireAt = expireAt;
            this.dto = dto;
        }
    }

    public AppDetailPanelDto getDetailPanel(long appId, AppUser currentUser) {
        appId = resolveRouteAppId(appId);
        AppDetailPanelDto panel = new AppDetailPanelDto();
        AppDetailVO detail = appStatsCenter.getAppDetail(appId);
        if (detail == null || detail.getAppDesc() == null) {
            return panel;
        }
        AppDesc appDesc = detail.getAppDesc();
        boolean hasAuth = currentUser != null
                && currentUser.getType() == AppUserTypeEnum.ADMIN_USER.value();
        panel.setHasAuth(hasAuth);

        AppDetailPanelDto.AppInfoSectionDto info = panel.getAppInfo();
        info.setAppId(appDesc.getAppId());
        info.setClusterNo(AppClusterNoSupport.displayClusterNo(appDesc));
        info.setAppName(appDesc.getName());
        info.setTypeDesc(appDesc.getTypeDesc());
        info.setImportantLevelLabel(resolveImportantLevel(appDesc.getImportantLevel()));
        info.setAlertUsersText(formatAlertUsers(detail.getAlertUsers()));
        if (detail.getOfficer() != null) {
            info.setOfficerText(detail.getOfficer().getChName() + "(" + detail.getOfficer().getName() + ")");
        } else {
            info.setOfficerText(userService.getOfficerName(appDesc.getOfficer()));
        }
        info.setMemTotalGb(String.format("%.2f", detail.getMem() / 1024.0));
        info.setMachineNum(detail.getMachineNum());
        info.setMasterNum(detail.getMasterNum());
        info.setSlaveNum(detail.getSlaveNum());
        info.setIntro(appDesc.getIntro());
        info.setOfficerId(StringUtils.defaultString(appDesc.getOfficer(), ""));
        info.setMasterName(appDesc.getMasterName());
        info.setHasSentinelPwd(appDesc.getHasSentinelPwd());

        // 内存使用率/连接数/命中率三项阈值已迁到「报警配置」统一维护，这里只留通知开关
        AppDetailPanelDto.AppAlertConfigFormDto alertConfig = panel.getAlertConfig();
        alertConfig.setIsAccessMonitor(appDesc.getIsAccessMonitor());

        panel.getAlertMetrics().add(buildAlertMetric(4, "集群报警通知", "",
                appDesc.getIsAccessMonitor() == 0 ? "监控关闭" : "监控开启"));

        if (detail.getAppUsers() != null) {
            for (AppUser user : detail.getAppUsers()) {
                AppDetailPanelDto.AppDetailUserDto row = new AppDetailPanelDto.AppDetailUserDto();
                row.setId(user.getId());
                row.setName(user.getName());
                row.setChName(user.getChName());
                row.setEmail(user.getEmail());
                row.setMobile(user.getMobile());
                row.setCompany(user.getCompany());
                row.setAlert(user.getIsAlert() == 1);
                row.setType(user.getType());
                panel.getUsers().add(row);
            }
        }
        return panel;
    }

    public void updateAppDetail(long appId, AppDetailUpdateRequestDto body, AppUser operator) {
        appId = resolveRouteAppId(appId);
        requireAdmin(operator);
        if (body == null || StringUtils.isBlank(body.getAppName()) || StringUtils.isBlank(body.getIntro())
                || StringUtils.isBlank(body.getOfficerId())) {
            throw new BizException("集群名称、描述、负责人不能为空");
        }
        AppDesc byName = appService.getAppByName(body.getAppName());
        if (byName != null && byName.getAppId() != appId) {
            throw new BizException("集群名称已被其它系统占用");
        }
        AppDesc appDesc = appService.getByAppId(appId);
        if (appDesc == null) {
            throw new BizException("集群不存在");
        }
        String newName = body.getAppName().trim();
        String newIntro = body.getIntro().trim();
        appDesc.setName(newName);
        appDesc.setIntro(newIntro);
        appDesc.setOfficer(body.getOfficerId().split(",")[0].trim());
        appService.update(appDesc);
        // external_redis 里存了一份纳管当时的 name/intro 快照，不同步就会与 app_desc 脱节，
        // 后续「重新纳管/补写实例」等按登记记录走的流程会拿到旧名字。
        // 该集群不是外部纳管的话，updateNameAndIntro 影响 0 行，属正常情况。
        externalRedisDao.updateNameAndIntro(appId, newName, newIntro);
    }

    public void changeAppAlertConfig(long appId, AppAlertConfigUpdateRequestDto body, AppUser operator) {
        appId = resolveRouteAppId(appId);
        if (body == null || appId <= 0) {
            throw new BizException("参数非法");
        }
        SuccessEnum result = appService.changeAppAlertConfig(appId, body.getIsAccessMonitor(), operator);
        if (result != SuccessEnum.SUCCESS) {
            throw new BizException("更新报警配置失败");
        }
    }

    public void addAppUsers(long appId, AppAddUsersRequestDto body, AppUser operator) {
        appId = resolveRouteAppId(appId);
        requireAdmin(operator);
        if (body == null || CollectionUtils.isEmpty(body.getUserIds())) {
            throw new BizException("请选择用户");
        }
        for (Long userId : body.getUserIds()) {
            if (userId == null || userId <= 0) {
                throw new BizException("用户参数非法");
            }
            if (!appService.saveAppToUser(appId, userId)) {
                throw new BizException("添加失败，只能添加有 Redis 管理平台权限的用户");
            }
        }
    }

    public void deleteAppUser(long appId, long userId, AppUser operator) {
        appId = resolveRouteAppId(appId);
        requireAdmin(operator);
        if (appId <= 0 || userId <= 0) {
            throw new BizException("参数非法");
        }
        SuccessEnum result = appService.deleteAppToUser(appId, userId);
        if (result != SuccessEnum.SUCCESS) {
            throw new BizException("删除用户失败");
        }
    }

    public void updateAppUser(long appId, long userId, AppDetailUserUpdateRequestDto body, AppUser operator) {
        appId = resolveRouteAppId(appId);
        requireAdmin(operator);
        if (body == null || userId <= 0) {
            throw new BizException("参数非法");
        }
        if (StringUtils.isBlank(body.getName()) || StringUtils.isBlank(body.getChName())
                || StringUtils.isBlank(body.getMobile()) || StringUtils.isBlank(body.getCompany())) {
            throw new BizException("域账户、中文名、手机、部门不能为空");
        }
        AppUser appUser = AppUser.buildFrom(
                userId,
                body.getName().trim(),
                body.getChName().trim(),
                StringUtils.defaultString(body.getEmail(), ""),
                body.getMobile().trim(),
                null,
                body.getType(),
                body.getIsAlert(),
                body.getCompany().trim(),
                null);
        userService.update(appUser);
    }

    private void requireAdmin(AppUser user) {
        if (user == null || user.getType() != AppUserTypeEnum.ADMIN_USER.value()) {
            throw new BizException("无权限执行此操作");
        }
    }

    public List<AppClientItemDto> getClientList(long appId, int condition) {
        return getClientList(appId, condition, false);
    }

    public List<AppClientItemDto> getClientList(long appId, int condition, boolean includeDetails) {
        appId = resolveRouteAppId(appId);
        int cond = condition <= 0 ? 3 : condition;
        List<Map<String, Object>> raw = redisCenter.getAppClientList(appId, cond);
        List<InstanceInfo> instanceInfoList = appService.getAppOnlineInstanceInfo(appId);
        Map<Integer, String> instanceMap = new HashMap<>();
        if (instanceInfoList != null) {
            for (InstanceInfo info : instanceInfoList) {
                instanceMap.put(info.getId(), info.getHostPort());
            }
        }
        List<AppClientItemDto> items = new ArrayList<>();
        if (raw == null) {
            return items;
        }
        for (Map<String, Object> row : raw) {
            AppClientItemDto dto = new AppClientItemDto();
            dto.setAddr(String.valueOf(row.getOrDefault("addr", "")));
            Object size = row.get("size");
            dto.setSize(size instanceof Number ? ((Number) size).longValue() : 0L);
            Object flagsObj = row.get("flags");
            if (flagsObj instanceof Set) {
                for (Object flag : (Set<?>) flagsObj) {
                    if (flag != null) {
                        dto.getFlags().add(String.valueOf(flag));
                    }
                }
            }
            Object statsObj = row.get("instanceClientStats");
            if (statsObj instanceof Map) {
                Map<?, ?> statsMap = (Map<?, ?>) statsObj;
                for (Map.Entry<?, ?> entry : statsMap.entrySet()) {
                    int instanceId = NumberUtils.toInt(String.valueOf(entry.getKey()));
                    if (!(entry.getValue() instanceof Map)) {
                        continue;
                    }
                    Map<?, ?> clientInfo = (Map<?, ?>) entry.getValue();
                    AppClientInstanceStatDto stat = new AppClientInstanceStatDto();
                    stat.setInstanceId(instanceId);
                    stat.setHostPort(instanceMap.getOrDefault(instanceId, String.valueOf(instanceId)));
                    Object count = clientInfo.get("count");
                    stat.setCount(count instanceof Number ? ((Number) count).longValue() : 0L);
                    if (includeDetails) {
                        appendClientConnections(stat, clientInfo.get("clientInfoList"));
                    }
                    dto.getInstanceStats().add(stat);
                }
            }
            items.add(dto);
        }
        return items;
    }

    private void appendClientConnections(AppClientInstanceStatDto stat, Object connList) {
        if (!(connList instanceof Collection)) {
            return;
        }
        for (Object c : (Collection<?>) connList) {
            if (!(c instanceof Map)) {
                continue;
            }
            Map<?, ?> clientInfo = (Map<?, ?>) c;
            InstanceClientConnectionDto connection = new InstanceClientConnectionDto();
            connection.setFlags(MapUtils.getString(clientInfo, "flags", ""));
            connection.setCmd(MapUtils.getString(clientInfo, "cmd", ""));
            connection.setDetail(String.valueOf(clientInfo));
            stat.getConnectionDetails().add(connection);
        }
    }

    public AppStatOverviewDto getStatOverview(long appId, String startDate, String endDate) throws ParseException {
        return getStatOverview(appId, startDate, endDate, true);
    }

    public AppStatOverviewDto getStatOverview(long appId, String startDate, String endDate, boolean includeClimax)
            throws ParseException {
        appId = resolveRouteAppId(appId);
        boolean statRangeClamped = false;
        if (StringUtils.isNotBlank(startDate) && StringUtils.isNotBlank(endDate)) {
            Date rawStart = DateUtil.parseRangeDateTime(startDate);
            Date rawEnd = DateUtil.parseRangeDateTime(endDate);
            if (endDate.trim().length() <= 10) {
                rawEnd = DateUtils.addDays(rawEnd, 1);
            }
            statRangeClamped = rawEnd.getTime() - rawStart.getTime() > APP_STAT_MAX_RANGE_MS;
        }
        TimeBetween range = resolveTimeRange(startDate, endDate);
        AppDetailVO detail = appStatsCenter.getAppDetail(appId);
        AppStatOverviewDto dto = new AppStatOverviewDto();
        dto.setAppId(appId);
        dto.setStartDate(DateUtil.formatRangeDateTime(range.getStartDate()));
        dto.setEndDate(DateUtil.formatRangeDateTime(range.getEndDate()));
        dto.setStatRangeClamped(statRangeClamped);
        if (detail != null) {
            dto.setAppName(detail.getAppDesc() != null ? detail.getAppDesc().getName() : "");
            dto.setMem(detail.getMem());
            dto.setMemUsePercent(detail.getMemUsePercent());
            dto.setHitPercent(detail.getHitPercent());
            dto.setInstanceCount(detail.getMasterNum() + detail.getSlaveNum() + detail.getSentinelNum());
            dto.setConn(detail.getConn());
            dto.setMasterNum(detail.getMasterNum());
            dto.setSlaveNum(detail.getSlaveNum());
            dto.setCurrentObjNum(detail.getCurrentObjNum());
            dto.setMachineNum(detail.getMachineNum());
            if (detail.getAppDesc() != null) {
                dto.setVersionName(resolveOverviewVersionName(detail.getAppDesc()));
                dto.setTypeDesc(detail.getAppDesc().getTypeDesc());
                dto.setStatusDesc(detail.getAppDesc().getStatusDesc());
            }
        }
        List<AppCommandStats> top5 = appStatsCenter.getTopLimitAppCommandStatsList(
                appId, range.getStartTime(), range.getEndTime(), 5);
        if (top5 != null) {
            for (AppCommandStats stat : top5) {
                dto.getTop5Commands().add(toChartPoint(SimpleChartData.getFromAppCommandStats(stat, null)));
            }
            if (includeClimax) {
                dto.getTop5Climax().addAll(buildTop5Climax(appId, range, top5));
            }
        }
        return dto;
    }

    private String resolveOverviewVersionName(AppDesc appDesc) {
        if (appDesc == null) {
            return "";
        }
        ExternalRedis externalRedis = externalRedisDao.getByAppId(appDesc.getAppId());
        if (externalRedis != null) {
            try {
                String detected = externalRedisCenter.detectRedisVersionName(externalRedis);
                if (StringUtils.isNotBlank(detected)) {
                    return detected;
                }
            } catch (Exception ignored) {
                return StringUtils.defaultString(appDesc.getVersionName());
            }
        }
        return StringUtils.defaultString(appDesc.getVersionName());
    }

    public List<AppCommandClimaxDto> getStatClimax(long appId, String startDate, String endDate) throws ParseException {
        appId = resolveRouteAppId(appId);
        TimeBetween range = resolveTimeRange(startDate, endDate);
        List<AppCommandStats> top5 = appStatsCenter.getTopLimitAppCommandStatsList(
                appId, range.getStartTime(), range.getEndTime(), 5);
        if (top5 == null || top5.isEmpty()) {
            return new ArrayList<AppCommandClimaxDto>();
        }
        return buildTop5Climax(appId, range, top5);
    }

    private List<AppCommandClimaxDto> buildTop5Climax(long appId, TimeBetween range, List<AppCommandStats> top5) {
        List<AppCommandClimaxDto> climaxList = new ArrayList<AppCommandClimaxDto>();
        SimpleDateFormat climaxSdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        for (AppCommandStats stat : top5) {
            AppCommandStats peak = appStatsCenter.getCommandClimax(
                    appId, range.getStartTime(), range.getEndTime(), stat.getCommandName());
            if (peak == null) {
                continue;
            }
            AppCommandClimaxDto climax = new AppCommandClimaxDto();
            climax.setCommandName(peak.getCommandName());
            climax.setCommandCount(peak.getCommandCount());
            climax.setCreateTime(peak.getCreateTime() != null ? climaxSdf.format(peak.getCreateTime()) : "");
            climaxList.add(climax);
        }
        return climaxList;
    }

    public List<ChartPointDto> getAppStatChart(long appId, String statName, String startDate, String endDate)
            throws ParseException {
        appId = resolveRouteAppId(appId);
        String name = StringUtils.isBlank(statName) ? "hitPercent" : statName;
        Map<String, List<ChartPointDto>> batch = getAppStatChartsBatch(appId, name, startDate, endDate);
        List<ChartPointDto> points = batch.get(name);
        return points != null ? points : new ArrayList<ChartPointDto>();
    }

    public Map<String, List<ChartPointDto>> getAppStatChartsBatch(long appId, String statNames, String startDate,
                                                                  String endDate) throws ParseException {
        appId = resolveRouteAppId(appId);
        Map<String, List<ChartPointDto>> result = new LinkedHashMap<String, List<ChartPointDto>>();
        if (StringUtils.isBlank(statNames)) {
            return result;
        }
        List<String> statNameList = new ArrayList<String>();
        for (String item : statNames.split(",")) {
            if (StringUtils.isNotBlank(item)) {
                statNameList.add(item.trim());
            }
        }
        for (String statName : statNameList) {
            result.put(statName, new ArrayList<ChartPointDto>());
        }
        TimeBetween range = resolveTimeRange(startDate, endDate);
        List<AppStats> stats = appStatsCenter.getAppStatsListByMinuteTime(
                appId, range.getStartTime(), range.getEndTime());
        if (stats == null || stats.isEmpty()) {
            return result;
        }
        for (AppStats stat : stats) {
            for (String key : statNameList) {
                try {
                    ChartPointDto point = toAppStatChartPoint(stat, key);
                    if (point != null) {
                        result.get(key).add(point);
                    }
                } catch (ParseException e) {
                    // skip bad point
                }
            }
        }
        for (String statName : statNameList) {
            if (isCpuRateStat(statName)) {
                result.put(statName, getAppCpuRatePoints(appId, statName, range));
            }
        }
        return result;
    }

    private List<ChartPointDto> getAppCpuRatePoints(long appId, String statName, TimeBetween range) {
        String redisKey;
        if ("cpuSys".equals(statName)) redisKey = "used_cpu_sys";
        else if ("cpuUser".equals(statName)) redisKey = "used_cpu_user";
        else redisKey = "used_cpu_user_children";

        TreeMap<Long, Double> aggregate = new TreeMap<Long, Double>();
        List<InstanceInfo> instances = instanceDao.getInstListByAppId(appId);
        if (instances == null) return new ArrayList<ChartPointDto>();
        for (InstanceInfo instance : instances) {
            if (!TypeUtil.isRedisDataType(instance.getType())) continue;
            List<Map<String, Object>> rows = instanceStatsCenter.queryDiffMapList(
                    range.getStartTime(), range.getEndTime(), instance.getIp(), instance.getPort(), ConstUtils.REDIS);
            for (Map<String, Object> row : rows) {
                Long collectTime = MapUtils.getLong(row, ConstUtils.COLLECT_TIME);
                Double value = MapUtils.getDouble(row, redisKey);
                if (collectTime == null || value == null) continue;
                aggregate.put(collectTime, aggregate.getOrDefault(collectTime, 0D) + value);
            }
        }
        List<ChartPointDto> points = new ArrayList<ChartPointDto>();
        Long previousTime = null;
        for (Map.Entry<Long, Double> entry : aggregate.entrySet()) {
            if (previousTime != null) {
                try {
                    Date previousDate = DateUtil.parseYYYYMMddHHMM(String.valueOf(previousTime));
                    Date currentDate = DateUtil.parseYYYYMMddHHMM(String.valueOf(entry.getKey()));
                    double intervalSeconds = (currentDate.getTime() - previousDate.getTime()) / 1000D;
                    if (intervalSeconds > 0) {
                        double rate = Math.round(entry.getValue() / intervalSeconds * 100D * 10000D) / 10000D;
                        ChartPointDto point = new ChartPointDto();
                        point.setX(currentDate.getTime());
                        point.setYDouble(rate);
                        point.setDate(DateUtil.formatDate(currentDate, "yyyy-MM-dd HH:mm"));
                        points.add(point);
                    }
                } catch (ParseException ignored) { }
            }
            previousTime = entry.getKey();
        }
        return points;
    }

    private boolean isCpuRateStat(String statName) {
        return "cpuSys".equals(statName) || "cpuUser".equals(statName)
                || "cpuUserChildren".equals(statName);
    }

    private List<ChartPointDto> toCpuRatePoints(List<ChartPointDto> points) {
        List<ChartPointDto> rates = new ArrayList<ChartPointDto>();
        if (points == null || points.size() < 2) return rates;
        points.sort(Comparator.comparingLong(ChartPointDto::getX));
        for (int i = 1; i < points.size(); i++) {
            ChartPointDto previous = points.get(i - 1);
            ChartPointDto current = points.get(i);
            long intervalMillis = current.getX() - previous.getX();
            if (intervalMillis <= 0) continue;
            double cpuSeconds = current.getYDouble() != null ? current.getYDouble() : current.getY();
            double rate = cpuSeconds * 100000D / intervalMillis;
            ChartPointDto point = new ChartPointDto();
            point.setX(current.getX());
            point.setYDouble(rate);
            point.setDate(current.getDate());
            rates.add(point);
        }
        return rates;
    }

    private ChartPointDto toAppStatChartPoint(AppStats stat, String key) throws ParseException {
        if ("memFragRatio".equals(key)) {
            HighchartDoublePoint point = HighchartDoublePoint.getFromAppStatsTimeline(stat, key);
            return point != null ? toChartPoint(point) : null;
        }
        HighchartPoint point = HighchartPoint.getFromAppStatsTimeline(stat, key);
        return point != null ? toChartPoint(point) : null;
    }

    public Map<String, List<ChartPointDto>> getAppOpsStatCharts(long appId, String statNames, String startDate,
                                                                String endDate) throws ParseException {
        appId = resolveRouteAppId(appId);
        Map<String, List<ChartPointDto>> result = new LinkedHashMap<String, List<ChartPointDto>>();
        if (StringUtils.isBlank(statNames)) {
            return result;
        }
        List<String> statNameList = new ArrayList<String>();
        for (String item : statNames.split(",")) {
            if (StringUtils.isNotBlank(item)) {
                statNameList.add(item.trim());
            }
        }
        for (String statName : statNameList) {
            result.put(statName, new ArrayList<ChartPointDto>());
        }
        TimeBetween range = resolveTimeRange(startDate, endDate);
        Map<Integer, Map<String, List<InstanceCommandStats>>> instanceStats = instanceStatsCenter
                .getStandardStatsList(appId, range.getStartTime(), range.getEndTime(), statNameList);
        if (instanceStats == null || instanceStats.isEmpty()) {
            return result;
        }
        Map<String, TreeMap<Long, Long>> aggregated = new LinkedHashMap<String, TreeMap<Long, Long>>();
        for (String statName : statNameList) {
            aggregated.put(statName, new TreeMap<Long, Long>());
        }
        for (Map<String, List<InstanceCommandStats>> commandMap : instanceStats.values()) {
            if (commandMap == null) {
                continue;
            }
            for (String key : statNameList) {
                List<InstanceCommandStats> points = commandMap.get(key);
                if (points == null) {
                    continue;
                }
                TreeMap<Long, Long> timeMap = aggregated.get(key);
                for (InstanceCommandStats point : points) {
                    long collectTime = point.getCollectTime();
                    long value = point.getCommandCount();
                    if (APP_OPS_MAX_STATS.contains(key)) {
                        Long existing = timeMap.get(collectTime);
                        timeMap.put(collectTime, existing == null ? value : Math.max(existing, value));
                    } else {
                        timeMap.put(collectTime, MapUtils.getLongValue(timeMap, collectTime, 0L) + value);
                    }
                }
            }
        }
        for (String key : statNameList) {
            List<ChartPointDto> points = new ArrayList<ChartPointDto>();
            TreeMap<Long, Long> timeMap = aggregated.get(key);
            if (timeMap != null) {
                for (Map.Entry<Long, Long> entry : timeMap.entrySet()) {
                    try {
                        ChartPointDto dto = buildOpsChartPoint(key, entry.getKey(), entry.getValue());
                        if (dto != null) {
                            points.add(dto);
                        }
                    } catch (ParseException e) {
                        // skip bad point
                    }
                }
            }
            result.put(key, points);
        }
        return result;
    }

    public List<ChartPointDto> getCommandChart(long appId, String commandName, String startDate, String endDate)
            throws ParseException {
        appId = resolveRouteAppId(appId);
        TimeBetween range = resolveTimeRange(startDate, endDate);
        List<AppCommandStats> stats;
        if (StringUtils.isNotBlank(commandName)) {
            stats = appStatsCenter.getCommandStatsListV2(
                    appId, range.getStartTime(), range.getEndTime(), TimeDimensionalityEnum.MINUTE, commandName);
        } else {
            stats = appStatsCenter.getCommandStatsListV2(
                    appId, range.getStartTime(), range.getEndTime(), TimeDimensionalityEnum.MINUTE);
        }
        List<ChartPointDto> points = new ArrayList<>();
        if (stats == null) {
            return points;
        }
        for (AppCommandStats stat : stats) {
            points.add(toChartPoint(SimpleChartData.getFromAppCommandStats(stat, null)));
        }
        return points;
    }

    public List<ChartPointDto> getTop5Commands(long appId, String startDate, String endDate) throws ParseException {
        appId = resolveRouteAppId(appId);
        TimeBetween range = resolveTimeRange(startDate, endDate);
        List<AppCommandStats> top5 = appStatsCenter.getTop5AppCommandStatsList(
                appId, range.getStartTime(), range.getEndTime());
        List<ChartPointDto> points = new ArrayList<>();
        if (top5 == null) {
            return points;
        }
        for (AppCommandStats stat : top5) {
            points.add(toChartPoint(SimpleChartData.getFromAppCommandStats(stat, null)));
        }
        return points;
    }

    /**
     * 判断实时汇总结果是否真的有采集样本。
     *
     * <p>用内存和连接数而不是命令数做判据：一个整天空闲的实例命令数确实可能是 0，
     * 但只要有采样，used_memory 一定大于 0——Redis 自身就占内存。
     */
    private boolean hasAnySample(AppDailyData data) {
        return data.getMaxUsedMemory() > 0
                || data.getMaxMinuteClientCount() > 0
                || data.getMaxMinuteCommandCount() > 0
                || data.getSlowLogCount() > 0;
    }

    public AppDailyDto getDaily(long appId, String dailyDate) throws ParseException {
        appId = resolveRouteAppId(appId);
        Date date;
        if (StringUtils.isBlank(dailyDate)) {
            date = DateUtils.addDays(new Date(), -1);
            dailyDate = DateUtil.formatDate(date, "yyyy-MM-dd");
        } else {
            date = DateUtil.parseYYYY_MM_dd(dailyDate);
        }
        AppDailyData data = appDailyDataCenter.getAppDailyData(appId, date);
        boolean persisted = data != null;
        if (data == null) {
            // 落库行缺失时（定时任务尚未执行到该日期、或平台刚接入）改为按分钟表实时汇总。
            // 原先只对测试应用兜底，导致普通集群在日报落库之前一直显示无数据；
            // 是否为实时汇总由 DTO 的 persisted 标记，页面据此提示。
            data = appDailyDataCenter.buildAppDailySnapshot(appId, date, DateUtils.addDays(date, 1));
            // 实时汇总在该日期完全没有采集样本时也会返回一个全 0 对象，
            // 直接展示会让人以为「当天真的一条命令都没有」，这里退回无数据
            if (data != null && !hasAnySample(data)) {
                data = null;
            }
        }
        AppDesc appDesc = appService.getByAppId(appId);
        AppDailyDto dto = new AppDailyDto();
        dto.setDailyDate(dailyDate);
        dto.setHasData(data != null);
        // persisted / testApp 此前声明了却从未赋值，恒为 false；页面要区分「定时落库」与「实时汇总」，这里补上
        dto.setPersisted(persisted);
        dto.setTestApp(appDesc != null && appDesc.isTestOk());
        if (data != null) {
            dto.setBigKeyTimes(data.getBigKeyTimes());
            dto.setBigKeyInfo(data.getBigKeyInfo());
            dto.setValueSizeDistributeDescHtml(data.getValueSizeDistributeCountDescHtml());
            dto.setSlowLogCount(data.getSlowLogCount());
            dto.setLatencyCount(data.getLatencyCount());
            dto.setClientExceptionCount(data.getClientExceptionCount());
            dto.setClientCmdCount(data.getClientCmdCount());
            dto.setClientAvgCmdCost(data.getClientAvgCmdCost());
            dto.setClientConnExpCount(data.getClientConnExpCount());
            dto.setClientAvgConnExpCost(data.getClientAvgConnExpCost());
            dto.setClientCmdExpCount(data.getClientCmdExpCount());
            dto.setClientAvgCmdExpCost(data.getClientAvgCmdExpCost());
            dto.setMaxMinuteClientCount(data.getMaxMinuteClientCount());
            dto.setAvgMinuteClientCount(data.getAvgMinuteClientCount());
            dto.setMaxMinuteCommandCount(data.getMaxMinuteCommandCount());
            dto.setAvgMinuteCommandCount(data.getAvgMinuteCommandCount());
            dto.setAvgHitRatio(data.getAvgHitRatio());
            dto.setMinMinuteHitRatio(data.getMinMinuteHitRatio());
            dto.setMaxMinuteHitRatio(data.getMaxMinuteHitRatio());
            dto.setAvgUsedMemory(data.getAvgUsedMemory());
            dto.setMaxUsedMemory(data.getMaxUsedMemory());
            dto.setExpiredKeysCount(data.getExpiredKeysCount());
            dto.setEvictedKeysCount(data.getEvictedKeysCount());
            dto.setAvgObjectSize(data.getAvgObjectSize());
            dto.setMaxObjectSize(data.getMaxObjectSize());
            dto.setAvgMinuteNetInputByte(data.getAvgMinuteNetInputByte());
            dto.setMaxMinuteNetInputByte(data.getMaxMinuteNetInputByte());
            dto.setAvgMinuteNetOutputByte(data.getAvgMinuteNetOutputByte());
            dto.setMaxMinuteNetOutputByte(data.getMaxMinuteNetOutputByte());
        }
        return dto;
    }

    public List<String> getCommandNameList(long appId, String startDate, String endDate) throws ParseException {
        appId = resolveRouteAppId(appId);
        TimeBetween range = resolveTimeRange(startDate, endDate);
        List<AppCommandStats> allCommands = appStatsCenter.getTopLimitAppCommandStatsList(
                appId, range.getStartTime(), range.getEndTime(), 100);
        List<String> names = new ArrayList<>();
        if (allCommands != null) {
            for (AppCommandStats stat : allCommands) {
                if (StringUtils.isNotBlank(stat.getCommandName())) {
                    names.add(stat.getCommandName());
                }
            }
        }
        return names;
    }

    public Map<String, List<ChartPointDto>> getCommandChartsBatch(
            long appId, String commands, String startDate, String endDate) throws ParseException {
        appId = resolveRouteAppId(appId);
        Map<String, List<ChartPointDto>> result = new LinkedHashMap<>();
        if (StringUtils.isBlank(commands)) {
            return result;
        }
        for (String part : commands.split(",")) {
            String cmd = part.trim();
            if (StringUtils.isNotBlank(cmd)) {
                result.put(cmd, getCommandChart(appId, cmd, startDate, endDate));
            }
        }
        return result;
    }

    public String executeAppCommand(long appId, String command, AppUser user) {
        appId = resolveRouteAppId(appId);
        return appStatsCenter.executeCommand(appId, command, user != null ? user.getName() : null);
    }

    public AppMachineTopologyDto getMachineTopology(long appId) {
        appId = resolveRouteAppId(appId);
        try {
            appService.syncAppTopology(appId);
        } catch (Exception ignored) {
            // same as JSP page load
        }
        AppDesc appDesc = appService.getByAppId(appId);
        List<InstanceInfo> instanceList = appService.getAppInstanceInfo(appId);
        resolveTopologyMasterLinks(instanceList);
        assignTopologyGroups(instanceList);

        Map<String, List<InstanceInfo>> machineMap = new LinkedHashMap<>();
        int maxGroupId = 0;
        if (instanceList != null) {
            for (InstanceInfo instance : instanceList) {
                String ip = instance.getIp();
                machineMap.computeIfAbsent(ip, key -> new ArrayList<>()).add(instance);
                if (instance.getGroupId() > maxGroupId) {
                    maxGroupId = instance.getGroupId();
                }
            }
        }

        AppMachineTopologyDto dto = new AppMachineTopologyDto();
        if (appDesc != null) {
            dto.setAppType(appDesc.getType());
            dto.setSentinelApp(appDesc.getType() == 5);
            dto.setGroupLabel(appDesc.getType() == 2 ? "分片" : "主从组");
        }
        dto.setGroupCount(maxGroupId);
        dto.getWarnings().addAll(collectTopologyWarnings(instanceList));

        for (Map.Entry<String, List<InstanceInfo>> entry : machineMap.entrySet()) {
            AppMachineTopologyDto.MachineRowDto row = new AppMachineTopologyDto.MachineRowDto();
            row.setIp(entry.getKey());
            for (InstanceInfo inst : entry.getValue()) {
                AppMachineTopologyDto.TopologyNodeDto node = new AppMachineTopologyDto.TopologyNodeDto();
                node.setId(inst.getId());
                node.setIp(inst.getIp());
                node.setPort(inst.getPort());
                node.setHostPort(inst.getHostPort());
                node.setGroupId(inst.getGroupId());
                node.setRoleDesc(inst.getRoleDesc());
                node.setInstanceType(inst.getType());
                node.setStatus(inst.getStatus());
                node.setOffline(inst.isOffline());
                row.getNodes().add(node);
            }
            dto.getMachines().add(row);
        }
        return dto;
    }

    /** 慢查询清理允许的最大保留天数，避免误填一个极大值导致「点了没反应」 */
    private static final int SLOWLOG_KEEP_DAYS_MAX = 3650;

    /**
     * 按保留天数清理平台侧的慢查询记录。
     *
     * <p>只删 instance_slow_log 里的行，Redis 自身的 SLOWLOG 缓冲区不受影响——
     * 那份数据在实例上，需要 SLOWLOG RESET 才会清空，本操作刻意不去碰它。</p>
     *
     * @param keepDays 保留最近多少天；0 表示清空该集群全部慢查询记录
     * @return 实际删除的行数
     */
    public int cleanSlowLogs(long appId, int keepDays, AppUser operator) {
        appId = resolveRouteAppId(appId);
        requireAdmin(operator);
        if (keepDays < 0 || keepDays > SLOWLOG_KEEP_DAYS_MAX) {
            throw new BizException("保留天数需在 0 到 " + SLOWLOG_KEEP_DAYS_MAX + " 之间");
        }
        AppDesc appDesc = appService.getByAppId(appId);
        if (appDesc == null) {
            throw new BizException("集群不存在");
        }
        // keepDays=0 时 before 就是当前时刻，等于清空历史记录
        Date before = new Date(System.currentTimeMillis() - keepDays * 24L * 60L * 60L * 1000L);
        int deleted = instanceSlowLogDao.deleteByAppIdBefore(appId, before);
        logger.info("clean slowlog appId={} keepDays={} before={} deleted={} operator={}",
                appId, keepDays, before, deleted, operator == null ? "-" : operator.getName());
        return deleted;
    }

    /** 清理前的预估条数，让用户知道这一下会删掉多少 */
    public int countCleanableSlowLogs(long appId, int keepDays) {
        appId = resolveRouteAppId(appId);
        if (keepDays < 0 || keepDays > SLOWLOG_KEEP_DAYS_MAX) {
            throw new BizException("保留天数需在 0 到 " + SLOWLOG_KEEP_DAYS_MAX + " 之间");
        }
        Date before = new Date(System.currentTimeMillis() - keepDays * 24L * 60L * 60L * 1000L);
        return instanceSlowLogDao.countByAppIdBefore(appId, before);
    }

    public AppLatencyDto getLatency(long appId, String searchDateParam, String startDateParam,
            String endDateParam, long minCostMillis) throws ParseException {
        appId = resolveRouteAppId(appId);
        TimeBetween timeBetween = StringUtils.isNotBlank(startDateParam) && StringUtils.isNotBlank(endDateParam)
                ? resolveTimeRange(startDateParam, endDateParam)
                : DateUtil.fillWithDateFormat(searchDateParam);
        Date startDate = timeBetween.getStartDate();
        Date endDate = timeBetween.getEndDate();
        long minCostMicros = Math.max(0L, minCostMillis) * 1000L;

        List<InstanceLatencyHistory> latencyHistoryList =
                instanceLatencyHistoryDao.getAppLatencyHistoryByRange(appId, startDate, endDate);
        List<InstanceSlowLog> slowLogList = appStatsCenter.getInstanceSlowLogByAppId(
                appId, startDate, endDate);
        if (CollectionUtils.isNotEmpty(slowLogList) && minCostMicros > 0) {
            slowLogList = slowLogList.stream()
                    .filter(log -> log.getCostTime() >= minCostMicros)
                    .collect(java.util.stream.Collectors.toList());
        }

        Map<String, Long> latencyByInstance = new LinkedHashMap<>();
        Map<String, Map<Long, Long>> latencyStats = new LinkedHashMap<>();
        if (CollectionUtils.isNotEmpty(latencyHistoryList)) {
            for (InstanceLatencyHistory history : latencyHistoryList) {
                String hostPort = history.getIp() + ":" + history.getPort();
                latencyByInstance.merge(hostPort, 1L, Long::sum);
                long timestamp = history.getExecuteDate().getTime();
                latencyStats.computeIfAbsent(history.getEvent(), key -> new TreeMap<>())
                        .merge(timestamp, 1L, Long::sum);
            }
        }
        Map<String, Long> slowLogCountMap = new LinkedHashMap<>();
        if (CollectionUtils.isNotEmpty(slowLogList)) {
            for (InstanceSlowLog log : slowLogList) {
                slowLogCountMap.merge(log.getIp() + ":" + log.getPort(), 1L, Long::sum);
            }
        }

        AppLatencyDto dto = new AppLatencyDto();
        dto.setAppId(appId);
        dto.setSearchDate(new SimpleDateFormat("yyyy-MM-dd").format(startDate));

        Set<String> instanceSet = new LinkedHashSet<>();
        if (latencyByInstance != null) {
            instanceSet.addAll(latencyByInstance.keySet());
        }
        if (slowLogCountMap != null) {
            instanceSet.addAll(slowLogCountMap.keySet());
        }
        List<InstanceInfo> onlineInstances = appService.getAppOnlineInstanceInfo(appId);
        if (CollectionUtils.isNotEmpty(onlineInstances)) {
            for (InstanceInfo instanceInfo : onlineInstances) {
                if (TypeUtil.isRedisSentinel(instanceInfo.getType())) {
                    continue;
                }
                String hostPort = instanceInfo.getIp() + ":" + instanceInfo.getPort();
                instanceSet.add(hostPort);
                dto.getInstanceIdByHostPort().put(hostPort, (long) instanceInfo.getId());
            }
        }

        dto.setLatencyDataEmpty(CollectionUtils.isEmpty(latencyHistoryList) && CollectionUtils.isEmpty(slowLogList));
        dto.setInstances(new ArrayList<>(instanceSet));

        if (!latencyStats.isEmpty()) {
            for (Map.Entry<String, Map<Long, Long>> entry : latencyStats.entrySet()) {
                LatencyChartSeriesDto series = new LatencyChartSeriesDto();
                series.setEvent(entry.getKey());
                for (Map.Entry<Long, Long> pointEntry : entry.getValue().entrySet()) {
                    LatencyChartPointDto point = new LatencyChartPointDto();
                    point.setTimestamp(pointEntry.getKey());
                    point.setCount(pointEntry.getValue());
                    series.getPoints().add(point);
                }
                dto.getChartSeries().add(series);
            }
        }

        SimpleDateFormat dateTimeFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        if (CollectionUtils.isNotEmpty(latencyHistoryList)) {
            for (InstanceLatencyHistory history : latencyHistoryList) {
                LatencyEventDetailDto detail = new LatencyEventDetailDto();
                detail.setId(history.getId());
                detail.setInstanceId(history.getInstanceId());
                detail.setHostPort(history.getIp() + ":" + history.getPort());
                detail.setEvent(history.getEvent());
                detail.setExecuteTime(dateTimeFormat.format(history.getExecuteDate()));
                detail.setExecutionCost(history.getExecutionCost());
                dto.getLatencyEvents().add(detail);
            }
        }

        if (latencyByInstance != null) {
            for (Map.Entry<String, Long> entry : latencyByInstance.entrySet()) {
                LatencyInstanceCountDto item = new LatencyInstanceCountDto();
                item.setHostPort(entry.getKey());
                item.setCount(entry.getValue() != null ? entry.getValue() : 0L);
                dto.getInstanceLatencies().add(item);
            }
        }
        if (slowLogCountMap != null) {
            for (Map.Entry<String, Long> entry : slowLogCountMap.entrySet()) {
                LatencyInstanceCountDto item = new LatencyInstanceCountDto();
                item.setHostPort(entry.getKey());
                item.setCount(entry.getValue() != null ? entry.getValue() : 0L);
                dto.getInstanceSlowLogCounts().add(item);
            }
        }
        if (slowLogList != null) {
            for (InstanceSlowLog log : slowLogList) {
                AppSlowLogItemDto item = new AppSlowLogItemDto();
                item.setId(log.getId());
                item.setSlowLogId(log.getSlowLogId());
                item.setInstanceId(log.getInstanceId());
                item.setIp(log.getIp());
                item.setPort(log.getPort());
                item.setHostPort(log.getIp() + ":" + log.getPort());
                item.setCostTime(log.getCostTime());
                item.setCommand(log.getCommand());
                item.setClientIp(StringUtils.isBlank(log.getClientIp()) ? "-" : log.getClientIp());
                if (log.getExecuteTime() != null) {
                    item.setExecuteTime(dateTimeFormat.format(log.getExecuteTime()));
                }
                dto.getSlowLogs().add(item);
            }
        }
        for (AppSlowLogItemDto item : dto.getSlowLogs()) {
            dto.getGroupedSlowLogs()
                    .computeIfAbsent(item.getHostPort(), key -> new ArrayList<>())
                    .add(item);
        }
        return dto;
    }

    private TimeBetween resolveTimeRange(String startDateParam, String endDateParam) throws ParseException {
        Date startDate;
        Date endDate;
        if (StringUtils.isBlank(startDateParam) || StringUtils.isBlank(endDateParam)) {
            endDate = new Date();
            startDate = DateUtils.addHours(endDate, -6);
        } else {
            startDate = DateUtil.parseRangeDateTime(startDateParam);
            endDate = DateUtil.parseRangeDateTime(endDateParam);
            if (endDateParam.trim().length() <= 10) {
                endDate = DateUtils.addDays(endDate, 1);
            }
        }
        // 页面时间来自浏览器，部署主机校时或存在少量时钟偏差时，短窗口可能整体落在服务端未来。
        // 将窗口整体回拨到服务端当前时间，避免查询未来分钟而返回空数据。
        Date now = new Date();
        if (endDate.after(now)) {
            long duration = Math.max(60_000L, endDate.getTime() - startDate.getTime());
            endDate = now;
            startDate = new Date(now.getTime() - duration);
        }
        if (endDate.getTime() - startDate.getTime() > APP_STAT_MAX_RANGE_MS) {
            startDate = new Date(endDate.getTime() - APP_STAT_MAX_RANGE_MS);
        }
        TimeBetween between = new TimeBetween();
        between.setStartDate(startDate);
        between.setEndDate(endDate);
        between.setStartTime(NumberUtils.toLong(DateUtil.formatYYYYMMddHHMM(startDate)));
        between.setEndTime(NumberUtils.toLong(DateUtil.formatYYYYMMddHHMM(endDate)));
        return between;
    }

    private ChartPointDto toChartPoint(SimpleChartData chart) {
        ChartPointDto dto = new ChartPointDto();
        dto.setX(chart.getX());
        dto.setY(chart.getY());
        dto.setDate(chart.getDate());
        dto.setCommandName(chart.getCommandName());
        return dto;
    }

    private ChartPointDto toChartPoint(HighchartPoint point) {
        ChartPointDto dto = new ChartPointDto();
        dto.setX(point.getX());
        dto.setY(point.getY());
        dto.setDate(point.getDate());
        return dto;
    }

    private ChartPointDto toChartPoint(HighchartDoublePoint point) {
        ChartPointDto dto = new ChartPointDto();
        dto.setX(point.getX());
        if (point.getY() != null) {
            dto.setY(point.getY().longValue());
            dto.setYDouble(point.getY());
        }
        dto.setDate(point.getDate());
        return dto;
    }

    private ChartPointDto buildOpsChartPoint(String statName, long collectTime, long count) throws ParseException {
        long displayCount = count;
        if ("latest_fork_usec".equals(statName) && count > 0) {
            displayCount = count / 1000;
        } else if ("aof_current_size".equals(statName) || "aof_base_size".equals(statName)) {
            displayCount = count / 1024 / 1024;
        } else if ("master_repl_offset".equals(statName) && count > 0) {
            displayCount = count / 1024 / 1024;
        }
        Date collectDate = DateUtil.parseYYYYMMddHHMM(String.valueOf(collectTime));
        String date;
        try {
            date = DateUtil.formatDate(collectDate, "yyyy-MM-dd HH:mm");
        } catch (Exception e) {
            date = DateUtil.formatDate(collectDate, "yyyy-MM-dd HH");
        }
        ChartPointDto dto = new ChartPointDto();
        dto.setX(collectDate.getTime());
        dto.setY(displayCount);
        dto.setDate(date);
        return dto;
    }

    private AppTopologyInstanceDto toTopologyItem(InstanceInfo inst, InstanceStats stats) {
        AppTopologyInstanceDto dto = new AppTopologyInstanceDto();
        dto.setId(inst.getId());
        dto.setIp(inst.getIp());
        dto.setPort(inst.getPort());
        dto.setHostPort(inst.getHostPort());
        dto.setHostId(inst.getHostId());
        dto.setStatus(inst.getStatus());
        dto.setStatusDesc(inst.getStatusDesc());
        dto.setRoleDesc(inst.getRoleDesc());
        dto.setMasterInstanceId(inst.getMasterInstanceId());
        dto.setInstanceType(inst.getType());
        dto.setUpdateTimeDesc(inst.getUpdateTimeDesc());
        dto.setExternal(inst.getHostId() == 0);
        dto.setMasterStar(!TypeUtil.isRedisSentinel(inst.getType()) && inst.getMasterInstanceId() == 0
                && inst.getStatus() != InstanceStatusEnum.OFFLINE_STATUS.getStatus());
        if (inst.getModules() != null) {
            for (redis.clients.jedis.Module module : inst.getModules()) {
                if (module != null) {
                    dto.getModules().add(module.getName() + "-" + module.getVersion());
                }
            }
        }
        if (stats != null && !TypeUtil.isRedisSentinel(inst.getType())) {
            dto.setUsedMemory(stats.getUsedMemory());
            dto.setMaxMemory(stats.getMaxMemory());
            dto.setEffectiveMaxMemory(stats.getEffectiveMaxMemory());
            dto.setMemUsePercent(stats.getMemUsePercent());
            dto.setCurrItems(stats.getCurrItems());
            dto.setCurrConnections(stats.getCurrConnections());
            dto.setHitPercent(stats.getHitPercent());
            dto.setMemFragmentationRatio(stats.getMemFragmentationRatio());
        }
        return dto;
    }

    private boolean shouldShowInTopologyList(InstanceInfo inst) {
        int type = inst.getType();
        if (!TypeUtil.isRedisType(type)) {
            return false;
        }
        return inst.getStatus() == InstanceStatusEnum.ERROR_STATUS.getStatus()
                || inst.getStatus() == InstanceStatusEnum.GOOD_STATUS.getStatus();
    }

    private AppDetailPanelDto.AppAlertMetricDto buildAlertMetric(int id, String key, String threshold, String cycle) {
        AppDetailPanelDto.AppAlertMetricDto metric = new AppDetailPanelDto.AppAlertMetricDto();
        metric.setId(id);
        metric.setAlertKey(key);
        metric.setThreshold(threshold);
        metric.setCycle(cycle);
        return metric;
    }

    private String resolveImportantLevel(int level) {
        for (AppDescEnum.AppImportantLevel item : AppDescEnum.AppImportantLevel.values()) {
            if (item.getValue() == level) {
                return item.getInfo();
            }
        }
        return "-";
    }

    private String formatAlertUsers(List<AppUser> users) {
        if (users == null || users.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < users.size(); i++) {
            AppUser user = users.get(i);
            if (i > 0) {
                sb.append(",");
            }
            sb.append(user.getChName()).append("(").append(user.getName()).append(")");
        }
        return sb.toString();
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
        if (CollectionUtils.isEmpty(instanceList)) {
            return;
        }
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
            if ("master".equals(instance.getRoleDesc()) && !idToGroup.containsKey(instance.getId())) {
                idToGroup.put(instance.getId(), nextGroup++);
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
        if (instanceList != null) {
            for (InstanceInfo instance : instanceList) {
                if (instance.isOffline() || instance.getGroupId() <= 0 || TypeUtil.isRedisSentinel(instance.getType())) {
                    continue;
                }
                groupMap.computeIfAbsent(instance.getGroupId(), key -> new ArrayList<>()).add(instance);
            }
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
}
