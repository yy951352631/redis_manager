package com.shcj.cache.risk.service;

import com.alibaba.fastjson.JSON;
import com.shcj.cache.dao.InstanceCommandLatencyDao;
import com.shcj.cache.dao.InstanceRiskMetricDao;
import com.shcj.cache.dao.InstanceSlowLogDao;
import com.shcj.cache.dao.RiskAssessReportDao;
import com.shcj.cache.dao.RiskAssessRuleDao;
import com.shcj.cache.entity.AppDesc;
import com.shcj.cache.entity.InstanceInfo;
import com.shcj.cache.entity.InstanceCommandLatency;
import com.shcj.cache.entity.InstanceRiskMetric;
import com.shcj.cache.entity.InstanceSlowLog;
import com.shcj.cache.entity.RiskAssessDimension;
import com.shcj.cache.entity.RiskAssessReport;
import com.shcj.cache.entity.RiskAssessRule;
import com.shcj.cache.redis.RedisCenter;
import com.shcj.cache.risk.model.DimensionResult;
import com.shcj.cache.risk.model.RiskAssessContext;
import com.shcj.cache.risk.model.RiskDimension;
import com.shcj.cache.risk.model.RiskDimensionEvaluator;
import com.shcj.cache.risk.model.RiskLevel;
import com.shcj.cache.util.TypeUtil;
import com.shcj.cache.web.service.AppService;
import org.apache.commons.collections.CollectionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 评估引擎：装配上下文 → 逐维度评估 → 汇总定级 → 落库。
 *
 * <p>所有数据一次性装配好再交给各维度，避免 15 个维度各自查库造成重复扫描。
 */
@Service
public class RiskAssessEngine {

    private static final Logger LOGGER = LoggerFactory.getLogger(RiskAssessEngine.class);

    private static final SimpleDateFormat COLLECT_TIME_FORMAT = new SimpleDateFormat("yyyyMMddHHmm");
    private static final SimpleDateFormat DISPLAY_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm");

    /** 达到该数量的「关注」维度时，总评升一级到风险 */
    private static final int ATTENTION_ESCALATE_COUNT = 3;

    @Autowired
    private AppService appService;

    @Autowired
    private InstanceRiskMetricDao instanceRiskMetricDao;

    @Autowired
    private InstanceCommandLatencyDao instanceCommandLatencyDao;

    @Autowired
    private InstanceSlowLogDao instanceSlowLogDao;

    @Autowired
    private RiskAssessRuleDao riskAssessRuleDao;

    @Autowired
    private RiskAssessReportDao riskAssessReportDao;

    @Autowired
    private RedisCenter redisCenter;

    @Autowired
    private RiskCommandCountLoader commandCountLoader;

    @Autowired
    private List<RiskDimensionEvaluator> evaluators;

    public RiskAssessReport assess(long appId, int windowHours, String operator) {
        long start = System.currentTimeMillis();
        AppDesc appDesc = appService.getByAppId(appId);
        if (appDesc == null) {
            throw new IllegalArgumentException("集群不存在: " + appId);
        }

        RiskAssessContext context = buildContext(appDesc, windowHours);
        List<DimensionResult> results = new ArrayList<>();
        for (RiskDimensionEvaluator evaluator : evaluators) {
            results.add(evaluateOne(evaluator, context));
        }

        RiskAssessReport report = summarize(appDesc, context, results, operator,
                System.currentTimeMillis() - start);
        persist(report, results);
        return report;
    }

    /**
     * 窗口与类型这两种「不该由维度自己关心」的前置条件在这里统一处理，
     * 到达 evaluate() 时已保证是满足的。
     */
    private DimensionResult evaluateOne(RiskDimensionEvaluator evaluator, RiskAssessContext context) {
        RiskDimension dimension = evaluator.dimension();
        if (dimension.isClusterOnly() && !context.isCluster()) {
            return DimensionResult.of(dimension, RiskLevel.NOT_APPLICABLE,
                    "该维度仅适用于 redis-cluster，当前集群类型为 " + context.getAppDesc().getTypeDesc());
        }
        if (context.getWindowHours() < dimension.getMinWindowHours()) {
            return DimensionResult.of(dimension, RiskLevel.WINDOW_TOO_SHORT,
                    "本维度需至少 " + dimension.getMinWindowHours() + " 小时窗口，当前 "
                            + context.getWindowHours() + " 小时");
        }
        try {
            DimensionResult result = evaluator.evaluate(context);
            if (result.getSampleWindow() == null) {
                result.setSampleWindow(DISPLAY_FORMAT.format(context.getWindowStart())
                        + " ~ " + DISPLAY_FORMAT.format(context.getWindowEnd()));
            }
            return result;
        } catch (Exception e) {
            LOGGER.error("evaluate dimension {} failed appId={}: {}",
                    dimension, context.getAppDesc().getAppId(), e.getMessage(), e);
            return DimensionResult.of(dimension, RiskLevel.INSUFFICIENT_DATA, "维度执行异常: " + e.getMessage());
        }
    }

    private RiskAssessContext buildContext(AppDesc appDesc, int windowHours) {
        RiskAssessContext context = new RiskAssessContext();
        context.setAppDesc(appDesc);
        context.setWindowHours(windowHours);

        Date end = new Date();
        Date begin = new Date(end.getTime() - windowHours * 3600_000L);
        context.setWindowStart(begin);
        context.setWindowEnd(end);
        long beginTime = Long.parseLong(COLLECT_TIME_FORMAT.format(begin));
        long endTime = Long.parseLong(COLLECT_TIME_FORMAT.format(end));
        context.setBeginCollectTime(beginTime);
        context.setEndCollectTime(endTime);

        List<InstanceInfo> all = appService.getAppOnlineInstanceInfo(appDesc.getAppId());
        List<InstanceInfo> instances = new ArrayList<>();
        List<InstanceInfo> sentinels = new ArrayList<>();
        if (CollectionUtils.isNotEmpty(all)) {
            for (InstanceInfo instance : all) {
                // sentinel 节点不承载数据，指标维度对它无意义
                if (TypeUtil.isRedisSentinel(instance.getType())) {
                    sentinels.add(instance);
                } else {
                    instances.add(instance);
                }
            }
        }
        context.setInstances(instances);
        // 高可用性维度要数哨兵个数，单独给它一份而不是放宽上面的过滤
        context.setSentinelInstances(sentinels);
        // 需要读运行时配置的维度（密码强度、持久化）走这一个入口，按实例缓存，
        // 否则每个维度各发一趟 CONFIG GET *
        context.setConfigLoader(instanceId -> redisCenter.getRedisConfigList(instanceId));

        List<InstanceRiskMetric> metrics = instanceRiskMetricDao.listByAppAndRange(
                appDesc.getAppId(), beginTime, endTime);
        Map<Long, List<InstanceRiskMetric>> byInstance = new HashMap<>();
        for (InstanceRiskMetric metric : metrics) {
            byInstance.computeIfAbsent(metric.getInstanceId(), k -> new ArrayList<>()).add(metric);
        }
        context.setMetricsByInstance(byInstance);
        context.setLatestSnapshot(instanceRiskMetricDao.listLatestSnapshot(
                appDesc.getAppId(), beginTime, endTime));
        context.setEarliestMetricTime(instanceRiskMetricDao.getEarliestCollectTime(appDesc.getAppId()));

        // 命令耗时样本：采集器一直在往 instance_command_latency_minute 写，
        // 但此前从未被读进上下文，导致 COMMAND_LATENCY 维度恒报「没有采集到样本」。
        List<InstanceCommandLatency> latencySamples = instanceCommandLatencyDao.listMinuteByAppAndRange(
                appDesc.getAppId(), beginTime, endTime);
        context.setCommandSamples(latencySamples == null ? new ArrayList<>() : latencySamples);

        // 有实例在窗口内没有任何样本，说明采集失败或刚纳管；倾斜判定需要知道样本是否完整
        List<InstanceInfo> uncollected = new ArrayList<>();
        for (InstanceInfo instance : instances) {
            if (!byInstance.containsKey((long) instance.getId())) {
                uncollected.add(instance);
            }
        }
        context.setUncollectedInstances(uncollected);

        loadSlowLogs(context, appDesc.getAppId(), begin, end);
        context.setCommandCounts(commandCountLoader.load(instances, beginTime, endTime));
        context.setRules(loadRules());
        return context;
    }

    private void loadSlowLogs(RiskAssessContext context, long appId, Date begin, Date end) {
        try {
            List<InstanceSlowLog> logs = instanceSlowLogDao.search(appId, begin, end);
            if (CollectionUtils.isEmpty(logs)) {
                return;
            }
            Map<Long, List<Long>> costs = new HashMap<>();
            List<Map<String, Object>> top = new ArrayList<>();
            logs.sort((a, b) -> Integer.compare(b.getCostTime(), a.getCostTime()));
            for (InstanceSlowLog log : logs) {
                costs.computeIfAbsent(log.getInstanceId(), k -> new ArrayList<>()).add((long) log.getCostTime());
                if (top.size() < 10) {
                    Map<String, Object> row = new HashMap<>();
                    row.put("instance", log.getIp() + ":" + log.getPort());
                    row.put("command", log.getCommand());
                    row.put("costMs", Math.round(log.getCostTime() / 1000.0D * 10) / 10.0D);
                    row.put("executeTime", log.getExecuteTime());
                    top.add(row);
                }
            }
            context.setSlowLogCosts(costs);
            context.setSlowLogTop(top);
        } catch (Exception e) {
            LOGGER.warn("load slow logs failed appId={}: {}", appId, e.getMessage());
        }
    }

    private Map<String, Map<String, Map<String, RiskAssessRule>>> loadRules() {
        Map<String, Map<String, Map<String, RiskAssessRule>>> rules = new HashMap<>();
        for (RiskAssessRule rule : riskAssessRuleDao.listAll()) {
            rules.computeIfAbsent(rule.getDimension(), k -> new HashMap<>())
                    .computeIfAbsent(rule.getSubItem() == null ? "" : rule.getSubItem(), k -> new HashMap<>())
                    .put(rule.getLevel(), rule);
        }
        return rules;
    }

    /**
     * 总评取各维度最差等级。加权求和在维度多时会产生严重的稀释效应——
     * 一个集群内存 95%、切了两次主，其余 12 个维度正常，加权后可能仍是「健康」。
     * 分数由等级映射而来，只用于排序与趋势，不参与判定。
     */
    private RiskAssessReport summarize(AppDesc appDesc, RiskAssessContext context,
                                       List<DimensionResult> results, String operator, long costMs) {
        RiskLevel worst = RiskLevel.NORMAL;
        int attentionCount = 0;
        int evaluated = 0;
        for (DimensionResult result : results) {
            if (!result.getLevel().isConclusive()) {
                continue;
            }
            evaluated++;
            worst = RiskLevel.worse(worst, result.getLevel());
            if (result.getLevel() == RiskLevel.ATTENTION) {
                attentionCount++;
            }
        }
        if (worst == RiskLevel.ATTENTION && attentionCount >= ATTENTION_ESCALATE_COUNT) {
            worst = RiskLevel.RISK;
        }

        RiskAssessReport report = new RiskAssessReport();
        report.setAppId(appDesc.getAppId());
        report.setAppName(appDesc.getName());
        report.setWindowHours(context.getWindowHours());
        report.setWindowStart(context.getWindowStart());
        report.setWindowEnd(context.getWindowEnd());
        report.setLevel(worst.name());
        report.setScore(score(worst, results));
        report.setEvaluatedDimensions(evaluated);
        report.setTotalDimensions(results.size());
        report.setInstanceCount(context.getInstances().size());
        report.setCollectedInstanceCount(context.getInstances().size() - context.getUncollectedInstances().size());
        report.setOperator(operator);
        report.setCostMs(costMs);
        report.setCreateTime(new Date());
        return report;
    }

    private int score(RiskLevel worst, List<DimensionResult> results) {
        int base;
        switch (worst) {
            case SEVERE:
                base = 30;
                break;
            case RISK:
                base = 60;
                break;
            case ATTENTION:
                base = 85;
                break;
            default:
                base = 100;
                break;
        }
        // 同级别内按命中数量微调，使排序有区分度
        int hits = 0;
        for (DimensionResult result : results) {
            if (result.getLevel().isConclusive() && result.getLevel() != RiskLevel.NORMAL) {
                hits++;
            }
        }
        return Math.max(0, base - Math.min(hits * 2, base / 2));
    }

    private void persist(RiskAssessReport report, List<DimensionResult> results) {
        riskAssessReportDao.saveReport(report);
        List<RiskAssessDimension> rows = new ArrayList<>();
        for (DimensionResult result : results) {
            RiskAssessDimension row = new RiskAssessDimension();
            row.setReportId(report.getId());
            row.setAppId(report.getAppId());
            row.setDimension(result.getDimension().name());
            row.setDimensionName(result.getDimension().getLabel());
            row.setLevel(result.getLevel().name());
            row.setActualValue(result.getActualValue());
            row.setThreshold(result.getThreshold());
            row.setInstanceId(result.getInstanceId());
            row.setInstanceHostPort(result.getInstanceHostPort());
            row.setSampleCount(result.getSampleCount());
            row.setSampleWindow(result.getSampleWindow());
            row.setSummary(result.getSummary());
            row.setSuggestion(result.getSuggestion());
            row.setEvidence(result.getEvidence().isEmpty() ? null : JSON.toJSONString(result.getEvidence()));
            rows.add(row);
        }
        if (!rows.isEmpty()) {
            riskAssessReportDao.batchSaveDimensions(rows);
        }
    }
}
