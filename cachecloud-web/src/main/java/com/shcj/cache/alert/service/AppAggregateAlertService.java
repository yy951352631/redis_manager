package com.shcj.cache.alert.service;

import com.shcj.cache.alert.impl.BaseAlertService;
import com.shcj.cache.alert.strategy.AlertCompareUtil;
import com.shcj.cache.alert.vo.KafkaAlertVO;
import com.shcj.cache.dao.AppDao;
import com.shcj.cache.entity.AppDesc;
import com.shcj.cache.entity.InstanceAlertConfig;
import com.shcj.cache.entity.InstanceInfo;
import com.shcj.cache.entity.InstanceStats;
import com.shcj.cache.redis.enums.InstanceAlertTypeEnum;
import com.shcj.cache.redis.enums.RedisAlertConfigEnum;
import com.shcj.cache.stats.app.AppStatsCenter;
import com.shcj.cache.stats.instance.InstanceStatsCenter;
import com.shcj.cache.util.TypeUtil;
import com.shcj.cache.web.enums.AlertTypeEnum;
import com.shcj.cache.web.util.DateUtil;
import com.shcj.cache.web.vo.AppDetailVO;
import org.apache.commons.collections.CollectionUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 按集群遍历评估的报警项。
 *
 * <p>内存使用率、客户端连接数、平均命中率原先由 {@code AppMemInspector} /
 * {@code AppClientConnInspector} / {@code AppHitPrecentInspector} 每 20 分钟巡检，
 * 阈值散落在 {@code app_desc} 的三个列里。现在阈值统一落到 instance_alert_configs，
 * 由分钟报警作业按同一套周期与比较语义驱动。</p>
 *
 * <p>内存使用率与客户端连接数只按<b>节点</b>判定（取值来自 instance_stats）；集群整体口径
 * 已去掉——集群级超限必然伴随某个节点超限，两层一起报只会让同一件事重复告警。
 * 平均命中率是真正的跨实例聚合值，仍按集群评估。</p>
 */
@Service
public class AppAggregateAlertService extends BaseAlertService {

    @Autowired
    private AppDao appDao;

    @Autowired
    private AppStatsCenter appStatsCenter;

    @Autowired
    private InstanceStatsCenter instanceStatsCenter;

    /**
     * 评估全部集群下的节点级/集群级报警项。
     *
     * @param aggregateConfigs 作用域为 APP_SCOPED 的报警配置（全局 + 按集群覆盖）
     * @param allInstanceList  全部节点，用于按集群分组
     * @return 本轮产生的报警条数
     */
    public int check(List<InstanceAlertConfig> aggregateConfigs, List<InstanceInfo> allInstanceList) {
        if (CollectionUtils.isEmpty(aggregateConfigs) || CollectionUtils.isEmpty(allInstanceList)) {
            return 0;
        }
        Map<String, InstanceAlertConfig> globalConfigs = new HashMap<>();
        // key = alertConfig + "#" + appId
        Map<String, InstanceAlertConfig> appConfigs = new HashMap<>();
        for (InstanceAlertConfig config : aggregateConfigs) {
            if (InstanceAlertTypeEnum.APP_ALERT.getValue() == config.getType()) {
                appConfigs.put(config.getAlertConfig() + "#" + config.getInstanceId(), config);
            } else {
                globalConfigs.put(config.getAlertConfig(), config);
            }
        }

        Map<Long, List<InstanceInfo>> instancesByApp = new HashMap<>();
        for (InstanceInfo info : allInstanceList) {
            instancesByApp.computeIfAbsent(info.getAppId(), k -> new ArrayList<>()).add(info);
        }

        int alerts = 0;
        for (Map.Entry<Long, List<InstanceInfo>> entry : instancesByApp.entrySet()) {
            long appId = entry.getKey();
            AppDesc appDesc = appDao.getAppDescById(appId);
            // 测试集群与已下线集群不参与聚合报警，沿用原巡检器的口径
            if (appDesc == null || appDesc.getIsTest() == 1 || appDesc.isOffline()) {
                continue;
            }
            AppDetailVO appDetail = appStatsCenter.getAppDetail(appId);
            if (appDetail == null) {
                continue;
            }
            alerts += checkNodeMemUsedRatio(resolve(globalConfigs, appConfigs, RedisAlertConfigEnum.node_mem_used_ratio, appId),
                    appDetail, entry.getValue());
            alerts += checkNodeClientConn(resolve(globalConfigs, appConfigs, RedisAlertConfigEnum.node_client_conn, appId),
                    appDetail, entry.getValue());
            alerts += checkHitPercent(resolve(globalConfigs, appConfigs, RedisAlertConfigEnum.app_hit_percent, appId),
                    appDetail);
        }
        return alerts;
    }

    /** 集群维度的配置优先于全局配置 */
    private InstanceAlertConfig resolve(Map<String, InstanceAlertConfig> globalConfigs,
                                        Map<String, InstanceAlertConfig> appConfigs,
                                        RedisAlertConfigEnum configEnum, long appId) {
        InstanceAlertConfig appConfig = appConfigs.get(configEnum.getValue() + "#" + appId);
        return appConfig != null ? appConfig : globalConfigs.get(configEnum.getValue());
    }

    /**
     * 节点内存使用率：逐节点判定，不再看集群整体口径。
     */
    private int checkNodeMemUsedRatio(InstanceAlertConfig config, AppDetailVO appDetail, List<InstanceInfo> instances) {
        if (config == null) {
            return 0;
        }
        AppDesc appDesc = appDetail.getAppDesc();
        int alerts = 0;
        for (InstanceStats stats : nodeStats(instances)) {
            if (AlertCompareUtil.isTriggered(config, stats.getMemUsePercent())) {
                String content = String.format("节点(%s:%s，集群 %s-%s)内存使用率 %.2f%%，%s预设值 %s%%，请及时关注",
                        stats.getIp(), stats.getPort(), appDesc.getAppId(), appDesc.getName(),
                        stats.getMemUsePercent(), config.getCompareInfo(), config.getAlertValue());
                emit(config, AlertTypeEnum.APP_SHARD_MEM_USED_RATIO, "Redis统一管理平台-节点内存使用率报警", content,
                        appDesc, appDetail, stats);
                alerts++;
            }
        }
        return alerts;
    }

    /**
     * 节点客户端连接数：逐节点判定，阈值即单节点连接数。
     */
    private int checkNodeClientConn(InstanceAlertConfig config, AppDetailVO appDetail, List<InstanceInfo> instances) {
        if (config == null) {
            return 0;
        }
        AppDesc appDesc = appDetail.getAppDesc();
        int alerts = 0;
        for (InstanceStats stats : nodeStats(instances)) {
            if (AlertCompareUtil.isTriggered(config, stats.getCurrConnections())) {
                String content = String.format("节点(%s:%s，集群 %s-%s)客户端连接数 %s，%s预设值 %s，请及时关注",
                        stats.getIp(), stats.getPort(), appDesc.getAppId(), appDesc.getName(),
                        stats.getCurrConnections(), config.getCompareInfo(), config.getAlertValue());
                emit(config, AlertTypeEnum.APP_SHARD_CLENT_CONNECTION, "Redis统一管理平台-节点客户端连接数报警", content,
                        appDesc, appDetail, stats);
                alerts++;
            }
        }
        return alerts;
    }

    private int checkHitPercent(InstanceAlertConfig config, AppDetailVO appDetail) {
        if (config == null) {
            return 0;
        }
        AppDesc appDesc = appDetail.getAppDesc();
        if (!AlertCompareUtil.isTriggered(config, appDetail.getHitPercent())) {
            return 0;
        }
        String content = String.format("集群(%s-%s)平均命中率 %.2f%%，%s预设值 %s%%，请及时关注",
                appDesc.getAppId(), appDesc.getName(), appDetail.getHitPercent(),
                config.getCompareInfo(), config.getAlertValue());
        emit(config, AlertTypeEnum.APP_HIT_RATIO, "Redis统一管理平台-集群平均命中率报警", content, appDesc, appDetail, null);
        return 1;
    }

    /** 只统计参与业务的 Redis 节点：跳过已下线、非 Redis 类型和 sentinel 观察者 */
    private List<InstanceStats> nodeStats(List<InstanceInfo> instances) {
        List<InstanceStats> statsList = new ArrayList<>();
        for (InstanceInfo instanceInfo : instances) {
            if (instanceInfo == null || instanceInfo.isOffline()) {
                continue;
            }
            if (!TypeUtil.isRedisType(instanceInfo.getType()) || TypeUtil.isRedisSentinel(instanceInfo.getType())) {
                continue;
            }
            InstanceStats stats = instanceStatsCenter.getInstanceStats(instanceInfo.getId());
            if (stats != null) {
                statsList.add(stats);
            }
        }
        return statsList;
    }

    private void emit(InstanceAlertConfig config, AlertTypeEnum type, String title, String content,
                      AppDesc appDesc, AppDetailVO appDetail, InstanceStats stats) {
        logger.warn("{}: {}", title, content);
        // 重要程度以报警配置为准，而不是 AlertTypeEnum 里写死的默认值
        int level = config.getImportantLevel() == null ? type.getImportantLevel() : config.getImportantLevel();
        if (stats == null) {
            appAlertRecordService.saveAlertInfoByTypeWithLevel(type, level, title, content, appDetail);
        } else {
            appAlertRecordService.saveAlertInfoByTypeWithLevel(type, level, title, content, appDetail, stats);
        }
        KafkaAlertVO kafkaAlertVO = new KafkaAlertVO();
        kafkaAlertVO.setContent(content);
        kafkaAlertVO.setSeverity(KafkaAlertVO.WARN);
        kafkaAlertVO.setEventTime(DateUtil.formatYYYYMMddHHMMSS(new Date()));
        kafkaAlertVO.setObject(stats == null ? appDesc.getName() : stats.getIp() + ":" + stats.getPort());
        kafkaAlertVO.setLocation("Redis管理平台");
        kafkaAlertVO.setTitle(title);
        kafkaAlertVO.setHost(stats == null ? "" : stats.getIp());
        kafkaAlertVO.setMergeKey(content);
        kafkaAlertVO.setBusiness(appDesc.getIntro());
        sendKafkaAlert(kafkaAlertVO);
        weChatComponent.sendWeChatToAll(title, content, appDetail.getWeChatList());
    }
}
