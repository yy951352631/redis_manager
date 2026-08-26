package com.shcj.cache.web.controller.api.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 键值分析分布统计快照，任务完成时写入 MySQL，供任意 API 节点读取。
 */
@Data
public class KeyAnalysisStatsSnapshotDto {
    private boolean hasDistributionData;
    private boolean hasTypeMemoryData;
    private boolean topKeyFromMemoryScan;
    private int bigKeyCount;
    private long bigKeyStringBytes = 100 * 1024L;
    private long bigKeyCollectionElements = 50000L;
    private String assistRedisEndpoint;
    private List<String> analysisRisks = new ArrayList<>();
    private List<ParamCountDto> idleKeyDistri = new ArrayList<>();
    private List<ParamCountDto> keyTypeDistri = new ArrayList<>();
    private List<ParamCountDto> keyTtlDistri = new ArrayList<>();
    private List<ParamCountDto> keyValueSizeDistri = new ArrayList<>();
    /**
     * 键值大小极值。分布图只能给出区间，看不出真实的最大/最小值，
     * 因此扫描时直接累计这四个原始量；平均值由 sum/count 得出，而不是用区间中值估算。
     * sampleCount 为 0 表示该来源没有采集到（例如从 assist Redis 反推的快照只有区间计数）。
     */
    private long valueSizeMaxBytes;
    private long valueSizeMinBytes;
    private long valueSizeSumBytes;
    private long valueSizeSampleCount;
    private List<ParamCountDto> keyTypeMemoryDistri = new ArrayList<>();
    private List<KeyAnalysisBigKeyDto> topKeys = new ArrayList<>();
    private List<KeyAnalysisBigKeyDto> bigKeys = new ArrayList<>();
    private List<KeyPrefixStatDto> keyPrefixTop = new ArrayList<>();
}
