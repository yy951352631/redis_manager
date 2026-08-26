package com.shcj.cache.dao;

import com.shcj.cache.entity.AppClientStatisticGather;
import com.shcj.cache.entity.InstanceSlowLog;

import java.sql.Timestamp;
import java.util.Date;
import java.util.List;
import java.util.Map;

import org.apache.ibatis.annotations.Param;

/**
 * 实例慢查询dao
 *
 * @author leifu
 * @Date 2016年2月22日
 * @Time 下午1:48:43
 */
public interface InstanceSlowLogDao {

    /**
     * 批量报错实例慢查询
     * @param instanceSlowLogList
     */
    int batchSave(List<InstanceSlowLog> instanceSlowLogList);

    /**
     * 按照应用id获取慢查询列表
     * @param appId
     * @return
     */
    List<InstanceSlowLog> getByAppId(@Param("appId") long appId);

    /**
     * 搜索慢查询日志
     */
    List<InstanceSlowLog> search(@Param("appId") long appId, @Param("startDate") Date startDate, @Param("endDate") Date endDate);

    List<InstanceSlowLog> getByInstanceExecuteTime(@Param("instanceId") long instanceId, @Param("executeDate") String executeDate);

    /**
     * 该节点已入库的最新一条慢查询时间；没有记录时返回 null。
     *
     * <p>Redis 的 SLOWLOG 缓冲区在 RESET 之前不会清空，每次采集拿到的都是同一批条目，
     * 用它把已入库的部分过滤掉，避免每分钟重复写同样的行。</p>
     */
    Timestamp getMaxExecuteTimeByInstanceId(@Param("instanceId") long instanceId);

    /**
     *
     * @param appId
     * @param startDate
     * @param endDate
     * @return
     */
    List<Map<String, Object>> getInstanceSlowLogCountMapByAppId(@Param("appId") long appId, @Param("startDate") Date startDate, @Param("endDate") Date endDate);


    /**
     * 获取指定日期慢查询个数
     * @param appId
     * @param startDate
     * @param endDate
     * @return
     */
    int getAppSlowLogCount(@Param("appId") long appId, @Param("startDate") Date startDate, @Param("endDate") Date endDate);

    List<AppClientStatisticGather> getAppSlowLogCountStat(@Param("startTime") long startTime, @Param("endTime") long endTime);

    /**
     * 删除该应用早于指定时刻的慢查询记录，返回删除行数。
     *
     * <p>只清理平台入库的记录，不会去动 Redis 自身的 SLOWLOG 缓冲区。</p>
     */
    int deleteByAppIdBefore(@Param("appId") long appId, @Param("before") Date before);

    /** 主面板慢查询榜：按节点统计窗口内条数，取前 limit 名 */
    List<Map<String, Object>> countByInstanceSince(@Param("since") Date since, @Param("limit") int limit);

    /** 主面板慢命令榜：按命令名聚合窗口内的调用次数与平均耗时 */
    List<Map<String, Object>> topCommandsSince(@Param("since") Date since, @Param("limit") int limit);

    /** 统计该应用早于指定时刻的慢查询记录数，用于清理前给出预估 */
    int countByAppIdBefore(@Param("appId") long appId, @Param("before") Date before);
}
