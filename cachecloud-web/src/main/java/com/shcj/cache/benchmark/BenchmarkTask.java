package com.shcj.cache.benchmark;

import lombok.Data;

import java.util.Date;

/** 压测任务的落库记录：参数快照 + 汇总结果 */
@Data
public class BenchmarkTask {

    private Long id;

    private long appId;

    private String appName;

    /** 压测目标描述：整集群，或选中的节点列表 */
    private String targetDesc;

    /** 参数快照 JSON。两次压测数字不同时，得能分清是集群变了还是参数变了 */
    private String optionsJson;

    /** RUNNING / FINISHED / STOPPED / FAILED */
    private String status;

    private long totalRequests;

    private long errorCount;

    private long qps;

    private double p50Ms;

    private double p95Ms;

    private double p99Ms;

    private double maxMs;

    private double avgMs;

    /** 压测期间平台自身 CPU，用于判断瓶颈是否在压测机一侧 */
    private double clientCpuPercent;

    /** 逐命令次数与均值 JSON */
    private String commandStatsJson;

    /** 错误分类 JSON */
    private String errorStatsJson;

    private String userName;

    private String errorMsg;

    private Date startTime;

    private Date endTime;
}
