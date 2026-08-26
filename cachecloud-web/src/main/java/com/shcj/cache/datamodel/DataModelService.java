package com.shcj.cache.datamodel;

import com.shcj.cache.entity.RiskAssessRule;
import com.shcj.cache.dao.RiskAssessRuleDao;
import com.shcj.cache.risk.model.RiskDimension;
import com.shcj.cache.web.controller.api.dto.DataModelDto;
import com.shcj.cache.web.controller.api.dto.DataModelOptionsDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 「数据模型」页的聚合查询。
 *
 * <p>实时聚合而非预计算：这些主题都是「按时间范围过滤 + GROUP BY 实例或分组属性」，
 * 分钟表在 (collect_time)、(app_id, collect_time) 上有索引，百万行量级下是亚秒到几秒的事，
 * 为它建一套物化表和补数逻辑收益不成正比。命令耗时主题走已有的小时归档表，
 * 那张表本身就是预计算产物。
 *
 * <p>结果按「筛选条件 + 主题」缓存 10 分钟。这一页是人在看图，不是被程序高频轮询，
 * 缓存主要用来挡住同一组条件下反复切主题时的重复扫表。
 */
@Service
public class DataModelService {

    private static final Logger LOGGER = LoggerFactory.getLogger(DataModelService.class);

    private static final SimpleDateFormat MINUTE_FORMAT = new SimpleDateFormat("yyyyMMddHHmm");
    private static final SimpleDateFormat HOUR_FORMAT = new SimpleDateFormat("yyyyMMddHH");

    private static final long CACHE_TTL_MS = 10 * 60 * 1000L;

    /** 命令耗时主题默认展示调用量最高的几条命令 */
    private static final int TOP_COMMANDS = 8;

    /** 单条命令在一个分组内至少要有这么多次调用才纳入基线 */
    private static final long MIN_COMMAND_CALLS = 1000L;

    /** 低于这个实例数就在图上标注样本不足 */
    private static final int LOW_SAMPLE_INSTANCES = 15;

    /** 低于这个天数就在图上标注覆盖时间过短 */
    private static final double LOW_SAMPLE_DAYS = 3D;

    /**
     * 计算「平均 key 大小」所需的最小 key 数。
     *
     * <p>used_memory 包含 Redis 自身的基础开销（几 MB 量级），key 数很少时这部分会完全主导结果——
     * 一个只有 1 个 key 的空节点会算出「平均 key 2.7MB」，纯属噪音。
     * 只有 key 数足够多、基础开销可忽略时，这个比值才近似反映真实的 key 体积。
     */
    private static final long MIN_KEYS_FOR_AVG_SIZE = 1000L;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private RiskAssessRuleDao riskAssessRuleDao;

    private final Map<String, CacheEntry> cache = new LinkedHashMap<String, CacheEntry>();

    private static final class CacheEntry {
        final DataModelDto value;
        final long at;

        CacheEntry(DataModelDto value) {
            this.value = value;
            this.at = System.currentTimeMillis();
        }
    }

    private DataModelDto cached(String topic, DataModelQuery query, java.util.function.Supplier<DataModelDto> loader) {
        String key = topic + "|" + query.toString();
        synchronized (cache) {
            CacheEntry entry = cache.get(key);
            if (entry != null && System.currentTimeMillis() - entry.at < CACHE_TTL_MS) {
                return entry.value;
            }
        }
        DataModelDto value = loader.get();
        synchronized (cache) {
            // 条件组合有限且页面用得不频繁，超过 64 项直接清空比做 LRU 简单且够用
            if (cache.size() > 64) {
                cache.clear();
            }
            cache.put(key, new CacheEntry(value));
        }
        return value;
    }

    /** 筛选项的可选值，全部来自平台现有数据 */
    public DataModelOptionsDto options() {
        DataModelOptionsDto dto = new DataModelOptionsDto();
        try {
            for (Map<String, Object> row : jdbcTemplate.queryForList(
                    "select app_id, name, is_test from app_desc order by app_id")) {
                DataModelOptionsDto.AppOption option = new DataModelOptionsDto.AppOption();
                option.setAppId(num(row.get("app_id")));
                option.setAppName(str(row.get("name")));
                option.setTestApp(num(row.get("is_test")) == 1);
                dto.getApps().add(option);
            }
            for (Map<String, Object> row : jdbcTemplate.queryForList(
                    "select distinct os_arch from instance_runtime_profile where os_arch is not null order by os_arch")) {
                dto.getArchs().add(str(row.get("os_arch")));
            }
            for (Map<String, Object> row : jdbcTemplate.queryForList(
                    "select distinct major_version from instance_runtime_profile "
                            + "where major_version is not null order by major_version")) {
                dto.getMajorVersions().add(str(row.get("major_version")));
            }
            for (Map<String, Object> row : jdbcTemplate.queryForList(
                    "select command, sum(calls) as calls from instance_command_latency_hour "
                            + "group by command order by calls desc limit 30")) {
                dto.getCommands().add(str(row.get("command")));
            }
        } catch (Exception e) {
            LOGGER.warn("load data model options failed: {}", e.getMessage());
        }
        return dto;
    }

    // #region 主题一：命令耗时基线
    /**
     * 分组柱状：X 轴为命令，每个分组是「架构 / 大版本」的一条系列。
     */
    public DataModelDto commandLatencyBaseline(DataModelQuery query) {
        return cached("commandLatency", query, () -> {
            DataModelDto dto = new DataModelDto();
            long since = Long.parseLong(HOUR_FORMAT.format(windowStart(query)));

            StringBuilder sql = new StringBuilder(
                    "select p.os_arch as arch, p.major_version as major, h.command as cmd, "
                            + "sum(h.calls) as calls, sum(h.usec) as usec, "
                            + "count(distinct h.instance_id) as instances "
                            + "from instance_command_latency_hour h "
                            + "join instance_runtime_profile p on p.instance_id = h.instance_id "
                            + "join app_desc a on a.app_id = h.app_id "
                            + "where h.collect_time >= ? ");
            List<Object> args = new ArrayList<Object>();
            args.add(since);
            appendProfileFilters(sql, args, query, "p", "a");
            sql.append(" group by p.os_arch, p.major_version, h.command");

            List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql.toString(), args.toArray());
            if (rows.isEmpty()) {
                dto.setEmptyReason("窗口内没有命令耗时归档数据。该主题依赖「命令耗时小时归档」任务，"
                        + "它每小时第 3 分钟执行一次，平台刚部署时需要等首次归档完成");
                return dto;
            }

            // 先统计各命令的总调用量，取 Top N 作为 X 轴
            Map<String, Long> callsByCommand = new HashMap<String, Long>();
            for (Map<String, Object> row : rows) {
                long calls = num(row.get("calls"));
                if (calls < MIN_COMMAND_CALLS) {
                    continue;
                }
                String cmd = str(row.get("cmd"));
                callsByCommand.merge(cmd, calls, Long::sum);
            }
            if (callsByCommand.isEmpty()) {
                dto.setEmptyReason("窗口内没有单命令调用量超过 " + MIN_COMMAND_CALLS + " 次的样本，"
                        + "调用量过低时平均耗时噪声太大，不作为基线");
                return dto;
            }

            List<String> commands;
            if (query.getCommands() != null && !query.getCommands().isEmpty()) {
                commands = new ArrayList<String>(query.getCommands());
                commands.retainAll(callsByCommand.keySet());
            } else {
                commands = new ArrayList<String>(callsByCommand.keySet());
                commands.sort(Comparator.comparingLong((String c) -> callsByCommand.get(c)).reversed());
                if (commands.size() > TOP_COMMANDS) {
                    commands = new ArrayList<String>(commands.subList(0, TOP_COMMANDS));
                }
            }

            // 分组 -> 命令 -> 平均耗时
            Map<String, Map<String, Double>> byGroup = new LinkedHashMap<String, Map<String, Double>>();
            Set<Long> instanceIds = new LinkedHashSet<Long>();
            int maxInstances = 0;
            for (Map<String, Object> row : rows) {
                long calls = num(row.get("calls"));
                if (calls < MIN_COMMAND_CALLS) {
                    continue;
                }
                String cmd = str(row.get("cmd"));
                if (!commands.contains(cmd)) {
                    continue;
                }
                String group = normalize(str(row.get("arch"))) + " / " + normalize(str(row.get("major")));
                byGroup.computeIfAbsent(group, k -> new HashMap<String, Double>())
                        .put(cmd, num(row.get("usec")) * 1.0D / calls);
                maxInstances = Math.max(maxInstances, (int) num(row.get("instances")));
            }

            dto.setCategories(commands);
            for (Map.Entry<String, Map<String, Double>> entry : byGroup.entrySet()) {
                dto.getGroups().add(entry.getKey());
                List<Double> values = new ArrayList<Double>();
                for (String cmd : commands) {
                    Double avg = entry.getValue().get(cmd);
                    values.add(avg == null ? null : round(avg));
                }
                dto.getSeries().add(values);
            }
            dto.setSampleCount(byGroup.size());
            dto.setInstanceCount(maxInstances);
            dto.setCoverDays(query.getWindowHours() / 24.0D);
            if (byGroup.size() < 2) {
                dto.setSampleNote("当前只有 " + byGroup.size() + " 个「架构 / 版本」分组，"
                        + "无法做跨架构或跨版本的对比；接入更多类型的实例后这张图才有比较价值");
            }
            appendLatencyThresholdLines(dto);
            return dto;
        });
    }

    /** 把风险评估现行的命令耗时阈值画成参考线——信息给到，改不改由人去评估策略里调 */
    private void appendLatencyThresholdLines(DataModelDto dto) {
        try {
            for (RiskAssessRule rule : riskAssessRuleDao.listByDimension(RiskDimension.COMMAND_LATENCY.name())) {
                if (rule.getThreshold() == null || rule.getEnabled() != 1) {
                    continue;
                }
                DataModelDto.DataModelReferenceLine line = new DataModelDto.DataModelReferenceLine();
                line.setLabel("风险评估阈值 " + levelName(rule.getLevel()) + "（基线 " + rule.getThreshold() + " 倍）");
                line.setValue(rule.getThreshold());
                dto.getReferenceLines().add(line);
            }
        } catch (Exception e) {
            LOGGER.warn("load command latency thresholds failed: {}", e.getMessage());
        }
    }

    private String levelName(String level) {
        if ("ATTENTION".equals(level)) return "关注";
        if ("RISK".equals(level)) return "风险";
        if ("SEVERE".equals(level)) return "严重";
        return level;
    }
    // #endregion

    // #region 主题二/三：散点
    /** key 平均大小（KB）对 QPS 的影响 */
    public DataModelDto keySizeVsQps(DataModelQuery query) {
        return cached("keySizeVsQps", query, () -> scatter(query,
                "avg(case when m.keys_count >= " + MIN_KEYS_FOR_AVG_SIZE
                        + " then m.used_memory / m.keys_count else null end) / 1024",
                "avg(m.instantaneous_ops)",
                "key 平均大小 (KB)", "QPS (ops/s)",
                "key 平均大小", "QPS"));
    }

    /** 内存体积对 fork 耗时的影响 */
    public DataModelDto memoryVsPersistence(DataModelQuery query) {
        return cached("memoryVsPersistence", query, () -> scatter(query,
                "avg(m.used_memory) / 1024 / 1024",
                "max(m.latest_fork_usec) / 1000",
                "已用内存 (MB)", "fork 耗时 (ms)",
                "已用内存", "fork 耗时"));
    }

    /**
     * 散点主题的共同实现：一个实例聚合成一个点。
     *
     * @param xExpr X 轴聚合表达式，SQL 片段
     * @param yExpr Y 轴聚合表达式，SQL 片段
     */
    private DataModelDto scatter(DataModelQuery query, String xExpr, String yExpr,
                                 String xAxisName, String yAxisName,
                                 String xLabel, String yLabel) {
        DataModelDto dto = new DataModelDto();
        dto.setXAxisName(xAxisName);
        dto.setYAxisName(yAxisName);

        long since = Long.parseLong(MINUTE_FORMAT.format(windowStart(query)));
        StringBuilder sql = new StringBuilder(
                "select m.instance_id as iid, m.ip as ip, m.port as port, a.name as app_name, "
                        + "p.os_arch as arch, p.major_version as major, "
                        + xExpr + " as x, " + yExpr + " as y, count(1) as samples "
                        + "from instance_risk_metric_minute m "
                        + "join app_desc a on a.app_id = m.app_id "
                        + "left join instance_runtime_profile p on p.instance_id = m.instance_id "
                        + "where m.collect_time >= ? ");
        List<Object> args = new ArrayList<Object>();
        args.add(since);
        appendMetricFilters(sql, args, query);
        sql.append(" group by m.instance_id, m.ip, m.port, a.name, p.os_arch, p.major_version");

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql.toString(), args.toArray());
        if (rows.isEmpty()) {
            dto.setEmptyReason("窗口内没有采集到分钟指标。请确认实例已纳管且采集任务正常运行");
            return dto;
        }

        Map<String, DataModelDto.DataModelScatterSeries> byArch =
                new LinkedHashMap<String, DataModelDto.DataModelScatterSeries>();
        List<double[]> points = new ArrayList<double[]>();
        for (Map<String, Object> row : rows) {
            Double x = dbl(row.get("x"));
            Double y = dbl(row.get("y"));
            if (x == null || y == null) {
                continue;
            }
            String arch = normalize(str(row.get("arch")));
            DataModelDto.DataModelScatterSeries series = byArch.get(arch);
            if (series == null) {
                series = new DataModelDto.DataModelScatterSeries();
                series.setName(arch);
                byArch.put(arch, series);
            }
            DataModelDto.DataModelPoint point = new DataModelDto.DataModelPoint();
            point.setX(round(x));
            point.setY(round(y));
            point.setLabel(str(row.get("app_name")) + " " + str(row.get("ip")) + ":" + str(row.get("port")));
            series.getPoints().add(point);
            points.add(new double[]{x, y});
        }

        dto.getScatterSeries().addAll(byArch.values());
        dto.setSampleCount(points.size());
        dto.setInstanceCount(points.size());
        dto.setCoverDays(query.getWindowHours() / 24.0D);
        dto.setCorrelation(buildCorrelation(points, xLabel, yLabel));
        int excluded = rows.size() - points.size();
        if (points.size() < LOW_SAMPLE_INSTANCES) {
            dto.setSampleNote("仅 " + points.size() + " 个实例参与统计，样本量偏少，"
                    + "趋势线与相关性仅供参考；实例数达到 " + LOW_SAMPLE_INSTANCES + " 个后结论才有统计意义"
                    + (excluded > 0 ? "。另有 " + excluded + " 个实例因指标无法计算被排除"
                        + "（如 key 数不足 " + MIN_KEYS_FOR_AVG_SIZE + " 个时平均 key 大小会被 Redis 基础开销主导）" : ""));
        } else if (query.getWindowHours() / 24.0D < LOW_SAMPLE_DAYS) {
            dto.setSampleNote("时间窗口不足 " + (int) LOW_SAMPLE_DAYS + " 天，单日波动可能主导结果");
        }
        return dto;
    }

    private DataModelDto.DataModelCorrelation buildCorrelation(List<double[]> points, String xLabel, String yLabel) {
        DataModelDto.DataModelCorrelation result = new DataModelDto.DataModelCorrelation();
        result.setSampleCount(points.size());

        Correlation correlation = Correlation.of(points);
        if (correlation == null) {
            result.setStrength("INSUFFICIENT");
            result.setConclusion("样本不足，无法分析相关性");
            return result;
        }
        if (!correlation.isReliable()) {
            // 少样本下相关系数极不稳定，只画线不给数——给了就会被当结论用
            result.setStrength("INSUFFICIENT");
            result.setConclusion(String.format("仅 %d 个样本（需 %d 个以上），暂不给出相关系数",
                    correlation.getSampleCount(), Correlation.MIN_RELIABLE_SAMPLES));
            addLine(result, correlation);
            return result;
        }

        result.setCoefficient(round(correlation.getCoefficient()));
        result.setSlope(round(correlation.getSlope()));
        result.setStrength(correlation.strength());
        if ("NONE".equals(correlation.strength())) {
            result.setConclusion(String.format("%s 与 %s 未见明显相关（r=%.2f，n=%d）",
                    xLabel, yLabel, correlation.getCoefficient(), correlation.getSampleCount()));
        } else {
            String direction = correlation.getSlope() >= 0 ? "上升" : "下降";
            String strengthText = "STRONG".equals(correlation.strength()) ? "相关性强" : "中等相关";
            result.setConclusion(String.format("%s 每增加 1 个单位，%s 平均%s约 %.2f（r=%.2f，n=%d，%s）",
                    xLabel, yLabel, direction, Math.abs(correlation.getSlope()),
                    correlation.getCoefficient(), correlation.getSampleCount(), strengthText));
        }
        addLine(result, correlation);
        return result;
    }

    private void addLine(DataModelDto.DataModelCorrelation result, Correlation correlation) {
        for (double[] p : correlation.linePoints()) {
            DataModelDto.DataModelPoint point = new DataModelDto.DataModelPoint();
            point.setX(round(p[0]));
            point.setY(round(p[1]));
            result.getLine().add(point);
        }
    }
    // #endregion

    // #region 主题四：集群横向对比
    public DataModelDto clusterComparison(DataModelQuery query) {
        return cached("clusterComparison", query, () -> {
            DataModelDto dto = new DataModelDto();
            long since = Long.parseLong(MINUTE_FORMAT.format(windowStart(query)));

            StringBuilder sql = new StringBuilder(
                    "select m.app_id as app_id, a.name as app_name, a.type as app_type, "
                            + "count(distinct m.instance_id) as instances, "
                            + "avg(m.instantaneous_ops) as qps, "
                            + "avg(m.mem_frag_ratio) as frag, "
                            + "avg(m.connected_clients) as clients, "
                            + "sum(m.keyspace_hits) as hits, sum(m.keyspace_misses) as misses, "
                            + "avg(m.used_memory) as used_memory, "
                            + "avg(case when m.max_memory > 0 then m.max_memory else m.total_system_memory end) as capacity, "
                            + "avg(m.keys_count) as keys_count, "
                            // 与散点主题同口径：逐行相除再平均，而不是 avg(内存)/avg(key数)。
                            // 后者在 key 数极少时会被放大到失真——两个主题对同一个概念给出不同的数，
                            // 会让整页的结论都不可信
                            + "avg(case when m.keys_count >= " + MIN_KEYS_FOR_AVG_SIZE
                            + " then m.used_memory / m.keys_count else null end) as avg_key_bytes, "
                            + "min(p.os_arch) as arch, min(p.major_version) as major "
                            + "from instance_risk_metric_minute m "
                            + "join app_desc a on a.app_id = m.app_id "
                            + "left join instance_runtime_profile p on p.instance_id = m.instance_id "
                            + "where m.collect_time >= ? ");
            List<Object> args = new ArrayList<Object>();
            args.add(since);
            appendMetricFilters(sql, args, query);
            sql.append(" group by m.app_id, a.name, a.type order by qps desc");

            List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql.toString(), args.toArray());
            if (rows.isEmpty()) {
                dto.setEmptyReason("窗口内没有采集到分钟指标");
                return dto;
            }
            for (Map<String, Object> row : rows) {
                DataModelDto.DataModelComparisonRow item = new DataModelDto.DataModelComparisonRow();
                item.setAppId(num(row.get("app_id")));
                item.setAppName(str(row.get("app_name")));
                item.setTypeDesc(appTypeDesc((int) num(row.get("app_type"))));
                item.setOsArch(normalize(str(row.get("arch"))));
                item.setMajorVersion(normalize(str(row.get("major"))));
                item.setInstanceCount((int) num(row.get("instances")));
                item.setQps(roundOrNull(dbl(row.get("qps"))));
                item.setMemFragRatio(roundOrNull(dbl(row.get("frag"))));
                item.setConnectedClients(roundOrNull(dbl(row.get("clients"))));

                long hits = num(row.get("hits"));
                long misses = num(row.get("misses"));
                item.setHitPercent(hits + misses > 0 ? round(hits * 100.0D / (hits + misses)) : null);

                Double usedMemory = dbl(row.get("used_memory"));
                Double capacity = dbl(row.get("capacity"));
                item.setUsedMemoryMb(usedMemory == null ? null : round(usedMemory / 1024 / 1024));
                item.setMemUsePercent(usedMemory != null && capacity != null && capacity > 0
                        ? round(usedMemory * 100.0D / capacity) : null);

                Double keys = dbl(row.get("keys_count"));
                item.setKeysCount(keys == null ? null : (long) keys.doubleValue());
                item.setAvgKeyBytes(roundOrNull(dbl(row.get("avg_key_bytes"))));
                dto.getComparisonRows().add(item);
            }
            dto.setSampleCount(dto.getComparisonRows().size());
            dto.setCoverDays(query.getWindowHours() / 24.0D);
            int instances = 0;
            for (DataModelDto.DataModelComparisonRow row : dto.getComparisonRows()) {
                instances += row.getInstanceCount();
            }
            dto.setInstanceCount(instances);
            if (dto.getComparisonRows().size() < 2) {
                dto.setSampleNote("只有 " + dto.getComparisonRows().size() + " 套集群参与统计，横向对比需要至少 2 套");
            }
            return dto;
        });
    }

    private String appTypeDesc(int type) {
        switch (type) {
            case 2: return "cluster";
            case 5: return "sentinel";
            case 6: return "standalone";
            default: return "type-" + type;
        }
    }
    // #endregion

    // #region 筛选拼接
    /** 分钟指标表的筛选：实例画像用 left join，条件要允许画像缺失的实例通过 */
    private void appendMetricFilters(StringBuilder sql, List<Object> args, DataModelQuery query) {
        if (query.getRole() != null) {
            sql.append(" and m.role = ?");
            args.add(query.getRole());
        }
        appendProfileFilters(sql, args, query, "p", "a");
        if (query.getAppIds() != null && !query.getAppIds().isEmpty()) {
            sql.append(" and m.app_id in (").append(placeholders(query.getAppIds().size())).append(")");
            args.addAll(query.getAppIds());
        }
    }

    private void appendProfileFilters(StringBuilder sql, List<Object> args, DataModelQuery query,
                                      String profileAlias, String appAlias) {
        if (query.getArchs() != null && !query.getArchs().isEmpty()) {
            sql.append(" and ").append(profileAlias).append(".os_arch in (")
                    .append(placeholders(query.getArchs().size())).append(")");
            args.addAll(query.getArchs());
        }
        if (query.getMajorVersions() != null && !query.getMajorVersions().isEmpty()) {
            sql.append(" and ").append(profileAlias).append(".major_version in (")
                    .append(placeholders(query.getMajorVersions().size())).append(")");
            args.addAll(query.getMajorVersions());
        }
        if (query.isExcludeTestApp()) {
            // 测试集群的负载模式与生产完全不同，混进来会把基线带偏
            sql.append(" and ").append(appAlias).append(".is_test <> 1");
        }
    }

    private String placeholders(int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            sb.append(i == 0 ? "?" : ",?");
        }
        return sb.toString();
    }

    private Date windowStart(DataModelQuery query) {
        int hours = query.getWindowHours() <= 0 ? 168 : query.getWindowHours();
        return new Date(System.currentTimeMillis() - hours * 3600_000L);
    }
    // #endregion

    // #region 取值辅助
    private String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String normalize(String value) {
        return value == null || value.trim().isEmpty() ? "未知" : value.trim();
    }

    private long num(Object value) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        try {
            return value == null ? 0L : Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private Double dbl(Object value) {
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private double round(double value) {
        return Math.round(value * 100) / 100.0D;
    }

    private Double roundOrNull(Double value) {
        return value == null ? null : round(value);
    }
    // #endregion
}
