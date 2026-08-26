package com.shcj.cache.risk.evaluator;

import com.shcj.cache.entity.RiskAssessRule;
import com.shcj.cache.risk.model.DimensionResult;
import com.shcj.cache.risk.model.RiskAssessContext;
import com.shcj.cache.risk.model.RiskDimension;
import com.shcj.cache.risk.model.RiskLevel;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 危险命令调用。
 *
 * <p>数据源用 commandstats 的调用计数而不是慢日志——一次 FLUSHALL 在空库上可能只要 0.1ms，
 * 根本不进慢日志，但它足以毁掉一个集群。计数会忠实记录每一次调用，无论快慢。
 *
 * <p>分两档：破坏性命令出现即严重、不设次数阈值；阻塞类命令按频次定级。
 * 清单存规则表可增删——不同业务对"什么算危险"的判断不同，硬编码一定会被吐槽。
 */
@Component
public class DangerousCommandEvaluator extends AbstractDimensionEvaluator {

    /** 出现即严重，不看次数 */
    private static final Set<String> DESTRUCTIVE = new HashSet<>(Arrays.asList(
            "flushall", "flushdb", "shutdown", "debug", "swapdb"));

    /** 按频次定级 */
    private static final Set<String> BLOCKING = new HashSet<>(Arrays.asList(
            "keys", "hgetall", "smembers", "sunion", "sinter", "sort", "eval", "evalsha", "monitor"));

    /**
     * 平台采集器自身每分钟对每个实例发起的命令，必须先剔除。
     *
     * <p>否则每个实例都会被自己的监控判为高频危险命令——实测一个 24 小时窗口里
     * {@code CONFIG GET maxmemory} 就有上千次，直接把维度顶到「严重」。
     *
     * <p>代价是无法再从 commandstats 侧发现人为的 {@code CONFIG SET}：
     * commandstats 只按命令名聚合，区分不了 GET 与 SET 子命令。这类操作改由审计日志覆盖。
     */
    private static final Set<String> PLATFORM_ISSUED = new HashSet<>(Arrays.asList(
            "config", "info", "cluster", "slowlog", "latency", "client", "ping", "command", "dbsize"));

    @Override
    public RiskDimension dimension() {
        return RiskDimension.DANGEROUS_COMMAND;
    }

    @Override
    public DimensionResult evaluate(RiskAssessContext context) {
        if (context.getCommandCounts().isEmpty()) {
            return insufficient("窗口内没有命令调用样本");
        }

        Map<String, Long> destructiveHits = new LinkedHashMap<>();
        Map<String, Long> blockingHits = new LinkedHashMap<>();
        for (Map<String, Long> perInstance : context.getCommandCounts().values()) {
            for (Map.Entry<String, Long> entry : perInstance.entrySet()) {
                String command = entry.getKey();
                long count = entry.getValue() == null ? 0L : entry.getValue();
                if (count <= 0 || PLATFORM_ISSUED.contains(command)) {
                    continue;
                }
                if (DESTRUCTIVE.contains(command)) {
                    destructiveHits.merge(command, count, Long::sum);
                } else if (BLOCKING.contains(command)) {
                    blockingHits.merge(command, count, Long::sum);
                }
            }
        }

        if (!destructiveHits.isEmpty()) {
            DimensionResult result = DimensionResult.of(dimension(), RiskLevel.SEVERE,
                    "窗口内检测到破坏性命令调用：" + destructiveHits);
            result.setActualValue((double) destructiveHits.values().stream().mapToLong(Long::longValue).sum());
            result.setSuggestion("立即排查调用来源；生产实例建议通过 rename-command 禁用 FLUSHALL/FLUSHDB/DEBUG 等命令");
            result.evidence("destructive", destructiveHits);
            result.evidence("blocking", blockingHits);
            return result;
        }

        if (blockingHits.isEmpty()) {
            DimensionResult result = normal("窗口内未检测到危险命令调用");
            result.setActualValue(0D);
            return result;
        }

        long total = blockingHits.values().stream().mapToLong(Long::longValue).sum();
        LevelHit hit = judge(context, null, total);
        List<String> detail = new ArrayList<>();
        for (Map.Entry<String, Long> entry : blockingHits.entrySet()) {
            detail.add(entry.getKey() + " " + entry.getValue() + " 次");
        }
        DimensionResult result = build(dimension(), hit, (double) total,
                "窗口内阻塞类命令调用 " + total + " 次：" + String.join("，", detail));
        result.evidence("blocking", blockingHits);
        return result;
    }
}
