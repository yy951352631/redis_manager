package com.shcj.cache.risk.evaluator;

import com.shcj.cache.entity.InstanceRiskMetric;
import com.shcj.cache.risk.model.DimensionResult;
import com.shcj.cache.risk.model.RiskAssessContext;
import com.shcj.cache.risk.model.RiskDimension;
import com.shcj.cache.risk.model.RiskLevel;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 集群倾斜：内存 / key 数量 / CPU / QPS / 吞吐量五个子项各自定级，取最差。
 *
 * <p>三条关键约定：
 * <ul>
 *   <li><b>只比 master。</b>slave 是副本，内存与 key 天然接近其 master，混进来等于把样本重复一遍，
 *       会把真实倾斜稀释掉一半；QPS 更甚——slave 默认不接读，混进来会制造出"倾斜 100%"的假象。</li>
 *   <li><b>用 {@code max/median - 1} 而非 max/min 或变异系数。</b>倾斜的实际危害是单点先被打满，
 *       有意义的量是"最坏节点相对典型水平超出多少"。max/min 会被一个空节点毁掉；
 *       变异系数对"单点特别高"不敏感（200/200/400 的 CV 只有 0.33，但那个 400 已经先一步逼近上限）。</li>
 *   <li><b>必须有绝对值下限。</b>每节点 30MB 的集群倾斜 3 倍也毫无危害，
 *       没有下限门槛的话空集群会天天报倾斜，这个功能三天就没人看了。</li>
 * </ul>
 */
@Component
public class ClusterSkewEvaluator extends AbstractDimensionEvaluator {

    private static final String[] SUB_ITEMS = {"memory", "keys", "cpu", "qps", "traffic"};
    private static final String[] SUB_LABELS = {"内存", "key 数量", "CPU", "QPS", "吞吐量"};

    @Override
    public RiskDimension dimension() {
        return RiskDimension.CLUSTER_SKEW;
    }

    @Override
    public DimensionResult evaluate(RiskAssessContext context) {
        List<InstanceRiskMetric> masters = new ArrayList<>();
        for (InstanceRiskMetric metric : context.getLatestSnapshot()) {
            if (metric.getRole() == 1) {
                masters.add(metric);
            }
        }
        if (masters.size() < 2) {
            return insufficient("同一采集时刻的 master 样本不足 2 个（当前 " + masters.size() + " 个），无法计算倾斜");
        }
        if (!context.getUncollectedInstances().isEmpty()) {
            // 样本残缺时倾斜结论不可靠，但仍给出并在证据里标注，由人判断
            return skew(context, masters, true);
        }
        return skew(context, masters, false);
    }

    private DimensionResult skew(RiskAssessContext context, List<InstanceRiskMetric> masters, boolean partial) {
        RiskLevel worstLevel = RiskLevel.NORMAL;
        String worstItem = null;
        double worstRatio = 0D;
        Double worstThreshold = null;
        String worstInstance = null;
        String worstSuggestion = null;
        Map<String, Object> evidence = new LinkedHashMap<>();

        for (int i = 0; i < SUB_ITEMS.length; i++) {
            List<Double> values = new ArrayList<>();
            List<String> labels = new ArrayList<>();
            for (InstanceRiskMetric master : masters) {
                Double value = valueOf(context, master, SUB_ITEMS[i]);
                if (value == null) {
                    continue;
                }
                values.add(value);
                labels.add(master.getIp() + ":" + master.getPort());
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("label", SUB_LABELS[i]);
            if (values.size() < 2) {
                row.put("level", RiskLevel.INSUFFICIENT_DATA.name());
                row.put("reason", "可比样本不足 2 个");
                evidence.put(SUB_ITEMS[i], row);
                continue;
            }

            double median = median(values);
            double max = Collections.max(values);
            String maxInstance = labels.get(values.indexOf(max));
            // median 为 0 时比值无意义（如集群完全空载）
            double ratio = median <= 0 ? 0D : max / median - 1;

            LevelHit hit = judge(context, SUB_ITEMS[i], ratio);
            // 绝对值门槛：最大值太小时倾斜无危害，直接视为正常
            Double minAbsolute = minAbsolute(context, SUB_ITEMS[i]);
            if (minAbsolute != null && max < minAbsolute) {
                hit = new LevelHit(RiskLevel.NORMAL, hit.threshold, null);
                row.put("belowMinAbsolute", minAbsolute);
            }

            row.put("max", Math.round(max * 100) / 100.0D);
            row.put("median", Math.round(median * 100) / 100.0D);
            row.put("skewPercent", Math.round(ratio * 10000) / 100.0D);
            row.put("maxInstance", maxInstance);
            row.put("level", hit.level.name());
            evidence.put(SUB_ITEMS[i], row);

            if (hit.level.getSeverity() > worstLevel.getSeverity()) {
                worstLevel = hit.level;
                worstItem = SUB_LABELS[i];
                worstRatio = ratio;
                worstThreshold = hit.threshold;
                worstInstance = maxInstance;
                worstSuggestion = hit.suggestion;
            }
        }

        String summary = worstLevel == RiskLevel.NORMAL
                ? "各 master 之间内存/key/CPU/QPS/吞吐量分布均衡"
                : String.format("%s倾斜 %.1f%%，最高节点 %s", worstItem, worstRatio * 100, worstInstance);
        if (partial) {
            summary = summary + "（注意：有实例采集失败，样本不完整）";
        }

        DimensionResult result = DimensionResult.of(dimension(), worstLevel, summary);
        result.setActualValue(Math.round(worstRatio * 10000) / 100.0D);
        result.setThreshold(worstThreshold);
        result.setInstanceHostPort(worstInstance);
        result.setSampleCount(masters.size());
        result.setSuggestion(worstSuggestion);
        result.evidence("subItems", evidence);
        result.evidence("masterCount", masters.size());
        if (partial) {
            result.evidence("uncollectedCount", context.getUncollectedInstances().size());
        }
        return result;
    }

    /**
     * 取某个 master 在该子项上的可比值。
     *
     * <p>内存、key、QPS 在快照里就是瞬时量，直接取；<b>CPU 与吞吐量是累计值</b>，
     * 直接跨实例比较会被启动时长差异污染——一个昨天重启的 master 累计 CPU 必然远低于
     * 已跑 30 天的同伴，那不是倾斜。因此这两项改用窗口内的增量除以采样点跨度，
     * 还原成速率后再比。
     */
    private Double valueOf(RiskAssessContext context, InstanceRiskMetric master, String subItem) {
        switch (subItem) {
            case "memory":
                return (double) master.getUsedMemory();
            case "keys":
                return (double) master.getKeysCount();
            case "qps":
                return (double) master.getInstantaneousOps();
            case "cpu":
                return rate(context, master, true);
            case "traffic":
                return rate(context, master, false);
            default:
                return null;
        }
    }

    private Double rate(RiskAssessContext context, InstanceRiskMetric master, boolean cpu) {
        List<InstanceRiskMetric> list = context.metricsOf(master.getInstanceId());
        if (list.size() < 2) {
            return null;
        }
        InstanceRiskMetric first = list.get(0);
        InstanceRiskMetric last = list.get(list.size() - 1);
        double delta = cpu
                ? (last.getUsedCpuSys() + last.getUsedCpuUser()) - (first.getUsedCpuSys() + first.getUsedCpuUser())
                : (last.getNetInputBytes() + last.getNetOutputBytes())
                        - (first.getNetInputBytes() + first.getNetOutputBytes());
        // 负值说明实例在窗口内重启过，计数器归零，该实例不参与本子项比较
        if (delta < 0) {
            return null;
        }
        int points = list.size() - 1;
        return points <= 0 ? null : delta / points;
    }

    private Double minAbsolute(RiskAssessContext context, String subItem) {
        for (RiskLevel level : new RiskLevel[]{RiskLevel.ATTENTION, RiskLevel.RISK, RiskLevel.SEVERE}) {
            com.shcj.cache.entity.RiskAssessRule rule = context.rule(dimension(), subItem, level);
            if (rule != null && rule.getMinAbsolute() != null) {
                return rule.getMinAbsolute();
            }
        }
        return null;
    }

    private double median(List<Double> values) {
        List<Double> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int size = sorted.size();
        if (size % 2 == 1) {
            return sorted.get(size / 2);
        }
        return (sorted.get(size / 2 - 1) + sorted.get(size / 2)) / 2.0D;
    }
}
