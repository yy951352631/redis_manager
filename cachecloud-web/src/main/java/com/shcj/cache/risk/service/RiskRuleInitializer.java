package com.shcj.cache.risk.service;

import com.shcj.cache.dao.RiskAssessRuleDao;
import com.shcj.cache.entity.RiskAssessRule;
import com.shcj.cache.risk.model.RiskDimension;
import com.shcj.cache.risk.model.RiskLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.HashMap;
import java.util.Map;

/**
 * 阈值规则的默认值初始化。
 *
 * <p>用 insert-if-absent，人工调过的阈值不会被启动刷回默认值。
 *
 * <p>同名维度的默认值<b>对齐 {@code instance_alert_configs}</b> 的现值——两套阈值必须同源，
 * 否则会出现「告警说没事、评估说严重」的自相矛盾，这是运维最不能忍的。
 * 但两张表分开存：告警要低噪声、阈值偏高，评估要分辨力、需要中间档，共用会互相绑架。
 */
@Component
public class RiskRuleInitializer {

    private static final Logger LOGGER = LoggerFactory.getLogger(RiskRuleInitializer.class);

    @Autowired
    private RiskAssessRuleDao riskAssessRuleDao;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @PostConstruct
    public void init() {
        try {
            Map<String, Double> alertThresholds = loadAlertThresholds();
            seedAll(alertThresholds);
            downgradeKeyExpireRules();
            LOGGER.info("risk assess rules initialized, total={}", riskAssessRuleDao.count());
        } catch (Exception e) {
            LOGGER.error("init risk assess rules failed: {}", e.getMessage(), e);
        }
    }

    /**
     * key 过期设置由「风险/严重」降级为只提示。
     *
     * <p>seed 是 insert-if-absent，改默认值动不了已存在的行，因此这里显式删除。
     * 只删未被人工改过的（customized=0），避免覆盖使用者自己调过的口径。
     */
    private void downgradeKeyExpireRules() {
        try {
            int removed = jdbcTemplate.update(
                    "delete from risk_assess_rule where dimension = ? and level in ('RISK', 'SEVERE') "
                            + "and customized = 0",
                    RiskDimension.KEY_EXPIRE.name());
            if (removed > 0) {
                LOGGER.info("downgraded KEY_EXPIRE rules, removed {} high-level rows", removed);
            }
        } catch (Exception e) {
            LOGGER.warn("downgrade KEY_EXPIRE rules failed: {}", e.getMessage());
        }
    }

    /** 读取告警配置中的现值，作为评估阈值「风险」档的默认来源 */
    private Map<String, Double> loadAlertThresholds() {
        Map<String, Double> result = new HashMap<>();
        try {
            jdbcTemplate.query(
                    "select alert_config, alert_value from instance_alert_configs where instance_id is null or instance_id = 0",
                    rs -> {
                        try {
                            result.put(rs.getString("alert_config"), Double.parseDouble(rs.getString("alert_value")));
                        } catch (Exception ignore) {
                            // 阈值是文本（如 rdb_last_bgsave_status=ok），本方法只取数值型
                        }
                    });
        } catch (Exception e) {
            LOGGER.warn("read instance_alert_configs failed, use built-in defaults: {}", e.getMessage());
        }
        return result;
    }

    private void seedAll(Map<String, Double> alert) {
        // 内存使用率（%）
        seed(RiskDimension.MEMORY_USAGE, null, RiskLevel.ATTENTION, "GT", 75D, null,
                "内存使用率超过 75%", "关注增长趋势，评估是否需要扩容");
        seed(RiskDimension.MEMORY_USAGE, null, RiskLevel.RISK, "GT", 85D, null,
                "内存使用率超过 85%", "尽快扩容或清理无用数据，并确认淘汰策略符合预期");
        seed(RiskDimension.MEMORY_USAGE, null, RiskLevel.SEVERE, "GT", 95D, null,
                "内存使用率超过 95%", "立即扩容；当前已濒临驱逐或 OOM");

        // 未设置 maxmemory 的实例数——没有淘汰兜底，内存会一路涨到吃满机器，本身即风险。
        // 与使用率各自定级、取最差；阈值按"有几个实例未设限"来配，可调可关。
        seed(RiskDimension.MEMORY_USAGE, "no_maxmemory", RiskLevel.ATTENTION, "GT", 0D, null,
                "存在未设置 maxmemory 的实例", "为实例设置 maxmemory 与淘汰策略，使用率将改以 maxmemory 计算");
        seed(RiskDimension.MEMORY_USAGE, "no_maxmemory", RiskLevel.RISK, "GT", 2D, null,
                "超过 2 个实例未设置 maxmemory", "集群多数节点无内存上限，需尽快统一补齐 maxmemory 配置");

        // 命中率（%），越低越差
        seed(RiskDimension.HIT_RATE, null, RiskLevel.ATTENTION, "LT", 90D, null,
                "命中率低于 90%", "检查缓存 key 设计与预热策略");
        seed(RiskDimension.HIT_RATE, null, RiskLevel.RISK, "LT", 80D, null,
                "命中率低于 80%", "大量请求穿透到后端，检查缓存策略与 TTL 设置");
        seed(RiskDimension.HIT_RATE, null, RiskLevel.SEVERE, "LT", 60D, null,
                "命中率低于 60%", "缓存基本未生效，需重新审视缓存设计");

        // 慢日志 P99（ms）
        seed(RiskDimension.SLOW_LOG, null, RiskLevel.ATTENTION, "GT", 100D, null,
                "慢日志 P99 超过 100ms", "查看 Top 慢命令，排查大 key 与复杂度过高的命令");
        seed(RiskDimension.SLOW_LOG, null, RiskLevel.RISK, "GT", 500D, null,
                "慢日志 P99 超过 500ms", "已明显阻塞主线程，需尽快优化命令或拆分大 key");
        seed(RiskDimension.SLOW_LOG, null, RiskLevel.SEVERE, "GT", 1000D, null,
                "慢日志 P99 超过 1s", "单条命令阻塞超过 1 秒，随时可能引发雪崩");

        // 危险命令（阻塞类累计调用次数）
        seed(RiskDimension.DANGEROUS_COMMAND, null, RiskLevel.ATTENTION, "GT", 0D, null,
                "存在阻塞类命令调用", "确认 KEYS/HGETALL/SMEMBERS 等命令的调用来源与数据规模");
        seed(RiskDimension.DANGEROUS_COMMAND, null, RiskLevel.RISK, "GT", 100D, null,
                "阻塞类命令调用超过 100 次", "改用 SCAN/HSCAN 等渐进式命令替代");
        seed(RiskDimension.DANGEROUS_COMMAND, null, RiskLevel.SEVERE, "GT", 1000D, null,
                "阻塞类命令调用超过 1000 次", "高频阻塞命令会持续占用主线程，需立即整改");

        // 连接数
        seed(RiskDimension.CONNECTION_USAGE, null, RiskLevel.ATTENTION, "GT", 3000D, null,
                "单实例连接数超过 3000", "确认客户端连接池配置是否合理");
        seed(RiskDimension.CONNECTION_USAGE, null, RiskLevel.RISK, "GT", 6000D, null,
                "单实例连接数超过 6000", "检查是否存在连接泄漏；确认 maxclients 余量");
        seed(RiskDimension.CONNECTION_USAGE, null, RiskLevel.SEVERE, "GT", 9000D, null,
                "单实例连接数超过 9000", "接近默认 maxclients 上限，新连接随时可能被拒绝");

        // 被拒绝连接（窗口增量）——告警配置里该项为 0，即出现即异常
        double rejected = alert.getOrDefault("rejected_connections", 0D);
        seed(RiskDimension.REJECTED_CONNECTION, null, RiskLevel.ATTENTION, "GT", rejected, null,
                "窗口内出现连接被拒绝", "检查 maxclients 与客户端连接池；对齐告警配置 rejected_connections");
        seed(RiskDimension.REJECTED_CONNECTION, null, RiskLevel.RISK, "GT", 10D, null,
                "窗口内连接被拒绝超过 10 次", "连接数已触顶，需调大 maxclients 或收敛客户端连接");
        seed(RiskDimension.REJECTED_CONNECTION, null, RiskLevel.SEVERE, "GT", 100D, null,
                "窗口内连接被拒绝超过 100 次", "大量客户端无法建连，业务已受影响");

        // fork 耗时（微秒）——风险档对齐告警配置 latest_fork_usec
        double fork = alert.getOrDefault("latest_fork_usec", 400000D);
        seed(RiskDimension.FORK_DURATION, null, RiskLevel.ATTENTION, "GT", 100000D, null,
                "fork 耗时超过 100ms", "关注实例内存规模，fork 期间主线程会被阻塞");
        seed(RiskDimension.FORK_DURATION, null, RiskLevel.RISK, "GT", fork, null,
                "fork 耗时超过 " + (long) fork + "μs（对齐告警配置）", "缩小实例内存或关闭不必要的持久化");
        seed(RiskDimension.FORK_DURATION, null, RiskLevel.SEVERE, "GT", 1000000D, null,
                "fork 耗时超过 1s", "每次持久化都会造成秒级卡顿，需立即拆分实例");

        // RDB 落盘耗时（秒）
        seed(RiskDimension.RDB_DURATION, null, RiskLevel.ATTENTION, "GT", 5D, null,
                "RDB 落盘耗时超过 5 秒", "关注数据量增长与磁盘性能");
        seed(RiskDimension.RDB_DURATION, null, RiskLevel.RISK, "GT", 30D, null,
                "RDB 落盘耗时超过 30 秒", "落盘期间 COW 内存开销大，需评估实例拆分");
        seed(RiskDimension.RDB_DURATION, null, RiskLevel.SEVERE, "GT", 120D, null,
                "RDB 落盘耗时超过 2 分钟", "持久化窗口过长，故障时数据丢失风险显著");

        // AOF 刷盘延迟（窗口增量）——对齐告警配置 aof_delayed_fsync
        double aof = alert.getOrDefault("aof_delayed_fsync", 3D);
        seed(RiskDimension.AOF_FSYNC_DELAY, null, RiskLevel.ATTENTION, "GT", 0D, null,
                "窗口内出现 AOF 刷盘延迟", "关注磁盘 IO 负载");
        seed(RiskDimension.AOF_FSYNC_DELAY, null, RiskLevel.RISK, "GT", aof, null,
                "AOF 刷盘延迟超过 " + (long) aof + " 次（对齐告警配置）", "磁盘 IO 已成瓶颈，考虑更换存储或调整 appendfsync");
        seed(RiskDimension.AOF_FSYNC_DELAY, null, RiskLevel.SEVERE, "GT", 100D, null,
                "AOF 刷盘延迟超过 100 次", "主线程频繁等待 fsync，写入延迟已明显劣化");

        // 未设置过期时间的 key 占比（%）——只到「关注」为止。
        // 不设 TTL 未必是问题：把 Redis 当持久存储、或用 maxmemory + LRU 兜底都是正当用法，
        // 真正的风险由内存使用率与内存增长率两个维度承担，这里只做提示，不参与定级恶化。
        seed(RiskDimension.KEY_EXPIRE, null, RiskLevel.ATTENTION, "GT", 50D, null,
                "超过 50% 的 key 未设置过期时间",
                "确认这些 key 是否应长期驻留；若非有意为之，补充 TTL 或确认已配置 maxmemory 淘汰策略");

        // 内存日均增长率（%，以窗口内均值为基准）。
        // 与「内存使用率」互补：使用率答「现在危不危险」，增长率答「照这个势头还有多久危险」。
        seed(RiskDimension.MEMORY_GROWTH, null, RiskLevel.ATTENTION, "GT", 5D, null,
                "内存日均增长超过 5%", "关注增长来源，确认是否为业务自然增长");
        seed(RiskDimension.MEMORY_GROWTH, null, RiskLevel.RISK, "GT", 10D, null,
                "内存日均增长超过 10%", "按此速度很快会触及上限，排查是否有 key 只写不删或 TTL 缺失");
        seed(RiskDimension.MEMORY_GROWTH, null, RiskLevel.SEVERE, "GT", 20D, null,
                "内存日均增长超过 20%", "增长失控，立即定位写入来源并评估扩容；参考证据中的预计打满天数");

        // 连接数日均增长率（%）
        seed(RiskDimension.CONNECTION_GROWTH, null, RiskLevel.ATTENTION, "GT", 10D, null,
                "连接数日均增长超过 10%", "确认是否为业务扩容；排除一次性阶跃后再看趋势");
        seed(RiskDimension.CONNECTION_GROWTH, null, RiskLevel.RISK, "GT", 25D, null,
                "连接数日均增长超过 25%", "多半是连接池只借不还或每次请求新建连接，检查客户端连接管理");
        seed(RiskDimension.CONNECTION_GROWTH, null, RiskLevel.SEVERE, "GT", 50D, null,
                "连接数日均增长超过 50%", "触及 maxclients 后新连接会被全部拒绝，故障是断崖式的，需立即排查客户端");

        // 命令执行耗时：与同架构、同大版本的平台基线之比（倍数）。
        // 用倍数而非绝对微秒——绝对值在不同机器与版本上不可比，见 CommandLatencyEvaluator 的说明。
        seed(RiskDimension.COMMAND_LATENCY, null, RiskLevel.ATTENTION, "GT", 2D, null,
                "某条命令平均耗时达到同类基线的 2 倍", "确认是否有大 key、热点 key 或该实例负载偏高");
        seed(RiskDimension.COMMAND_LATENCY, null, RiskLevel.RISK, "GT", 4D, null,
                "某条命令平均耗时达到同类基线的 4 倍", "排查大 key/慢命令与机器资源争用，必要时迁移实例");
        seed(RiskDimension.COMMAND_LATENCY, null, RiskLevel.SEVERE, "GT", 8D, null,
                "某条命令平均耗时达到同类基线的 8 倍", "该实例命令处理已明显劣化，优先排查阻塞操作与磁盘/CPU 争用");

        // 集群纪元变化次数
        seed(RiskDimension.CLUSTER_EPOCH, null, RiskLevel.ATTENTION, "GT", 0D, null,
                "窗口内发生过纪元变化", "确认是否为计划内运维操作");
        seed(RiskDimension.CLUSTER_EPOCH, null, RiskLevel.RISK, "GT", 2D, null,
                "窗口内纪元变化超过 2 次", "排查网络抖动与节点负载；检查 cluster-node-timeout");
        seed(RiskDimension.CLUSTER_EPOCH, null, RiskLevel.SEVERE, "GT", 5D, null,
                "窗口内纪元变化超过 5 次", "集群频繁切换，拓扑极不稳定");

        // 倾斜：五个子项共用比例阈值，各自的绝对值门槛不同
        seedSkew("memory", 1024D * 1024 * 1024, "内存");
        seedSkew("keys", 100000D, "key 数量");
        seedSkew("cpu", 1D, "CPU");
        seedSkew("qps", 100D, "QPS");
        seedSkew("traffic", 1024D * 1024, "吞吐量");
    }

    private void seedSkew(String subItem, double minAbsolute, String label) {
        seed(RiskDimension.CLUSTER_SKEW, subItem, RiskLevel.ATTENTION, "GT", 0.3D, minAbsolute,
                label + "倾斜超过 30%", "关注分片分布；确认是否存在热点 key");
        seed(RiskDimension.CLUSTER_SKEW, subItem, RiskLevel.RISK, "GT", 0.5D, minAbsolute,
                label + "倾斜超过 50%", "检查 slot 分布与大 key；必要时 reshard 或拆分热点 key");
        seed(RiskDimension.CLUSTER_SKEW, subItem, RiskLevel.SEVERE, "GT", 1.0D, minAbsolute,
                label + "倾斜超过 100%", "最热节点已是典型节点的两倍以上，会先于集群整体触顶");
    }

    private void seed(RiskDimension dimension, String subItem, RiskLevel level, String operator,
                      Double threshold, Double minAbsolute, String description, String suggestion) {
        RiskAssessRule rule = new RiskAssessRule();
        rule.setDimension(dimension.name());
        rule.setSubItem(subItem);
        rule.setLevel(level.name());
        rule.setOperator(operator);
        rule.setThreshold(threshold);
        rule.setMinAbsolute(minAbsolute);
        rule.setEnabled(1);
        rule.setDescription(description);
        rule.setSuggestion(suggestion);
        riskAssessRuleDao.insertIfAbsent(rule);
    }
}
