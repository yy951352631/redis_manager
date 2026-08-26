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
 * 持久化配置：AOF 与 RDB 是否至少开启一种。
 *
 * <p>两者都关闭时，进程重启或宕机会丢失全部数据，定为关注。</p>
 *
 * <p>RDB 是否开启看 {@code save} 是否为空——INFO 里没有这个信息，
 * 只有 CONFIG GET 能拿到，所以这一项必须读实例配置而不能靠采集表。</p>
 */
@Component
public class PersistenceEvaluator extends AbstractDimensionEvaluator {

    private static final Logger logger = LoggerFactory.getLogger(PersistenceEvaluator.class);

    @Autowired
    private RedisCenter redisCenter;

    @Override
    public RiskDimension dimension() {
        return RiskDimension.PERSISTENCE;
    }

    @Override
    public DimensionResult evaluate(RiskAssessContext context) {
        List<InstanceInfo> targets = dataInstances(context);
        if (targets.isEmpty()) {
            return insufficient("没有可检查的数据节点");
        }

        List<String> none = new ArrayList<>();
        List<String> unreachable = new ArrayList<>();
        Map<String, Object> detail = new LinkedHashMap<>();

        for (InstanceInfo instance : targets) {
            String hostPort = instance.getIp() + ":" + instance.getPort();
            Map<String, String> config;
            try {
                config = redisCenter.getRedisConfigList(instance.getId());
            } catch (Exception e) {
                logger.warn("read persistence config failed {}: {}", hostPort, e.getMessage());
                config = null;
            }
            if (config == null || config.isEmpty()) {
                unreachable.add(hostPort);
                detail.put(hostPort, "读取配置失败");
                continue;
            }
            boolean aof = "yes".equalsIgnoreCase(StringUtils.trimToEmpty(config.get("appendonly")));
            boolean rdb = StringUtils.isNotBlank(config.get("save"));
            if (aof && rdb) {
                detail.put(hostPort, "AOF + RDB");
            } else if (aof) {
                detail.put(hostPort, "仅 AOF");
            } else if (rdb) {
                detail.put(hostPort, "仅 RDB");
            } else {
                none.add(hostPort);
                detail.put(hostPort, "未开启任何持久化");
            }
        }

        if (unreachable.size() == targets.size()) {
            return insufficient("所有节点的配置都读取失败，无法判定持久化状态");
        }

        DimensionResult result;
        if (!none.isEmpty()) {
            result = DimensionResult.of(dimension(), RiskLevel.ATTENTION,
                    String.format("%d 个节点未开启任何持久化：%s", none.size(), join(none)));
            result.setSuggestion("按用途选择：需要快速恢复且可容忍少量丢失用 RDB（save 配置点），"
                    + "要求尽量不丢数据用 AOF（appendonly yes，appendfsync everysec）");
        } else {
            result = normal("全部节点均已开启持久化");
        }
        result.setActualValue((double) none.size());
        result.setSampleCount(targets.size());
        result.evidence("instances", detail);
        if (!unreachable.isEmpty()) {
            result.evidence("unreachable", unreachable);
        }
        return result;
    }

    private List<InstanceInfo> dataInstances(RiskAssessContext context) {
        List<InstanceInfo> list = new ArrayList<>();
        if (context.getInstances() == null) {
            return list;
        }
        for (InstanceInfo instance : context.getInstances()) {
            // 哨兵不存业务数据，持久化对它没有意义
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
