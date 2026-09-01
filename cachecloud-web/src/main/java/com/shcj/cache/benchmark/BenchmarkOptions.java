package com.shcj.cache.benchmark;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** 一次压测的全部参数，与结果一起落库作为快照 */
@Data
public class BenchmarkOptions {

    /** 目标集群 */
    private long appId;

    /** 定向压测的节点 hostPort；为空表示压整集群 */
    private List<String> targetNodes = new ArrayList<>();

    /** 勾选的命令名 */
    private List<String> commands = new ArrayList<>();

    private int concurrency = 50;

    private int keySpace = 10000;

    /** value 字节数。redis-benchmark 默认 3 字节，过于失真，这里取 128 */
    private int valueSize = 128;

    private int ttlSeconds = 300;

    /** 终止条件：压测时长（秒）。与 totalRequests 二选一，两者都给时以先到者为准 */
    private int durationSeconds = 60;

    /** 终止条件：总请求数。0 表示不按请求数终止 */
    private long totalRequests = 0L;

    private int pipeline = 1;

    /** true = 热点分布（约两成 key 承担八成访问），false = 均匀随机 */
    private boolean hotspot = false;

    /** 读写混合比例，如 8:2；未勾选读或写命令时该侧自动置 0 */
    private int readWeight = 1;

    private int writeWeight = 1;

    /** 结束后清理压测数据 */
    private boolean cleanup = true;
}
