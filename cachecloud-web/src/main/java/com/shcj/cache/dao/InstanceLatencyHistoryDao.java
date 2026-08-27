package com.shcj.cache.dao;

import com.shcj.cache.entity.AppClientStatisticGather;
import com.shcj.cache.entity.InstanceLatencyHistory;
import org.apache.ibatis.annotations.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Date;

/**
 * @Author: rucao
 * @Date: 2020/5/7 4:57 下午
 */
@Repository
public interface InstanceLatencyHistoryDao {
    int batchSave(List<InstanceLatencyHistory> instanceLatencyHistoryList);

    /**
     * 按事件取该实例已入库的最新一条延迟记录时间，用于采集侧去重。
     *
     * <p>各事件的 LATENCY HISTORY 是相互独立的环形缓冲，取全实例的最大值会把
     * 低频事件的历史样本整段丢掉，所以必须按 event 分组。</p>
     */
    List<Map<String, Object>> getMaxExecuteDateGroupByEvent(@Param("instanceId") long instanceId);

    List<Map<String, Object>> getAppLatencyStats(@Param("appId") long appId, @Param("startTime") long startTime, @Param("endTime") long endTime);

    int getAppLatencyStatsCount(@Param("appId") long appId, @Param("startTime") long startTime, @Param("endTime") long endTime);

    List<Map<String, Object>> getAppLatencyStatsGroupByInstance(@Param("appId") long appId, @Param("startTime") long startTime, @Param("endTime") long endTime);

    List<Map<String, Object>> getAppLatencyInfo(@Param("appId") long appId, @Param("startTime") long startTime, @Param("endTime") long endTime, @Param("event") String event);

    List<InstanceLatencyHistory> getAppLatencyHistoryByRange(@Param("appId") long appId,
            @Param("startDate") Date startDate, @Param("endDate") Date endDate);

    List<AppClientStatisticGather> getAppLatencyCountStat(@Param("startTime") long startTime, @Param("endTime") long endTime);
}
