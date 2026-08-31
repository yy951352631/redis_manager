package com.shcj.cache.dao;

import com.shcj.cache.entity.InstanceRiskMetric;
import org.apache.ibatis.annotations.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

@Repository
public interface InstanceRiskMetricDao {

    int save(InstanceRiskMetric metric);

    /** 取某集群窗口内的全部样本，按实例与时间排序 */
    List<InstanceRiskMetric> listByAppAndRange(@Param("appId") long appId,
                                               @Param("beginTime") long beginTime,
                                               @Param("endTime") long endTime);

    /** 取某集群在窗口内最新一个完整采集点上的全部实例样本，用于倾斜判定 */
    List<InstanceRiskMetric> listLatestSnapshot(@Param("appId") long appId,
                                                @Param("beginTime") long beginTime,
                                                @Param("endTime") long endTime);

    /** 该集群最早的样本时间，用于判断历史积累是否够窗口 */
    Long getEarliestCollectTime(@Param("appId") long appId);

    /**
     * 主面板用：取每个实例在窗口内的最新一条采样。
     *
     * <p>用「窗口内 collect_time 最大的那条」而不是全表最新，避免掉线实例拿着
     * 几小时前的旧值继续参与排行——那会让面板显示一个早已不存在的状态。</p>
     */
    List<InstanceRiskMetric> listLatestInWindow(@Param("sinceCollectTime") long sinceCollectTime);

    /** 主面板用：窗口内各实例的峰值，用于 Top-N 排行（避免被单点毛刺带偏取均值） */
    List<InstanceRiskMetric> listWindowPeak(@Param("sinceCollectTime") long sinceCollectTime);

    /** 主面板趋势图：窗口内全部采样点（按集群聚合前的原始行） */
    List<InstanceRiskMetric> listWindowSeries(@Param("sinceCollectTime") long sinceCollectTime);

    /**
     * 取窗口内每个实例的首尾两条采样，用于把累计计数器换算成窗口增量。
     *
     * <p>keyspace_hits / keyspace_misses 是自实例启动以来的累计值，直接拿最新快照算比值
     * 得到的是「开机至今的平均命中率」——跑久了分母上百万，一分钟的真实流量根本推不动它。
     * 只有末值减首值才是这段窗口里真实发生的命中与穿透。</p>
     */
    List<InstanceRiskMetric> listWindowBoundarySamples(@Param("sinceCollectTime") long sinceCollectTime,
                                                       @Param("untilCollectTime") long untilCollectTime);

    /**
     * 主面板 CPU 榜：窗口内各实例的 CPU 累计耗时增量与真实跨度。
     *
     * <p>used_cpu_sys/user 是进程累计秒数，单点取值没有意义，
     * 必须用「增量 / 时间跨度」才是使用率。</p>
     */
    List<Map<String, Object>> cpuUsageSince(@Param("sinceCollectTime") long sinceCollectTime);

    /**
     * 取某个集群最近两轮采集里各实例的 CPU 累计值与瞬时 ops，
     * 用于算集群 CPU 使用率与集群 QPS。
     *
     * <p>只回两条而不是 max-min：实例重启会让累计计数器归零，max-min 会把重启前的
     * 高值减重启后的低值，算出一个凭空冒出来的巨大增量。两条相减为负才认得出这种情况。</p>
     */
    /**
     * 取某个集群窗口内每个实例的首尾两条命中计数，用于按窗口算集群命中率。
     *
     * <p>只回首尾两条：实例重启会让累计计数器归零，首尾相减为负才认得出这种情况。</p>
     */
    List<Map<String, Object>> hitBoundaryByApp(@Param("appId") long appId,
                                               @Param("sinceCollectTime") long sinceCollectTime);

    List<Map<String, Object>> cpuBoundaryByApp(@Param("appId") long appId,
                                               @Param("sinceCollectTime") long sinceCollectTime);

    int deleteBefore(@Param("collectTime") long collectTime);
}
