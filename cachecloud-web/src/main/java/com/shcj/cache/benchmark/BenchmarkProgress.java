package com.shcj.cache.benchmark;

import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

/** 运行期进度快照，供界面每秒轮询 */
@Data
public class BenchmarkProgress {

    private long taskId;

    private String status;

    private long elapsedSeconds;

    private long totalRequests;

    private long errorCount;

    /** 最近一秒的 QPS，不是全程平均——曲线要能看出波动 */
    private long currentQps;

    private long avgQps;

    private double p50Ms;

    private double p95Ms;

    private double p99Ms;

    private double maxMs;

    /** 压测期间平台自身的 CPU 占用，用于判断瓶颈是否在压测机这一侧 */
    private double clientCpuPercent;

    private Map<String, Long> errorTypes = new LinkedHashMap<>();
}
