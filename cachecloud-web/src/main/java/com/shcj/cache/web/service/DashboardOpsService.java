package com.shcj.cache.web.service;

import com.shcj.cache.dao.AppAlertRecordDao;
import com.shcj.cache.dao.AppDao;
import com.shcj.cache.dao.InstanceRiskMetricDao;
import com.shcj.cache.dao.InstanceSlowLogDao;
import com.shcj.cache.entity.AppAlertRecord;
import com.shcj.cache.entity.AppDesc;
import com.shcj.cache.constant.InstanceStatusEnum;
import com.shcj.cache.entity.InstanceInfo;
import com.shcj.cache.entity.InstanceRiskMetric;
import com.shcj.cache.util.ConstUtils;
import com.shcj.cache.web.controller.api.dto.DashboardOpsDto;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 运维主面板的数据聚合。
 *
 * <p>两条数据线：</p>
 * <ul>
 *   <li>告警态 —— 取自 app_alert_record，与「报警配置」同源，阈值只有一套；</li>
 *   <li>指标榜 —— 取自 instance_risk_metric_minute 分钟窄表，不依赖告警开关。</li>
 * </ul>
 */
@Service("dashboardOpsService")
public class DashboardOpsService {

    private static final Logger logger = LoggerFactory.getLogger(DashboardOpsService.class);

    /** 排行榜与哨兵计数的统计窗口 */
    private static final int WINDOW_MINUTES = 60;

    /** 趋势图窗口：比排行榜长，才看得出内存爬升的走势 */
    private static final int TREND_MINUTES = 180;

    /** 趋势图采样粒度（分钟）：3 小时原始点有 180 个，画出来过密，按此聚合 */
    private static final int TREND_BUCKET_MINUTES = 3;

    /** CPU 使用率的统计窗口：比其它榜短，取值更贴近当下负载 */
    private static final int CPU_WINDOW_MINUTES = 10;

    /** 窄表 role：1=master，2=slave */
    private static final int ROLE_MASTER = 1;

    /** 最近这么久内还有新记录，就认为告警仍在持续；留 5 分钟是为了容忍一两轮采集抖动 */
    private static final long ACTIVE_WINDOW_MS = 5 * 60 * 1000L;

    /** 告警归并的回溯范围：再往前的记录对「当前态」没有意义 */
    private static final int ALERT_LOOKBACK_HOURS = 6;

    /** 单次最多归并的原始记录数，防止历史噪声把内存打满 */
    private static final int ALERT_SCAN_LIMIT = 5000;

    private static final int TOP_N = 5;

    /** 阈值类告警：`实时ops(instantaneous_ops_per_sec) 当前 18，大于预设值 0` */
    private static final Pattern METRIC_PATTERN = Pattern.compile("^(.+?)\\(([a-zA-Z_][\\w_]*)\\)");
    /** 状态变更类：`...-由运行中变为异常, 集群:4-xxx` */
    private static final Pattern STATE_PATTERN = Pattern.compile("由(.+?)(?:变为|恢复为)(.+?)[,，]");

    @Autowired
    private AppAlertRecordDao appAlertRecordDao;

    @Autowired
    private AppDao appDao;

    @Autowired
    private InstanceRiskMetricDao instanceRiskMetricDao;

    @Autowired
    private InstanceSlowLogDao instanceSlowLogDao;

    @Autowired
    private com.shcj.cache.dao.InstanceDao instanceDao;

    @Autowired
    private com.shcj.cache.dao.OperationAuditDao operationAuditDao;

    @Autowired
    private OperationAuditService operationAuditService;

    public DashboardOpsDto build() {
        DashboardOpsDto dto = new DashboardOpsDto();
        Date now = new Date();
        dto.setDataTime(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(now));
        dto.setWindowMinutes(WINDOW_MINUTES);

        Map<Long, AppDesc> appMap = loadApps();
        List<DashboardOpsDto.ActiveAlertDto> alerts = mergeAlerts(now, appMap);
        dto.setActiveAlerts(alerts);
        dto.setPosture(buildPosture(alerts, appMap));

        long since = windowStart(now);
        List<InstanceRiskMetric> latest = safeLatest(since);
        List<InstanceRiskMetric> peak = safePeak(since);
        dto.setSentinels(buildSentinels(peak, appMap));
        dto.setBoards(buildBoards(latest, peak, appMap, now, since));

        List<InstanceInfo> instances = safeInstances();
        dto.setKpi(buildKpi(latest, peak, instances, appMap, now));
        dto.setClusterHealth(buildClusterHealth(latest, instances, appMap, alerts));
        dto.setTrend(buildTrend(windowStart(now, TREND_MINUTES), appMap));
        dto.setResources(buildResources(latest, peak, since, appMap));
        dto.setSlowCommands(buildSlowCommands(now));
        dto.setRecentOps(buildRecentOps(appMap));
        return dto;
    }

    // ------------------------------------------------------------------ 告警

    /**
     * 把事件流归并成当前态。
     *
     * <p>app_alert_record 没有恢复态，同一个问题每分钟每节点各产生一条，
     * 一小时能有几百条。这里按 (集群, 节点, 指标) 收敛成一行，用
     * 「最近一条距今是否超过 ACTIVE_WINDOW_MS」推断是否仍在持续。</p>
     */
    private List<DashboardOpsDto.ActiveAlertDto> mergeAlerts(Date now, Map<Long, AppDesc> appMap) {
        Date since = new Date(now.getTime() - ALERT_LOOKBACK_HOURS * 3600_000L);
        List<AppAlertRecord> records;
        try {
            records = appAlertRecordDao.search(null, null, null, null, since, now, 0, ALERT_SCAN_LIMIT);
        } catch (Exception e) {
            logger.error("load alert records for dashboard failed: {}", e.getMessage(), e);
            return new ArrayList<>();
        }
        if (records == null || records.isEmpty()) {
            return new ArrayList<>();
        }

        Map<String, DashboardOpsDto.ActiveAlertDto> merged = new LinkedHashMap<>();
        SimpleDateFormat fmt = new SimpleDateFormat("MM-dd HH:mm:ss");
        Map<String, Date> firstSeen = new LinkedHashMap<>();
        Map<String, Date> lastSeen = new LinkedHashMap<>();

        for (AppAlertRecord record : records) {
            if (record == null || record.getCreateTime() == null) {
                continue;
            }
            String metric = resolveMetric(record.getContent());
            String hostPort = resolveHostPort(record);
            long appId = record.getAppId() == null ? 0L : record.getAppId();
            String key = appId + "|" + hostPort + "|" + metric;

            DashboardOpsDto.ActiveAlertDto row = merged.get(key);
            if (row == null) {
                row = new DashboardOpsDto.ActiveAlertDto();
                row.setAppId(appId);
                AppDesc app = appMap.get(appId);
                row.setAppName(app != null ? app.getName() : (appId > 0 ? "集群 " + appId : "-"));
                row.setInstanceId(record.getInstanceId() == null ? 0L : record.getInstanceId());
                row.setHostPort(hostPort);
                row.setMetric(metric);
                row.setImportantLevel(record.getImportantLevel());
                row.setImportantLevelDesc(levelDesc(record.getImportantLevel()));
                row.setCount(0);
                // 建组时就把原文填上：search 结果是倒序的，首条即最新，
                // 后续记录都更旧，下面的 after() 永远不成立——原先只在 after() 分支里赋值，
                // 导致「最近一条告警原文」始终为空。
                row.setLatestContent(StringUtils.abbreviate(
                        StringUtils.defaultString(record.getContent()), 300));
                merged.put(key, row);
                firstSeen.put(key, record.getCreateTime());
                lastSeen.put(key, record.getCreateTime());
            }
            row.setCount(row.getCount() + 1);
            // 同一归并组内取最高严重度，避免被一条低级别记录稀释
            if (record.getImportantLevel() > row.getImportantLevel()) {
                row.setImportantLevel(record.getImportantLevel());
                row.setImportantLevelDesc(levelDesc(record.getImportantLevel()));
            }
            if (record.getCreateTime().before(firstSeen.get(key))) {
                firstSeen.put(key, record.getCreateTime());
            }
            if (record.getCreateTime().after(lastSeen.get(key))) {
                lastSeen.put(key, record.getCreateTime());
                row.setLatestContent(StringUtils.abbreviate(
                        StringUtils.defaultString(record.getContent()), 300));
            }
        }

        List<DashboardOpsDto.ActiveAlertDto> result = new ArrayList<>(merged.values());
        for (Map.Entry<String, DashboardOpsDto.ActiveAlertDto> entry : merged.entrySet()) {
            Date first = firstSeen.get(entry.getKey());
            Date last = lastSeen.get(entry.getKey());
            DashboardOpsDto.ActiveAlertDto row = entry.getValue();
            row.setFirstTime(fmt.format(first));
            row.setLastTime(fmt.format(last));
            row.setActive(now.getTime() - last.getTime() <= ACTIVE_WINDOW_MS);
            long spanMs = row.isActive() ? now.getTime() - first.getTime() : last.getTime() - first.getTime();
            row.setDuration(humanizeDuration(spanMs));
        }
        // 持续中的排前面，其次按严重度，再按持续时长
        result.sort(Comparator
                .comparing(DashboardOpsDto.ActiveAlertDto::isActive).reversed()
                .thenComparing(Comparator.comparingInt(DashboardOpsDto.ActiveAlertDto::getImportantLevel).reversed())
                .thenComparing(Comparator.comparingInt(DashboardOpsDto.ActiveAlertDto::getCount).reversed()));
        return result;
    }

    /**
     * 从告警内容里解析出指标名，作为归并键的一部分。
     *
     * <p>三种已知形态各自处理；解析不出来时回落到"其它告警"而不是把整段内容当键，
     * 否则含变动数值的内容会导致每条记录自成一组，归并失效。</p>
     */
    // 包级可见：归并键完全依赖这个解析器，必须单测覆盖
    String resolveMetric(String content) {
        String text = StringUtils.trimToEmpty(content);
        if (text.isEmpty()) {
            return "其它告警";
        }
        Matcher metric = METRIC_PATTERN.matcher(text);
        if (metric.find()) {
            return metric.group(1).trim();
        }
        Matcher state = STATE_PATTERN.matcher(text);
        if (state.find()) {
            return "节点状态变更";
        }
        if (text.contains("节点状态异常")) {
            return "节点状态异常";
        }
        return "其它告警";
    }

    private String resolveHostPort(AppAlertRecord record) {
        if (StringUtils.isNotBlank(record.getIp())) {
            return record.getIp() + ":" + (record.getPort() == null ? 0 : record.getPort());
        }
        return "-";
    }

    private DashboardOpsDto.PostureDto buildPosture(List<DashboardOpsDto.ActiveAlertDto> alerts,
                                                    Map<Long, AppDesc> appMap) {
        DashboardOpsDto.PostureDto posture = new DashboardOpsDto.PostureDto();
        for (DashboardOpsDto.ActiveAlertDto alert : alerts) {
            if (!alert.isActive()) {
                continue;
            }
            if (alert.getImportantLevel() >= 2) {
                posture.setSevereCount(posture.getSevereCount() + 1);
            } else {
                posture.setWarningCount(posture.getWarningCount() + 1);
            }
        }
        for (AppDesc app : appMap.values()) {
            if (app.getIsAccessMonitor() != 1) {
                DashboardOpsDto.UnmonitoredAppDto item = new DashboardOpsDto.UnmonitoredAppDto();
                item.setAppId(app.getAppId());
                item.setAppName(app.getName());
                posture.getUnmonitoredApps().add(item);
            }
        }
        posture.setNormalCount(Math.max(0,
                appMap.size() - posture.getUnmonitoredApps().size()));
        return posture;
    }

    // ------------------------------------------------------------------ 指标

    private List<DashboardOpsDto.SentinelCounterDto> buildSentinels(List<InstanceRiskMetric> peak,
                                                                    Map<Long, AppDesc> appMap) {
        List<DashboardOpsDto.SentinelCounterDto> list = new ArrayList<>();
        list.add(sentinel(peak, "rejectedConnections", "拒绝连接",
                m -> m.getRejectedConnections()));
        list.add(sentinel(peak, "evictedKeys", "驱逐键", m -> m.getEvictedKeys()));
        list.add(sentinel(peak, "aofDelayedFsync", "AOF 阻塞", m -> m.getAofDelayedFsync()));
        return list;
    }

    private interface LongMetric {
        long get(InstanceRiskMetric metric);
    }

    private DashboardOpsDto.SentinelCounterDto sentinel(List<InstanceRiskMetric> metrics, String key,
                                                        String label, LongMetric getter) {
        DashboardOpsDto.SentinelCounterDto dto = new DashboardOpsDto.SentinelCounterDto();
        dto.setKey(key);
        dto.setLabel(label);
        long total = 0;
        for (InstanceRiskMetric metric : metrics) {
            long v;
            try {
                v = getter.get(metric);
            } catch (Exception e) {
                continue;
            }
            if (v > 0) {
                total += v;
                if (dto.getHotNodes().size() < 5) {
                    dto.getHotNodes().add(metric.getIp() + ":" + metric.getPort());
                }
            }
        }
        dto.setValue(total);
        return dto;
    }

    private List<DashboardOpsDto.TopBoardDto> buildBoards(List<InstanceRiskMetric> latest,
                                                          List<InstanceRiskMetric> peak,
                                                          Map<Long, AppDesc> appMap,
                                                          Date now, long since) {
        List<DashboardOpsDto.TopBoardDto> boards = new ArrayList<>();

        DashboardOpsDto.TopBoardDto memBoard = board("memUsedRatio", "内存使用率", "%", 90d,
                masterOnly(latest), appMap, metric -> {
                    long max = metric.getMaxMemory() > 0 ? metric.getMaxMemory() : metric.getTotalSystemMemory();
                    if (max <= 0) return null;
                    return metric.getUsedMemory() * 100d / max;
                }, v -> String.format("%.1f%%", v));
        memBoard.setHint("only for master");
        memBoard.setLinkType("instance");
        boards.add(memBoard);

        boards.add(cpuBoard(now, appMap));

        DashboardOpsDto.TopBoardDto connBoard = board("connectedClients", "客户端连接数", "", null,
                peak, appMap, metric -> (double) metric.getConnectedClients(),
                v -> String.valueOf((long) v));
        connBoard.setLinkType("instanceClients");
        boards.add(connBoard);

        boards.add(slowLogBoard(appMap, now));

        boards.add(withLink(board("evictedKeys", "驱逐键数", "", 1d, peak, appMap,
                metric -> (double) metric.getEvictedKeys(),
                v -> String.valueOf((long) v)), "instance"));
        return boards;
    }

    /** 只保留 master 节点的采样 */
    private List<InstanceRiskMetric> masterOnly(List<InstanceRiskMetric> metrics) {
        List<InstanceRiskMetric> result = new ArrayList<>();
        for (InstanceRiskMetric m : metrics) {
            if (m.getRole() == ROLE_MASTER) {
                result.add(m);
            }
        }
        return result;
    }

    private DashboardOpsDto.TopBoardDto withLink(DashboardOpsDto.TopBoardDto board, String linkType) {
        board.setLinkType(linkType);
        return board;
    }

    private interface ValueExtractor {
        Double extract(InstanceRiskMetric metric);
    }

    private interface ValueFormatter {
        String format(double value);
    }

    private DashboardOpsDto.TopBoardDto board(String key, String label, String unit, Double threshold,
                                               List<InstanceRiskMetric> metrics, Map<Long, AppDesc> appMap,
                                               ValueExtractor extractor, ValueFormatter formatter) {
        DashboardOpsDto.TopBoardDto board = new DashboardOpsDto.TopBoardDto();
        board.setKey(key);
        board.setLabel(label);
        board.setUnit(unit);
        board.setThreshold(threshold);
        List<DashboardOpsDto.TopBoardRowDto> rows = new ArrayList<>();
        for (InstanceRiskMetric metric : metrics) {
            Double value;
            try {
                value = extractor.extract(metric);
            } catch (Exception e) {
                continue;
            }
            if (value == null) {
                continue;
            }
            DashboardOpsDto.TopBoardRowDto row = new DashboardOpsDto.TopBoardRowDto();
            row.setAppId(metric.getAppId());
            AppDesc app = appMap.get(metric.getAppId());
            row.setAppName(app != null ? app.getName() : "-");
            row.setInstanceId(metric.getInstanceId());
            row.setHostPort(metric.getIp() + ":" + metric.getPort());
            row.setValue(value);
            row.setDisplay(formatter.format(value));
            row.setExceeded(threshold != null && value >= threshold);
            rows.add(row);
        }
        rows.sort(Comparator.comparingDouble(DashboardOpsDto.TopBoardRowDto::getValue).reversed());
        board.setRows(rows.size() > TOP_N ? new ArrayList<>(rows.subList(0, TOP_N)) : rows);
        return board;
    }

    /**
     * CPU 使用率榜。
     *
     * <p>used_cpu_sys/user 是进程累计秒数，取单点值毫无意义，
     * 必须算「窗口内增量 / 真实时间跨度」。窗口取 10 分钟而非 60 分钟：
     * CPU 是瞬时性负载指标，一小时均值会把短时飙升摊平。</p>
     *
     * <p>Redis 主线程单线程，但后台线程存在，因此该值可能超过 100%，不做截断。</p>
     */
    private DashboardOpsDto.TopBoardDto cpuBoard(Date now, Map<Long, AppDesc> appMap) {
        DashboardOpsDto.TopBoardDto board = new DashboardOpsDto.TopBoardDto();
        board.setKey("cpuUsage");
        board.setLabel("CPU 使用率");
        board.setUnit("%");
        board.setThreshold(80d);
        board.setLinkType("instance");
        board.setHint("近 " + CPU_WINDOW_MINUTES + " 分钟均值");
        try {
            List<Map<String, Object>> rows =
                    instanceRiskMetricDao.cpuUsageSince(windowStart(now, CPU_WINDOW_MINUTES));
            if (rows == null || rows.isEmpty()) {
                return board;
            }
            List<DashboardOpsDto.TopBoardRowDto> list = new ArrayList<>();
            for (Map<String, Object> item : rows) {
                double delta = toDouble(item.get("cpuDelta"));
                double span = toDouble(item.get("spanSeconds"));
                if (span <= 0) {
                    continue;
                }
                double pct = delta * 100d / span;
                DashboardOpsDto.TopBoardRowDto row = new DashboardOpsDto.TopBoardRowDto();
                long appId = toLong(item.get("appId"));
                row.setAppId(appId);
                AppDesc app = appMap.get(appId);
                row.setAppName(app != null ? app.getName() : "-");
                row.setInstanceId(toLong(item.get("instanceId")));
                row.setHostPort(String.valueOf(item.get("ip")) + ":" + toLong(item.get("port")));
                row.setValue(pct);
                row.setDisplay(String.format("%.2f%%", pct));
                row.setExceeded(pct >= 80d);
                list.add(row);
            }
            list.sort(Comparator.comparingDouble(DashboardOpsDto.TopBoardRowDto::getValue).reversed());
            board.setRows(list.size() > TOP_N ? new ArrayList<>(list.subList(0, TOP_N)) : list);
        } catch (Exception e) {
            logger.error("build cpu board failed: {}", e.getMessage(), e);
        }
        return board;
    }

    /** 慢查询榜单独走 instance_slow_log，窄表里没有这个维度 */
    private DashboardOpsDto.TopBoardDto slowLogBoard(Map<Long, AppDesc> appMap, Date now) {
        DashboardOpsDto.TopBoardDto board = new DashboardOpsDto.TopBoardDto();
        board.setKey("slowLog");
        board.setLabel("慢查询数");
        board.setUnit("条");
        board.setThreshold(1d);
        board.setLinkType("appLatency");
        board.setHint("近 24 小时");
        try {
            // 60 分钟内只有少数节点有慢查询，凑不满 Top5；与「慢命令」榜统一用 24 小时
            Date since = new Date(now.getTime() - 24 * 3600_000L);
            List<Map<String, Object>> stats = instanceSlowLogDao.countByInstanceSince(since, TOP_N);
            if (stats != null) {
                for (Map<String, Object> item : stats) {
                    DashboardOpsDto.TopBoardRowDto row = new DashboardOpsDto.TopBoardRowDto();
                    long appId = toLong(item.get("appId"));
                    row.setAppId(appId);
                    AppDesc app = appMap.get(appId);
                    row.setAppName(app != null ? app.getName() : "-");
                    row.setInstanceId(toLong(item.get("instanceId")));
                    row.setHostPort(String.valueOf(item.get("ip")) + ":" + toLong(item.get("port")));
                    double count = toLong(item.get("cnt"));
                    row.setValue(count);
                    row.setDisplay(String.valueOf((long) count));
                    row.setExceeded(count > 0);
                    board.getRows().add(row);
                }
            }
        } catch (Exception e) {
            logger.error("build slow log board failed: {}", e.getMessage(), e);
        }
        return board;
    }

    // ------------------------------------------------------------------ KPI 与概览

    private DashboardOpsDto.KpiDto buildKpi(List<InstanceRiskMetric> latest, List<InstanceRiskMetric> peak,
                                             List<InstanceInfo> instances, Map<Long, AppDesc> appMap, Date now) {
        DashboardOpsDto.KpiDto kpi = new DashboardOpsDto.KpiDto();
        for (InstanceInfo info : instances) {
            kpi.setInstanceTotal(kpi.getInstanceTotal() + 1);
            if (info.getStatus() == InstanceStatusEnum.GOOD_STATUS.getStatus()) {
                kpi.setInstanceOnline(kpi.getInstanceOnline() + 1);
            } else {
                kpi.setInstanceOffline(kpi.getInstanceOffline() + 1);
            }
            // 哨兵不算主从，否则主从比会被它拉偏
            if (info.getType() == ConstUtils.CACHE_REDIS_SENTINEL) {
                continue;
            }
            if (info.getMasterInstanceId() > 0) {
                kpi.setSlaveCount(kpi.getSlaveCount() + 1);
            } else {
                kpi.setMasterCount(kpi.getMasterCount() + 1);
            }
        }
        kpi.setClusterTotal(appMap.size());

        long memUsed = 0;
        long memTotal = 0;
        long keys = 0;
        long expires = 0;
        for (InstanceRiskMetric m : latest) {
            memUsed += Math.max(0, m.getUsedMemory());
            memTotal += m.getMaxMemory() > 0 ? m.getMaxMemory() : Math.max(0, m.getTotalSystemMemory());
            keys += Math.max(0, m.getKeysCount());
            expires += Math.max(0, m.getExpiresCount());
        }
        kpi.setMemUsed(memUsed);
        kpi.setMemTotal(memTotal);
        kpi.setMemRatio(memTotal > 0 ? memUsed * 100d / memTotal : 0d);
        kpi.setKeyTotal(keys);
        kpi.setKeyExpires(expires);
        kpi.setKeyExpiresRatio(keys > 0 ? expires * 100d / keys : 0d);

        long qps = 0;
        for (InstanceRiskMetric m : latest) {
            qps += Math.max(0, m.getInstantaneousOps());
        }
        long qpsPeak = 0;
        for (InstanceRiskMetric m : peak) {
            qpsPeak += Math.max(0, m.getInstantaneousOps());
        }
        kpi.setQps(qps);
        kpi.setQpsPeak(Math.max(qps, qpsPeak));

        Double hitRate = windowHitRate(windowStart(now), collectTimeOf(now));
        kpi.setHitRate(hitRate);
        kpi.setHitRateDelta(hitRate == null ? null : resolveHitRateDelta(now, hitRate));
        return kpi;
    }

    /**
     * 窗口内的命中率，窗口里没有发生任何查找时返回 null。
     *
     * <p>返回 null 而不是 0：0% 会被读成「全部穿透」，那是把「这段时间没有请求」
     * 说成了一个严重结论。前端据此显示「—」。</p>
     */
    private Double windowHitRate(long since, long until) {
        List<InstanceRiskMetric> samples;
        try {
            samples = instanceRiskMetricDao.listWindowBoundarySamples(since, until);
        } catch (Exception e) {
            logger.error("load hit rate boundary samples failed: {}", e.getMessage(), e);
            return null;
        }
        return hitRateOf(samples);
    }

    /**
     * 由窗口首尾采样算出命中率，纯计算，便于测试。
     */
    static Double hitRateOf(List<InstanceRiskMetric> samples) {
        if (samples == null || samples.isEmpty()) {
            return null;
        }
        Map<Long, InstanceRiskMetric> firstByInstance = new HashMap<>();
        Map<Long, InstanceRiskMetric> lastByInstance = new HashMap<>();
        for (InstanceRiskMetric sample : samples) {
            long instanceId = sample.getInstanceId();
            InstanceRiskMetric first = firstByInstance.get(instanceId);
            if (first == null || sample.getCollectTime() < first.getCollectTime()) {
                firstByInstance.put(instanceId, sample);
            }
            InstanceRiskMetric last = lastByInstance.get(instanceId);
            if (last == null || sample.getCollectTime() > last.getCollectTime()) {
                lastByInstance.put(instanceId, sample);
            }
        }
        long hits = 0;
        long misses = 0;
        for (Map.Entry<Long, InstanceRiskMetric> entry : lastByInstance.entrySet()) {
            InstanceRiskMetric first = firstByInstance.get(entry.getKey());
            InstanceRiskMetric last = entry.getValue();
            if (first == null) {
                continue;
            }
            long hitDelta = last.getKeyspaceHits() - first.getKeyspaceHits();
            long missDelta = last.getKeyspaceMisses() - first.getKeyspaceMisses();
            // 负增量说明实例在窗口内重启过，累计计数器归了零，这段窗口的样本没法用
            if (hitDelta < 0 || missDelta < 0) {
                continue;
            }
            hits += hitDelta;
            misses += missDelta;
        }
        long lookup = hits + misses;
        return lookup > 0 ? hits * 100d / lookup : null;
    }

    /**
     * 命中率较昨日同时段的变化。
     *
     * <p>取不到昨日样本时返回 null，由前端显示为"—"。这里刻意不回落成 0：
     * 0 会被读成"与昨日持平"，那是把"没有数据"说成了一个结论。</p>
     */
    private Double resolveHitRateDelta(Date now, double todayRate) {
        try {
            Date yesterday = new Date(now.getTime() - 24 * 3600_000L);
            Double yesterdayRate = windowHitRate(windowStart(yesterday), collectTimeOf(yesterday));
            if (yesterdayRate == null) {
                return null;
            }
            return todayRate - yesterdayRate;
        } catch (Exception e) {
            logger.debug("resolve hit rate delta failed: {}", e.getMessage());
            return null;
        }
    }

    private DashboardOpsDto.ClusterHealthDto buildClusterHealth(List<InstanceRiskMetric> latest,
                                                                 List<InstanceInfo> instances,
                                                                 Map<Long, AppDesc> appMap,
                                                                 List<DashboardOpsDto.ActiveAlertDto> alerts) {
        DashboardOpsDto.ClusterHealthDto health = new DashboardOpsDto.ClusterHealthDto();
        Map<Long, long[]> memByApp = new LinkedHashMap<>();
        for (InstanceRiskMetric m : latest) {
            long[] agg = memByApp.computeIfAbsent(m.getAppId(), k -> new long[2]);
            agg[0] += Math.max(0, m.getUsedMemory());
            agg[1] += m.getMaxMemory() > 0 ? m.getMaxMemory() : Math.max(0, m.getTotalSystemMemory());
        }
        Map<Long, int[]> roleByApp = new LinkedHashMap<>();
        Map<Long, Integer> downByApp = new LinkedHashMap<>();
        for (InstanceInfo info : instances) {
            if (info.getStatus() != InstanceStatusEnum.GOOD_STATUS.getStatus()) {
                downByApp.merge(info.getAppId(), 1, Integer::sum);
            }
            if (info.getType() == ConstUtils.CACHE_REDIS_SENTINEL) {
                continue;
            }
            int[] role = roleByApp.computeIfAbsent(info.getAppId(), k -> new int[2]);
            if (info.getMasterInstanceId() > 0) {
                role[1]++;
            } else {
                role[0]++;
            }
        }
        Map<Long, Boolean> alertByApp = new LinkedHashMap<>();
        for (DashboardOpsDto.ActiveAlertDto alert : alerts) {
            if (alert.isActive()) {
                alertByApp.put(alert.getAppId(), Boolean.TRUE);
            }
        }

        for (AppDesc app : appMap.values()) {
            DashboardOpsDto.ClusterHealthRowDto row = new DashboardOpsDto.ClusterHealthRowDto();
            row.setAppId(app.getAppId());
            row.setAppName(app.getName());
            int[] role = roleByApp.getOrDefault(app.getAppId(), new int[2]);
            row.setMasterCount(role[0]);
            row.setSlaveCount(role[1]);
            long[] mem = memByApp.getOrDefault(app.getAppId(), new long[2]);
            row.setMemRatio(mem[1] > 0 ? mem[0] * 100d / mem[1] : 0d);
            row.setMemDisplay(humanBytes(mem[0]) + " / " + humanBytes(mem[1]));

            int down = downByApp.getOrDefault(app.getAppId(), 0);
            int total = role[0] + role[1];
            if (total > 0 && down >= total) {
                row.setStatus("offline");
                row.setStatusDesc("离线");
                health.setOffline(health.getOffline() + 1);
            } else if (down > 0) {
                row.setStatus("abnormal");
                row.setStatusDesc("异常");
                health.setAbnormal(health.getAbnormal() + 1);
            } else if (Boolean.TRUE.equals(alertByApp.get(app.getAppId()))) {
                row.setStatus("warning");
                row.setStatusDesc("告警");
                health.setWarning(health.getWarning() + 1);
            } else {
                row.setStatus("healthy");
                row.setStatusDesc("健康");
                health.setHealthy(health.getHealthy() + 1);
            }
            health.getRows().add(row);
        }
        return health;
    }

    /**
     * 指标趋势：内存使用率最高的 Top5 节点，按采样时间对齐成多条线。
     *
     * <p>画节点而不是集群：集群级加总会把单个节点的内存飙升平均掉，
     * 而内存打满恰恰是按节点发生的。</p>
     */
    private DashboardOpsDto.TrendDto buildTrend(long since, Map<Long, AppDesc> appMap) {
        DashboardOpsDto.TrendDto trend = new DashboardOpsDto.TrendDto();
        List<InstanceRiskMetric> all;
        try {
            all = instanceRiskMetricDao.listWindowSeries(since);
        } catch (Exception e) {
            logger.error("load trend series failed: {}", e.getMessage(), e);
            return trend;
        }
        if (all == null || all.isEmpty()) {
            return trend;
        }

        // (3 分钟桶, 节点) -> 该桶内使用率的累计值与样本数，最后取均值
        Map<Long, Map<Long, double[]>> byBucket = new java.util.TreeMap<>();
        Map<Long, String> nodeName = new LinkedHashMap<>();
        Map<Long, Long> nodeApp = new LinkedHashMap<>();
        Map<Long, Double> nodePeak = new LinkedHashMap<>();
        for (InstanceRiskMetric m : all) {
            // master 过滤已下推到 SQL（listWindowSeries 里 role = 1）。
            // 这里不再按 role 判断：该查询若漏选 role 列，字段会默认为 0，
            // 判断就会把所有行都滤掉——趋势图空白正是这么来的。
            long limit = m.getMaxMemory() > 0 ? m.getMaxMemory() : m.getTotalSystemMemory();
            if (limit <= 0) {
                // 没有内存上限就算不出使用率，跳过而不是记 0——0 会被读成"很空闲"
                continue;
            }
            double ratio = Math.max(0, m.getUsedMemory()) * 100d / limit;
            long instanceId = m.getInstanceId();
            double[] agg = byBucket
                    .computeIfAbsent(bucketOf(m.getCollectTime()), k -> new LinkedHashMap<>())
                    .computeIfAbsent(instanceId, k -> new double[2]);
            agg[0] += ratio;
            agg[1] += 1;
            nodeName.putIfAbsent(instanceId, m.getIp() + ":" + m.getPort());
            nodeApp.putIfAbsent(instanceId, m.getAppId());
            // 峰值取原始采样，不受聚合平滑影响——否则短暂尖峰会被抹掉，Top5 选错节点
            nodePeak.merge(instanceId, ratio, Math::max);
        }
        Map<Long, Map<Long, Double>> byTime = new java.util.TreeMap<>();
        for (Map.Entry<Long, Map<Long, double[]>> bucket : byBucket.entrySet()) {
            Map<Long, Double> row = new LinkedHashMap<>();
            for (Map.Entry<Long, double[]> node : bucket.getValue().entrySet()) {
                double[] agg = node.getValue();
                row.put(node.getKey(), agg[1] > 0 ? agg[0] / agg[1] : null);
            }
            byTime.put(bucket.getKey(), row);
        }
        if (byTime.isEmpty()) {
            return trend;
        }

        // 按窗口内峰值挑 Top5：一直平稳的节点没有看的必要
        List<Map.Entry<Long, Double>> ranked = new ArrayList<>(nodePeak.entrySet());
        ranked.sort(Map.Entry.<Long, Double>comparingByValue().reversed());
        List<Long> topNodes = new ArrayList<>();
        for (Map.Entry<Long, Double> entry : ranked) {
            if (topNodes.size() >= TOP_N) {
                break;
            }
            topNodes.add(entry.getKey());
        }

        List<Long> axis = new ArrayList<>(byTime.keySet());
        for (Long t : axis) {
            String text = String.valueOf(t);
            trend.getTimes().add(text.length() == 12
                    ? text.substring(8, 10) + ":" + text.substring(10, 12) : text);
        }
        for (Long instanceId : topNodes) {
            DashboardOpsDto.TrendSeriesDto series = new DashboardOpsDto.TrendSeriesDto();
            series.setAppId(nodeApp.getOrDefault(instanceId, 0L));
            AppDesc app = appMap.get(series.getAppId());
            String prefix = app != null ? app.getName() + " · " : "";
            series.setName(prefix + nodeName.getOrDefault(instanceId, String.valueOf(instanceId)));
            for (Long t : axis) {
                Double v = byTime.get(t).get(instanceId);
                // 缺采样点留 null，让前端断线而不是画一条假的直线
                series.getValues().add(v);
            }
            trend.getSeries().add(series);
        }
        return trend;
    }

    /**
     * 把 yyyyMMddHHmm 的采样时间向下取整到 3 分钟桶。
     *
     * <p>只动分钟位，不做日期运算：collect_time 本身就是这个格式，
     * 转成 Date 再转回来反而会引入时区问题。</p>
     */
    private long bucketOf(long collectTime) {
        long minute = collectTime % 100;
        long floored = minute - (minute % TREND_BUCKET_MINUTES);
        return collectTime - minute + floored;
    }

    private List<DashboardOpsDto.ResourceGaugeDto> buildResources(List<InstanceRiskMetric> latest,
                                                                   List<InstanceRiskMetric> peak,
                                                                   long since, Map<Long, AppDesc> appMap) {
        List<DashboardOpsDto.ResourceGaugeDto> list = new ArrayList<>();

        long memUsed = 0;
        long memTotal = 0;
        long clients = 0;
        long evicted = 0;
        double cpu = 0;
        long netIn = 0;
        long netOut = 0;
        for (InstanceRiskMetric m : latest) {
            memUsed += Math.max(0, m.getUsedMemory());
            memTotal += m.getMaxMemory() > 0 ? m.getMaxMemory() : Math.max(0, m.getTotalSystemMemory());
            clients += Math.max(0, m.getConnectedClients());
            cpu += Math.max(0d, m.getUsedCpuSys()) + Math.max(0d, m.getUsedCpuUser());
            netIn += Math.max(0, m.getNetInputBytes());
            netOut += Math.max(0, m.getNetOutputBytes());
        }
        for (InstanceRiskMetric m : peak) {
            evicted += Math.max(0, m.getEvictedKeys());
        }

        list.add(gauge("memory", "内存使用率",
                memTotal > 0 ? memUsed * 100d / memTotal : 0d,
                String.format("%.1f%%", memTotal > 0 ? memUsed * 100d / memTotal : 0d),
                humanBytes(memUsed) + " / " + humanBytes(memTotal)));
        // CPU 是进程累计秒数，不是瞬时占用率，这里只报累计值，不伪装成百分比
        list.add(gauge("cpu", "CPU 累计耗时", null, String.format("%.1f s", cpu), "sys + user"));
        list.add(gauge("clients", "连接数", null, String.valueOf(clients), "全部在线节点合计"));
        list.add(gauge("netIn", "网络入口", null, humanBytes(netIn), "累计"));
        list.add(gauge("netOut", "网络出口", null, humanBytes(netOut), "累计"));
        list.add(gauge("evicted", "回收键数", null, String.valueOf(evicted),
                "近 " + WINDOW_MINUTES + " 分钟"));
        return list;
    }

    private DashboardOpsDto.ResourceGaugeDto gauge(String key, String label, Double percent,
                                                    String display, String sub) {
        DashboardOpsDto.ResourceGaugeDto dto = new DashboardOpsDto.ResourceGaugeDto();
        dto.setKey(key);
        dto.setLabel(label);
        dto.setPercent(percent);
        dto.setDisplay(display);
        dto.setSub(sub);
        return dto;
    }

    private List<DashboardOpsDto.SlowCommandDto> buildSlowCommands(Date now) {
        List<DashboardOpsDto.SlowCommandDto> list = new ArrayList<>();
        try {
            Date since = new Date(now.getTime() - 24 * 3600_000L);
            List<Map<String, Object>> rows = instanceSlowLogDao.topCommandsSince(since, 10);
            if (rows == null || rows.isEmpty()) {
                return list;
            }
            double totalCost = 0;
            for (Map<String, Object> row : rows) {
                totalCost += toLong(row.get("totalCost"));
            }
            for (Map<String, Object> row : rows) {
                DashboardOpsDto.SlowCommandDto dto = new DashboardOpsDto.SlowCommandDto();
                dto.setCommand(String.valueOf(row.get("cmd")));
                dto.setCalls(toLong(row.get("calls")));
                Object avg = row.get("avgCostMs");
                dto.setAvgCostMs(avg instanceof Number ? ((Number) avg).doubleValue() : 0d);
                dto.setCostRatio(totalCost > 0 ? toLong(row.get("totalCost")) * 100d / totalCost : 0d);
                list.add(dto);
            }
        } catch (Exception e) {
            logger.error("build slow commands failed: {}", e.getMessage(), e);
        }
        return list;
    }

    private List<DashboardOpsDto.RecentOpDto> buildRecentOps(Map<Long, AppDesc> appMap) {
        List<DashboardOpsDto.RecentOpDto> list = new ArrayList<>();
        try {
            List<com.shcj.cache.entity.OperationAudit> rows =
                    operationAuditDao.search(null, null, null, null, null, null, 0, 10);
            if (rows == null) {
                return list;
            }
            SimpleDateFormat fmt = new SimpleDateFormat("MM-dd HH:mm");
            for (com.shcj.cache.entity.OperationAudit row : rows) {
                DashboardOpsDto.RecentOpDto dto = new DashboardOpsDto.RecentOpDto();
                dto.setTime(row.getCreateTime() == null ? "-" : fmt.format(row.getCreateTime()));
                dto.setUserName(StringUtils.defaultIfEmpty(StringUtils.trimToEmpty(row.getUserName()), "-"));
                dto.setHandler(StringUtils.trimToEmpty(row.getHandler()));
                dto.setAppId(row.getAppId() == null ? 0L : row.getAppId());
                dto.setObjectLabel(resolveOpObject(row, appMap));
                dto.setSuccess(row.getSuccess() == 1);
                list.add(dto);
            }
        } catch (Exception e) {
            logger.error("build recent ops failed: {}", e.getMessage(), e);
        }
        return list;
    }

    /**
     * 操作对象：优先集群名，退化到节点，都没有则留空。
     *
     * <p>与审计日志页「对象」列同口径，两处看到的应当是同一件事。</p>
     */
    private String resolveOpObject(com.shcj.cache.entity.OperationAudit row, Map<Long, AppDesc> appMap) {
        Long appId = row.getAppId();
        if (appId != null && appId > 0) {
            AppDesc app = appMap == null ? null : appMap.get(appId);
            if (app != null && StringUtils.isNotBlank(app.getName())) {
                return app.getName();
            }
            return "集群 " + appId;
        }
        // 节点与迁移任务的文案与审计日志页共用同一套口径，两处看到的应当是同一件事
        return StringUtils.defaultString(operationAuditService.resolveObjectLabel(row));
    }

    private List<InstanceInfo> safeInstances() {
        try {
            List<InstanceInfo> list = instanceDao.getAllInsts();
            if (list == null) {
                return new ArrayList<>();
            }
            List<InstanceInfo> result = new ArrayList<>();
            for (InstanceInfo info : list) {
                // 已下线/永久下线的节点不计入规模，否则删过的节点会一直挂在总数里
                if (info.getStatus() == InstanceStatusEnum.OFFLINE_STATUS.getStatus()
                        || info.getStatus() == InstanceStatusEnum.FORGET_STATUS.getStatus()) {
                    continue;
                }
                result.add(info);
            }
            return result;
        } catch (Exception e) {
            logger.error("load instances failed: {}", e.getMessage(), e);
            return new ArrayList<>();
        }
    }

    private String humanBytes(long bytes) {
        if (bytes <= 0) {
            return "0";
        }
        double v = bytes;
        String[] units = {"B", "KB", "MB", "GB", "TB"};
        int i = 0;
        while (v >= 1024 && i < units.length - 1) {
            v /= 1024;
            i++;
        }
        return String.format(v >= 100 ? "%.0f%s" : "%.1f%s", v, units[i]);
    }

    // ------------------------------------------------------------------ 工具

    private Map<Long, AppDesc> loadApps() {
        Map<Long, AppDesc> map = new LinkedHashMap<>();
        try {
            List<AppDesc> apps = appDao.listPublishedApps();
            if (apps != null) {
                for (AppDesc app : apps) {
                    map.put(app.getAppId(), app);
                }
            }
        } catch (Exception e) {
            logger.error("load apps for dashboard failed: {}", e.getMessage(), e);
        }
        return map;
    }

    private long collectTimeOf(Date time) {
        return Long.parseLong(new SimpleDateFormat("yyyyMMddHHmm").format(time));
    }

    private long windowStart(Date now) {
        return windowStart(now, WINDOW_MINUTES);
    }

    private long windowStart(Date now, int minutes) {
        Date since = new Date(now.getTime() - minutes * 60_000L);
        return Long.parseLong(new SimpleDateFormat("yyyyMMddHHmm").format(since));
    }

    private List<InstanceRiskMetric> safeLatest(long since) {
        try {
            List<InstanceRiskMetric> list = instanceRiskMetricDao.listLatestInWindow(since);
            return list == null ? new ArrayList<>() : list;
        } catch (Exception e) {
            logger.error("load latest metrics failed: {}", e.getMessage(), e);
            return new ArrayList<>();
        }
    }

    private List<InstanceRiskMetric> safePeak(long since) {
        try {
            List<InstanceRiskMetric> list = instanceRiskMetricDao.listWindowPeak(since);
            return list == null ? new ArrayList<>() : list;
        } catch (Exception e) {
            logger.error("load peak metrics failed: {}", e.getMessage(), e);
            return new ArrayList<>();
        }
    }

    private double toDouble(Object value) {
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (Exception e) {
            return 0d;
        }
    }

    private long toLong(Object value) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (Exception e) {
            return 0L;
        }
    }

    private String levelDesc(int level) {
        if (level >= 2) return "紧急";
        if (level == 1) return "重要";
        return "一般";
    }

    // 包级可见，便于单测
    String humanizeDuration(long ms) {
        if (ms < 0) ms = 0;
        long minutes = ms / 60_000L;
        if (minutes < 60) {
            return minutes + "m";
        }
        long hours = minutes / 60;
        long rest = minutes % 60;
        if (hours < 24) {
            return rest > 0 ? hours + "h" + rest + "m" : hours + "h";
        }
        return (hours / 24) + "d" + (hours % 24) + "h";
    }
}
