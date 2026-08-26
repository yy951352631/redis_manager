package com.shcj.cache.risk.evaluator;

import com.shcj.cache.entity.InstanceInfo;
import com.shcj.cache.redis.RedisCenter;
import com.shcj.cache.risk.model.DimensionResult;
import com.shcj.cache.risk.model.RiskAssessContext;
import com.shcj.cache.risk.model.RiskDimension;
import com.shcj.cache.risk.model.RiskLevel;
import com.shcj.cache.util.ConstUtils;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 访问密码强度。
 *
 * <p>定级：未设密码 → 严重；已设但不满足强密码要求 → 关注；满足 → 正常。</p>
 *
 * <p>取值以实例上的 {@code requirepass} 为准而不是平台里存的密码：
 * 平台记录的是"连接时用什么"，实例上真正生效的才是"别人能不能连进来"，
 * 两者可能不一致，而后者才是安全结论。</p>
 */
@Component
public class AccessPasswordEvaluator extends AbstractDimensionEvaluator {

    private static final Logger logger = LoggerFactory.getLogger(AccessPasswordEvaluator.class);

    /** 强密码最小长度 */
    private static final int MIN_LENGTH = 12;

    @Autowired
    private RedisCenter redisCenter;

    @Override
    public RiskDimension dimension() {
        return RiskDimension.ACCESS_PASSWORD;
    }

    @Override
    public DimensionResult evaluate(RiskAssessContext context) {
        List<InstanceInfo> targets = dataInstances(context);
        if (targets.isEmpty()) {
            return insufficient("没有可检查的数据节点");
        }

        List<String> noPassword = new ArrayList<>();
        List<String> weakPassword = new ArrayList<>();
        List<String> unreachable = new ArrayList<>();
        Map<String, Object> detail = new LinkedHashMap<>();

        for (InstanceInfo instance : targets) {
            String hostPort = instance.getIp() + ":" + instance.getPort();
            String password;
            try {
                Map<String, String> config = redisCenter.getRedisConfigList(instance.getId());
                if (config == null || !config.containsKey("requirepass")) {
                    unreachable.add(hostPort);
                    detail.put(hostPort, "读取配置失败");
                    continue;
                }
                password = StringUtils.trimToEmpty(config.get("requirepass"));
            } catch (Exception e) {
                logger.warn("read requirepass failed {}: {}", hostPort, e.getMessage());
                unreachable.add(hostPort);
                detail.put(hostPort, "读取配置失败");
                continue;
            }
            if (password.isEmpty()) {
                noPassword.add(hostPort);
                detail.put(hostPort, "未设置密码");
            } else if (!isStrong(password)) {
                weakPassword.add(hostPort);
                detail.put(hostPort, "密码强度不足（" + describeWeakness(password) + "）");
            } else {
                detail.put(hostPort, "已设置强密码");
            }
        }

        // 全部读不到就是数据不足，不能当作"没问题"
        if (unreachable.size() == targets.size()) {
            return insufficient("所有节点的配置都读取失败，无法判定密码强度");
        }

        DimensionResult result;
        if (!noPassword.isEmpty()) {
            // 无密码等于任何能连通网络的人都可直接读写/FLUSHALL，按最高级别报
            result = DimensionResult.of(dimension(), RiskLevel.SEVERE,
                    String.format("%d 个节点未设置访问密码：%s", noPassword.size(), join(noPassword)));
            result.setSuggestion("为实例配置 requirepass（12 位以上，含大小写字母与数字），"
                    + "并在平台「集群密码修改」中同步更新");
        } else if (!weakPassword.isEmpty()) {
            result = DimensionResult.of(dimension(), RiskLevel.ATTENTION,
                    String.format("%d 个节点密码强度不足：%s", weakPassword.size(), join(weakPassword)));
            result.setSuggestion("将密码改为 12 位以上且同时包含大写字母、小写字母与数字");
        } else {
            result = normal("全部节点均已设置强密码");
        }
        result.setActualValue((double) (noPassword.size() + weakPassword.size()));
        result.setSampleCount(targets.size());
        result.evidence("instances", detail);
        if (!unreachable.isEmpty()) {
            // 部分读不到时明确标注，避免把"没检查到"读成"没问题"
            result.evidence("unreachable", unreachable);
        }
        return result;
    }

    /** 12 位以上，且同时含大写、小写、数字 */
    private boolean isStrong(String password) {
        if (password.length() < MIN_LENGTH) {
            return false;
        }
        return hasUpper(password) && hasLower(password) && hasDigit(password);
    }

    private String describeWeakness(String password) {
        List<String> reasons = new ArrayList<>();
        if (password.length() < MIN_LENGTH) {
            reasons.add("不足 " + MIN_LENGTH + " 位");
        }
        if (!hasUpper(password)) {
            reasons.add("缺大写字母");
        }
        if (!hasLower(password)) {
            reasons.add("缺小写字母");
        }
        if (!hasDigit(password)) {
            reasons.add("缺数字");
        }
        return StringUtils.join(reasons, "、");
    }

    private boolean hasUpper(String s) {
        for (char c : s.toCharArray()) {
            if (c >= 'A' && c <= 'Z') return true;
        }
        return false;
    }

    private boolean hasLower(String s) {
        for (char c : s.toCharArray()) {
            if (c >= 'a' && c <= 'z') return true;
        }
        return false;
    }

    private boolean hasDigit(String s) {
        for (char c : s.toCharArray()) {
            if (c >= '0' && c <= '9') return true;
        }
        return false;
    }

    private List<InstanceInfo> dataInstances(RiskAssessContext context) {
        List<InstanceInfo> list = new ArrayList<>();
        if (context.getInstances() == null) {
            return list;
        }
        for (InstanceInfo instance : context.getInstances()) {
            // 哨兵有独立的 sentinel 密码体系，不在本项判定范围内
            if (instance.getType() == ConstUtils.CACHE_REDIS_SENTINEL) {
                continue;
            }
            list.add(instance);
        }
        return list;
    }

    private String join(List<String> items) {
        if (items.size() <= 3) {
            return StringUtils.join(items, "、");
        }
        return StringUtils.join(items.subList(0, 3), "、") + " 等 " + items.size() + " 个";
    }
}
