package com.shcj.cache.web.service;

import com.shcj.cache.dao.RiskAssessReportDao;
import com.shcj.cache.dao.RiskAssessRuleDao;
import com.shcj.cache.entity.RiskAssessRule;
import com.shcj.cache.risk.model.RiskDimension;
import com.shcj.cache.risk.model.RiskDimensionEvaluator;
import com.shcj.cache.web.controller.api.dto.RiskRuleDimensionDto;
import com.shcj.cache.entity.AppDesc;
import com.shcj.cache.entity.InstanceInfo;
import com.shcj.cache.entity.RiskAssessDimension;
import com.shcj.cache.entity.RiskAssessReport;
import com.shcj.cache.risk.model.RiskLevel;
import com.shcj.cache.risk.service.RiskAssessEngine;
import com.shcj.cache.web.controller.api.dto.RiskAssessDimensionDto;
import com.shcj.cache.web.controller.api.dto.RiskAssessOverviewDto;
import com.shcj.cache.web.controller.api.dto.RiskAssessOverviewItemDto;
import com.shcj.cache.web.controller.api.dto.RiskAssessReportDto;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 风险评估的查询与触发。
 */
@Service
public class RiskAssessApiService {

    private static final Logger LOGGER = LoggerFactory.getLogger(RiskAssessApiService.class);

    private static final SimpleDateFormat TIME_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    /**
     * 允许的窗口：30 天需要指标窄表保留 30 天，代价过大，因此上限为 7 天。
     *
     * <p>必须与前端 WINDOW_OPTIONS 保持一致。72h 这一档不能少——内存增长率、
     * 连接增长率、命令执行耗时三个维度的 minWindowHours 正是 72，
     * 少了它前端选「最近 3 天」会被静默改写成 7 天。</p>
     */
    private static final int[] ALLOWED_WINDOWS = {24, 72, 168};

    /** 同一集群同窗口在该秒数内重复触发，直接返回上一份报告，避免连点打爆采集 */
    private static final int DEDUP_SECONDS = 60;

    @Autowired
    private AppService appService;

    @Autowired
    private com.shcj.cache.dao.AppDao appDao;

    @Autowired
    private RiskAssessEngine riskAssessEngine;

    @Autowired
    private RiskAssessRuleDao riskAssessRuleDao;

    /** 评估器列表用来判断哪些维度只是声明了、还没有实现 */
    @Autowired(required = false)
    private List<RiskDimensionEvaluator> riskDimensionEvaluators;

    /** 各维度「看的是什么数」的说明，抽屉里配合阈值一起展示 */
    private static final Map<String, String> RISK_METRIC_DESC = buildMetricDesc();

    private static Map<String, String> buildMetricDesc() {
        Map<String, String> map = new LinkedHashMap<String, String>();
        map.put("MEMORY_USAGE", "取窗口内各实例 used_memory / maxmemory 的最大值；未设 maxmemory 时改用机器总内存，"
                + "并单独统计未设限的实例数");
        map.put("HIT_RATE", "窗口内 keyspace_hits / (hits + misses)，越低越差");
        map.put("SLOW_LOG", "窗口内慢日志耗时的 P99（毫秒）");
        map.put("DANGEROUS_COMMAND", "窗口内 KEYS/FLUSHALL/FLUSHDB 等危险命令的调用次数，取自 commandstats 差分");
        map.put("CONNECTION_USAGE", "connected_clients / maxclients 的最大值");
        map.put("REJECTED_CONNECTION", "窗口内 rejected_connections 的增量");
        map.put("FORK_DURATION", "latest_fork_usec 的最大值（毫秒）");
        map.put("RDB_DURATION", "rdb_last_bgsave_time_sec 的最大值（秒）");
        map.put("AOF_FSYNC_DELAY", "窗口内 aof_delayed_fsync 的增量");
        map.put("KEY_EXPIRE", "master 上未设置 TTL 的 key 占比。仅作提示，不参与风险定级——"
                + "不设 TTL 未必是问题，真正的隐患由内存使用率与增长率承担");
        map.put("CLUSTER_SKEW", "各分片在内存/key 数/CPU/QPS 上偏离均值的最大比例；绝对值过小时不判定");
        map.put("CLUSTER_EPOCH", "窗口内 cluster_current_epoch 的变化次数，反映发生过多少次切换");
        map.put("MEMORY_GROWTH", "对窗口内 used_memory 做最小二乘拟合，得到日均增长率（以窗口均值为基准），"
                + "取增长最快的实例。与「内存使用率」互补：使用率答现在危不危险，增长率答还有多久危险。"
                + "证据里附「预计打满天数」，但不用它定级——增长为负或已超限时该值无定义");
        map.put("CONNECTION_GROWTH", "对窗口内 connected_clients 做同样的拟合。连接数缓慢爬升多为客户端"
                + "连接池只借不还，危害有滞后性：触及 maxclients 前无感，之后新连接被全部拒绝。"
                + "平均连接数不足 10 时不判定，避免 2 个涨到 4 个被算成 100%");
        map.put("COMMAND_LATENCY", "各命令窗口内的平均耗时，与「同架构 + 同 Redis 大版本」的平台基线相比，"
                + "取最劣命令的倍数。基线由平台自身实例近 7 天的小时归档算出");
        map.put("ACCESS_PASSWORD", "读各数据节点的 requirepass：未设密码判风险；已设但不满足"
                + "「12 位以上且同时含大小写字母与数字」判关注。以实例上实际生效的配置为准，"
                + "而不是平台里存的连接密码——后者只说明平台怎么连，前者才决定别人能不能连进来");
        map.put("HIGH_AVAILABILITY", "检查每个主节点是否配有从节点。无从节点的主节点宕机即丢失该分片服务能力，"
                + "standalone 单实例同理，均判风险；哨兵架构下若存活哨兵不足 3 个，无法可靠仲裁，判关注");
        map.put("PERSISTENCE", "读各数据节点的 appendonly 与 save：两者皆空表示重启即全量丢数据，判关注。"
                + "RDB 是否开启只能从 CONFIG GET save 判断，INFO 里没有这个信息");
        return map;
    }

    /**
     * 分类型判定的维度：结论由代码里的分支决定，不存在可调阈值。
     *
     * <p>它们在 risk_assess_rule 表里没有行——硬塞进去会出现
     * operator/threshold 都是假值的记录，反而误导。这里直接构造展示项。</p>
     */
    private static final Map<String, List<RiskRuleDimensionDto.RiskRuleItemDto>> CATEGORICAL_RULES =
            buildCategoricalRules();

    private static Map<String, List<RiskRuleDimensionDto.RiskRuleItemDto>> buildCategoricalRules() {
        Map<String, List<RiskRuleDimensionDto.RiskRuleItemDto>> map =
                new LinkedHashMap<String, List<RiskRuleDimensionDto.RiskRuleItemDto>>();

        map.put("ACCESS_PASSWORD", Arrays.asList(
                categorical("SEVERE", "requirepass", "未设置密码",
                        "实例未配置 requirepass，网络可达即可直接读写，包括 FLUSHALL",
                        "立即配置 requirepass（12 位以上，含大小写字母与数字），并在「集群密码修改」同步"),
                categorical("ATTENTION", "requirepass", "长度 < 12，或缺少大写/小写/数字中任一类",
                        "已设密码但不满足强密码要求，存在被暴力破解的风险",
                        "将密码改为 12 位以上且同时包含大写字母、小写字母与数字")));

        map.put("HIGH_AVAILABILITY", Arrays.asList(
                categorical("RISK", "主从拓扑", "存在无从节点的主节点，或 standalone 单实例部署",
                        "主节点宕机即丢失该分片的服务能力，无法自动恢复",
                        "为每个主节点配置至少一个从节点；standalone 建议迁移到 sentinel / cluster"),
                categorical("ATTENTION", "哨兵数量", "哨兵架构下存活哨兵 < 3",
                        "主从齐全但哨兵不足 3 个时无法达成多数派，故障转移可能不会触发",
                        "补足至少 3 个哨兵节点")));

        map.put("PERSISTENCE", Arrays.asList(
                categorical("ATTENTION", "appendonly / save", "两者均未开启",
                        "重启或宕机会丢失全部数据",
                        "按用途选择：可容忍少量丢失用 RDB（save 配置点），要求尽量不丢用 AOF（appendfsync everysec）")));
        return map;
    }

    private static RiskRuleDimensionDto.RiskRuleItemDto categorical(String level, String subItem,
                                                                    String condition, String description,
                                                                    String suggestion) {
        RiskRuleDimensionDto.RiskRuleItemDto item = new RiskRuleDimensionDto.RiskRuleItemDto();
        item.setLevel(level);
        item.setLevelName(levelNameOf(level));
        item.setSubItem(subItem);
        item.setConditionText(condition);
        item.setDescription(description);
        item.setSuggestion(suggestion);
        item.setEnabled(true);
        return item;
    }

    private static String levelNameOf(String level) {
        if ("ATTENTION".equals(level)) return "关注";
        if ("RISK".equals(level)) return "风险";
        if ("SEVERE".equals(level)) return "严重";
        return level;
    }

    @Autowired
    private RiskAssessReportDao riskAssessReportDao;

    public RiskAssessOverviewDto overview(String keyword, String level, int pageNo, int pageSize) {
        List<AppDesc> apps = appDao.getOnlineApps();
        if (apps == null) {
            apps = new ArrayList<>();
        }
        List<AppDesc> filtered = new ArrayList<>();
        for (AppDesc app : apps) {
            if (StringUtils.isNotBlank(keyword)
                    && (app.getName() == null || !app.getName().toLowerCase().contains(keyword.toLowerCase()))) {
                continue;
            }
            filtered.add(app);
        }

        List<Long> appIds = new ArrayList<>();
        for (AppDesc app : filtered) {
            appIds.add(app.getAppId());
        }
        Map<Long, RiskAssessReport> latest = new HashMap<>();
        if (!appIds.isEmpty()) {
            for (RiskAssessReport report : riskAssessReportDao.listLatestByApps(appIds)) {
                latest.put(report.getAppId(), report);
            }
        }

        List<RiskAssessOverviewItemDto> items = new ArrayList<>();
        Map<String, Integer> levelCount = new LinkedHashMap<>();
        for (AppDesc app : filtered) {
            RiskAssessReport report = latest.get(app.getAppId());
            String itemLevel = report == null ? "UNASSESSED" : report.getLevel();
            levelCount.merge(itemLevel, 1, Integer::sum);
            if (StringUtils.isNotBlank(level) && !level.equals(itemLevel)) {
                continue;
            }
            items.add(toItem(app, report));
        }

        // 排序：先按风险严重度倒序，同级按分数升序，未评估的排最后
        items.sort((a, b) -> {
            int sa = severityOf(a.getLevel());
            int sb = severityOf(b.getLevel());
            if (sa != sb) {
                return sb - sa;
            }
            int scoreA = a.getScore() == null ? 101 : a.getScore();
            int scoreB = b.getScore() == null ? 101 : b.getScore();
            return scoreA - scoreB;
        });

        RiskAssessOverviewDto dto = new RiskAssessOverviewDto();
        dto.setTotalCount(items.size());
        dto.setPageNo(Math.max(1, pageNo));
        dto.setPageSize(pageSize < 1 ? 20 : pageSize);
        dto.setTotalPages(items.isEmpty() ? 0
                : (items.size() + dto.getPageSize() - 1) / dto.getPageSize());
        dto.setLevelCount(levelCount);

        int from = Math.min((dto.getPageNo() - 1) * dto.getPageSize(), items.size());
        int to = Math.min(from + dto.getPageSize(), items.size());
        dto.setItems(new ArrayList<>(items.subList(from, to)));
        return dto;
    }

    private int severityOf(String level) {
        if (level == null || "UNASSESSED".equals(level)) {
            return -1;
        }
        try {
            return RiskLevel.valueOf(level).getSeverity();
        } catch (IllegalArgumentException e) {
            return -1;
        }
    }

    private RiskAssessOverviewItemDto toItem(AppDesc app, RiskAssessReport report) {
        RiskAssessOverviewItemDto item = new RiskAssessOverviewItemDto();
        item.setAppId(app.getAppId());
        item.setAppName(app.getName());
        item.setTypeDesc(app.getTypeDesc());
        item.setVersionName(app.getVersionName());
        List<InstanceInfo> instances = appService.getAppOnlineInstanceInfo(app.getAppId());
        item.setInstanceCount(CollectionUtils.isEmpty(instances) ? 0 : instances.size());
        if (report != null) {
            item.setReportId(report.getId());
            item.setLevel(report.getLevel());
            item.setLevelLabel(labelOf(report.getLevel()));
            item.setScore(report.getScore());
            item.setWindowHours(report.getWindowHours());
            item.setEvaluatedDimensions(report.getEvaluatedDimensions());
            item.setTotalDimensions(report.getTotalDimensions());
            item.setLastAssessTime(report.getCreateTime() == null ? null : TIME_FORMAT.format(report.getCreateTime()));
        } else {
            item.setLevel("UNASSESSED");
            item.setLevelLabel("未评估");
        }
        return item;
    }

    public RiskAssessReportDto assess(long appId, int windowHours, String operator) {
        int window = normalizeWindow(windowHours);
        RiskAssessReport recent = riskAssessReportDao.getRecentReport(appId, window,
                new Date(System.currentTimeMillis() - DEDUP_SECONDS * 1000L));
        if (recent != null) {
            LOGGER.info("reuse recent risk report appId={} reportId={}", appId, recent.getId());
            return getReport(recent.getId());
        }
        RiskAssessReport report = riskAssessEngine.assess(appId, window, operator);
        return getReport(report.getId());
    }

    // 包级可见，便于单元测试锁住「前端有几档、后端就必须允许几档」
    int normalizeWindow(int windowHours) {
        for (int allowed : ALLOWED_WINDOWS) {
            if (allowed == windowHours) {
                return allowed;
            }
        }
        // 默认 7 天：唯一能让全部维度都出结论的窗口。
        // 这里会把用户选择悄悄换掉，因此留一条 WARN——前端新增了窗口档位却忘了
        // 同步 ALLOWED_WINDOWS 时，表现是「选了没反应」，日志是唯一的线索。
        LOGGER.warn("unsupported risk assess window {}h, fall back to 168h; "
                + "check ALLOWED_WINDOWS matches the frontend options", windowHours);
        return 168;
    }

    public RiskAssessReportDto getReport(long reportId) {
        RiskAssessReport report = riskAssessReportDao.getReport(reportId);
        if (report == null) {
            return null;
        }
        RiskAssessReportDto dto = new RiskAssessReportDto();
        dto.setReportId(report.getId());
        dto.setAppId(report.getAppId());
        dto.setAppName(report.getAppName());
        dto.setWindowHours(report.getWindowHours());
        dto.setWindowStart(format(report.getWindowStart()));
        dto.setWindowEnd(format(report.getWindowEnd()));
        dto.setLevel(report.getLevel());
        dto.setLevelLabel(labelOf(report.getLevel()));
        dto.setScore(report.getScore());
        dto.setEvaluatedDimensions(report.getEvaluatedDimensions());
        dto.setTotalDimensions(report.getTotalDimensions());
        dto.setInstanceCount(report.getInstanceCount());
        dto.setCollectedInstanceCount(report.getCollectedInstanceCount());
        dto.setOperator(report.getOperator());
        dto.setCostMs(report.getCostMs());
        dto.setCreateTime(format(report.getCreateTime()));

        for (RiskAssessDimension dimension : riskAssessReportDao.listDimensions(reportId)) {
            RiskAssessDimensionDto row = new RiskAssessDimensionDto();
            row.setDimension(dimension.getDimension());
            row.setDimensionName(dimension.getDimensionName());
            row.setLevel(dimension.getLevel());
            row.setLevelLabel(labelOf(dimension.getLevel()));
            row.setActualValue(dimension.getActualValue());
            row.setThreshold(dimension.getThreshold());
            row.setInstanceId(dimension.getInstanceId());
            row.setInstanceHostPort(dimension.getInstanceHostPort());
            row.setSampleCount(dimension.getSampleCount());
            row.setSampleWindow(dimension.getSampleWindow());
            row.setSummary(dimension.getSummary());
            row.setSuggestion(dimension.getSuggestion());
            row.setEvidence(dimension.getEvidence());
            dto.getDimensions().add(row);
        }
        return dto;
    }

    public List<RiskAssessReportDto> history(long appId, int limit) {
        List<RiskAssessReportDto> list = new ArrayList<>();
        for (RiskAssessReport report : riskAssessReportDao.listHistory(appId, limit < 1 ? 10 : limit)) {
            RiskAssessReportDto dto = new RiskAssessReportDto();
            dto.setReportId(report.getId());
            dto.setAppId(report.getAppId());
            dto.setAppName(report.getAppName());
            dto.setWindowHours(report.getWindowHours());
            dto.setLevel(report.getLevel());
            dto.setLevelLabel(labelOf(report.getLevel()));
            dto.setScore(report.getScore());
            dto.setEvaluatedDimensions(report.getEvaluatedDimensions());
            dto.setTotalDimensions(report.getTotalDimensions());
            dto.setCreateTime(format(report.getCreateTime()));
            list.add(dto);
        }
        return list;
    }

    private String labelOf(String level) {
        if (level == null) {
            return null;
        }
        try {
            return RiskLevel.valueOf(level).getLabel();
        } catch (IllegalArgumentException e) {
            return level;
        }
    }

    private String format(Date date) {
        return date == null ? null : TIME_FORMAT.format(date);
    }

    /**
     * 评估策略：把 risk_assess_rule 按维度组织后返回，供前端「评估策略」抽屉展示。
     *
     * <p>同时标出「声明了但还没有评估器实现」的维度——枚举里有、报告里不会出现，
     * 不说明的话使用者会以为是漏评了。
     */
    public List<RiskRuleDimensionDto> listRules() {
        Map<String, List<RiskAssessRule>> byDimension = new LinkedHashMap<String, List<RiskAssessRule>>();
        for (RiskAssessRule rule : riskAssessRuleDao.listAll()) {
            List<RiskAssessRule> list = byDimension.get(rule.getDimension());
            if (list == null) {
                list = new ArrayList<RiskAssessRule>();
                byDimension.put(rule.getDimension(), list);
            }
            list.add(rule);
        }

        Set<String> implemented = new HashSet<String>();
        if (riskDimensionEvaluators != null) {
            for (RiskDimensionEvaluator evaluator : riskDimensionEvaluators) {
                implemented.add(evaluator.dimension().name());
            }
        }

        List<RiskRuleDimensionDto> result = new ArrayList<RiskRuleDimensionDto>();
        // 按枚举顺序输出，保证抽屉里的排列稳定且与报告一致
        for (RiskDimension dimension : RiskDimension.values()) {
            RiskRuleDimensionDto dto = new RiskRuleDimensionDto();
            dto.setDimension(dimension.name());
            dto.setDimensionName(dimension.getLabel());
            dto.setMinWindowHours(dimension.getMinWindowHours());
            dto.setClusterOnly(dimension.isClusterOnly());
            dto.setNotImplemented(!implemented.contains(dimension.name()));
            dto.setMetricDesc(RISK_METRIC_DESC.get(dimension.name()));

            List<RiskRuleDimensionDto.RiskRuleItemDto> categorical = CATEGORICAL_RULES.get(dimension.name());
            if (categorical != null) {
                // 分类型维度（安全/可用性）不走 risk_assess_rule 的阈值比对，
                // 规则写在评估器里；这里把判定条件如实列出来，让策略抽屉与实现一致。
                dto.getItems().addAll(categorical);
                result.add(dto);
                continue;
            }

            List<RiskAssessRule> rules = byDimension.get(dimension.name());
            if (rules != null) {
                rules.sort(Comparator.comparingInt(r -> levelOrder(r.getLevel())));
                for (RiskAssessRule rule : rules) {
                    RiskRuleDimensionDto.RiskRuleItemDto item = new RiskRuleDimensionDto.RiskRuleItemDto();
                    item.setSubItem(rule.getSubItem());
                    item.setLevel(rule.getLevel());
                    item.setLevelName(levelName(rule.getLevel()));
                    item.setOperator(rule.getOperator());
                    item.setOperatorName(operatorName(rule.getOperator()));
                    item.setThreshold(rule.getThreshold());
                    item.setMinAbsolute(rule.getMinAbsolute());
                    item.setEnabled(rule.getEnabled() == 1);
                    item.setCustomized(rule.getCustomized() == 1);
                    item.setDescription(rule.getDescription());
                    item.setSuggestion(rule.getSuggestion());
                    dto.getItems().add(item);
                }
            }
            result.add(dto);
        }
        return result;
    }

    private int levelOrder(String level) {
        if ("ATTENTION".equals(level)) return 0;
        if ("RISK".equals(level)) return 1;
        if ("SEVERE".equals(level)) return 2;
        return 3;
    }

    private String levelName(String level) {
        if ("ATTENTION".equals(level)) return "关注";
        if ("RISK".equals(level)) return "风险";
        if ("SEVERE".equals(level)) return "严重";
        return level;
    }

    private String operatorName(String operator) {
        if (operator == null) return "大于";
        switch (operator) {
            case "GTE": return "大于等于";
            case "LT": return "小于";
            case "LTE": return "小于等于";
            case "EQ": return "等于";
            case "NE": return "不等于";
            case "GT":
            default: return "大于";
        }
    }
}
