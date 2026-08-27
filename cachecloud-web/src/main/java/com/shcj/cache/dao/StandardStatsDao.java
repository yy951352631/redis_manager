package com.shcj.cache.dao;

import com.shcj.cache.entity.StandardStats;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;

/**
 * 实例统计相关DAO
 */
public interface StandardStatsDao {

    public int mergeStandardStats(StandardStats standardStats);

    public int mergeInstanceMinuteStats(StandardStats standardStats);

    public StandardStats getStandardStats(@Param("collectTime") long collectTime, @Param("ip") String ip,
            @Param("port") int port, @Param("dbType") String dbType);

    public List<StandardStats> getDiffJsonList(@Param("beginTime") long beginTime, @Param("endTime") long endTime,
            @Param("ip") String ip, @Param("port") int port, @Param("dbType") String dbType);

    public int deleteStandardStatsLessCreatedTime(@Param("createdTime") Date createdTime);

    public List<StandardStats> getStandardStatsByCreateTime(@Param("beginTime") Date beginTime,
            @Param("endTime") Date endTime, @Param("dbType") String dbType);

    /**
     * 取单个实例最近的若干条采集快照，按采集时间倒序。
     *
     * <p>集群列表要的是「这个节点的最新两条」，而 getStandardStatsByCreateTime 会把
     * 全平台该时间窗内的行连同 info_json 整片捞回来再在内存里挑——节点数一多就是
     * 每节点几 MB 的无谓反序列化。这里按 uniq_index(ip,port,db_type,collect_time)
     * 精确取数。</p>
     */
    public List<StandardStats> getRecentStandardStats(@Param("ip") String ip, @Param("port") int port,
            @Param("dbType") String dbType, @Param("beginTime") Date beginTime, @Param("limit") int limit);

}
