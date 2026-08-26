package com.shcj.cache.entity;

import lombok.Data;

import java.io.Serializable;

/**
 * 实例维度的命令耗时样本。
 *
 * <p>INFO commandstats 给的 {@code usec_per_call} 是实例启动至今的累计均值，对近期劣化不敏感，
 * 因此这里同时落 {@code calls} 与 {@code usec} 的累计值，由消费方对相邻样本做差分得到
 * {@code Δusec/Δcalls}，即该采样区间内的真实平均耗时。
 */
@Data
public class InstanceCommandLatency implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    /** 分钟表为 yyyyMMddHHmm，小时表为 yyyyMMddHH */
    private long collectTime;

    private long appId;
    private long instanceId;
    private String command;

    /** 累计调用次数（分钟表）/ 区间调用次数之和（小时表） */
    private long calls;

    /** 累计耗时微秒（分钟表）/ 区间耗时之和（小时表） */
    private long usec;

    /** 仅小时表：该小时内单个采样点的最大平均耗时，用于保留突发信息 */
    private double maxAvgUsec;
}
