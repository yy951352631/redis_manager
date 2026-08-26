package com.shcj.cache.redis.enums;

/**
 * 报警项的数据来源与作用域。
 *
 * <p>决定分钟报警作业用哪条路径评估该配置项：</p>
 * <ul>
 *   <li>{@link #INSTANCE_INFO} —— 取 standard_statistics 的 info_json / diff_json，逐实例标量比较，
 *       平台绝大多数报警项属于此类；</li>
 *   <li>{@link #INSTANCE_STATE} —— 不依赖 INFO 内容，而是看采集本身成功与否（节点存活探测）；</li>
 *   <li>{@link #APP_SCOPED} —— 需要按集群遍历评估的项：节点级指标取 instance_stats
 *       （内存使用率、客户端连接数），集群级指标取 AppStatsCenter 聚合值（平均命中率）。
 *       两者都无法由单实例 INFO 直接算出，因此不走标量比较那条路径。</li>
 * </ul>
 */
public enum AlertConfigScopeEnum {

    INSTANCE_INFO,

    INSTANCE_STATE,

    APP_SCOPED;

}
