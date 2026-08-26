package com.shcj.cache.entity;

import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 实例运行画像：架构与 Redis 版本。
 *
 * <p>命令耗时基线必须按「架构 × 大版本」分组——同一条 GET 在 ARM 与 X86、5.x 与 7.x 上的
 * 基准耗时并不可比，混在一起算平均只会得到一个谁都不像的数。
 * 采集侧每次都有完整 INFO，顺手落一行比评估时反查所有实例便宜得多。
 */
@Data
public class InstanceRuntimeProfile implements Serializable {

    private static final long serialVersionUID = 1L;

    private long instanceId;
    private long appId;
    private String osArch;
    private String redisVersion;
    private String majorVersion;
    private Date updateTime;
}
