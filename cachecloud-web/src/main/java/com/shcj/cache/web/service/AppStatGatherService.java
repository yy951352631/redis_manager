package com.shcj.cache.web.service;

import com.shcj.cache.dao.AppClientStatisticGatherDao;
import com.shcj.cache.dao.AppStatsDao;
import com.shcj.cache.dao.InstanceLatencyHistoryDao;
import com.shcj.cache.dao.InstanceSlowLogDao;
import com.shcj.cache.entity.AppClientStatisticGather;
import com.shcj.cache.stats.app.AppStatsCenter;
import org.apache.commons.collections.CollectionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 把慢日志数、延迟事件数、应用统计、连接数按应用聚合进 {@code app_client_statistic_gather}，
 * 供「服务端统计」页与日报使用。
 *
 * <p>本服务由原 {@code AppClientStatisticGatherServiceImpl} 拆分而来：那个类同时做两件事——
 * 聚合客户端 SDK 上报数据（上报入口已下线，数据源不复存在），以及聚合上面这四项<b>仍在持续产生</b>
 * 的平台侧数据。下线上报能力时整个类被一并删除，导致服务端统计页的「慢日志数 / 连接数」
 * 恒为 0。这里只保留后者。
 *
 * <p>每个数据源单独 try：任何一项失败不影响其余项，与原实现一致。
 */
@Service
public class AppStatGatherService {

    private static final Logger LOGGER = LoggerFactory.getLogger(AppStatGatherService.class);

    @Autowired
    private AppStatsDao appStatsDao;

    @Autowired
    private InstanceSlowLogDao instanceSlowLogDao;

    @Autowired
    private InstanceLatencyHistoryDao instanceLatencyHistoryDao;

    @Autowired
    private AppClientStatisticGatherDao appClientStatisticGatherDao;

    @Autowired
    private AppStatsCenter appStatsCenter;

    /**
     * 增量聚合：用于分钟级周期任务，同一天内多次执行按累加合并。
     */
    public void gatherIncrement(long startTime, long endTime) {
        gather(startTime, endTime, true);
    }

    /**
     * 覆盖聚合：用于每日补偿，重算整段区间并覆盖当天的值。
     */
    public void gatherOverwrite(long startTime, long endTime) {
        gather(startTime, endTime, false);
    }

    private void gather(long startTime, long endTime, boolean increment) {
        try {
            List<AppClientStatisticGather> slowLogCounts =
                    instanceSlowLogDao.getAppSlowLogCountStat(startTime, endTime);
            if (CollectionUtils.isNotEmpty(slowLogCounts)) {
                if (increment) {
                    appClientStatisticGatherDao.batchAddSlowLogCount(slowLogCounts);
                } else {
                    appClientStatisticGatherDao.batchSaveSlowLogCount(slowLogCounts);
                }
            }
        } catch (Exception e) {
            LOGGER.error("gather slow log count failed: {}", e.getMessage(), e);
        }

        try {
            List<AppClientStatisticGather> latencyCounts =
                    instanceLatencyHistoryDao.getAppLatencyCountStat(startTime, endTime);
            if (CollectionUtils.isNotEmpty(latencyCounts)) {
                if (increment) {
                    appClientStatisticGatherDao.batchAddLatencyCount(latencyCounts);
                } else {
                    appClientStatisticGatherDao.batchSaveLatencyCount(latencyCounts);
                }
            }
        } catch (Exception e) {
            LOGGER.error("gather latency count failed: {}", e.getMessage(), e);
        }

        try {
            List<AppClientStatisticGather> appStats = appStatsDao.gatherAppsStats(startTime, endTime);
            if (CollectionUtils.isNotEmpty(appStats)) {
                // 应用统计本身就是按区间重算的聚合值，DAO 只提供覆盖写，增量/覆盖两种模式一致
                appClientStatisticGatherDao.batchSaveAppStats(appStats);
            }
        } catch (Exception e) {
            LOGGER.error("gather app stats failed: {}", e.getMessage(), e);
        }

        try {
            // 连接数是当前时刻的快照，不存在增量语义，始终覆盖
            List<AppClientStatisticGather> connClients = appStatsCenter.getOnlineAppConnClients();
            if (CollectionUtils.isNotEmpty(connClients)) {
                appClientStatisticGatherDao.batchSaveConnClients(connClients);
            }
        } catch (Exception e) {
            LOGGER.error("gather connected clients failed: {}", e.getMessage(), e);
        }
    }
}
