package com.shcj.cache.web.controller.api.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class KeyAnalysisResultDto {
    private long appId;
    private long auditId;
    private boolean hasDistributionData;
    private boolean emptyDb;
    private boolean statsMissing;
    private boolean hasAnalysisRisk;
    private boolean hasTypeMemoryData;
    private boolean topKeyFromMemoryScan;
    private int bigKeyCount;
    private long bigKeyStringBytes = 100 * 1024L;
    private long bigKeyCollectionElements = 50000L;
    private String assistRedisEndpoint;
    private long totalKeyCount;
    private List<String> analysisRisks = new ArrayList<>();
    private List<String> analysisNodes = new ArrayList<>();
    private List<ParamCountDto> idleKeyDistri = new ArrayList<>();
    private List<ParamCountDto> keyTypeDistri = new ArrayList<>();
    private List<ParamCountDto> keyTtlDistri = new ArrayList<>();
    private List<ParamCountDto> keyValueSizeDistri = new ArrayList<>();
    /** 键值大小极值，见 KeyAnalysisStatsSnapshotDto 同名字段；sampleCount 为 0 时前端不画极值图 */
    private long valueSizeMaxBytes;
    private long valueSizeMinBytes;
    private long valueSizeSumBytes;
    private long valueSizeSampleCount;
    private List<ParamCountDto> keyTypeMemoryDistri = new ArrayList<>();
    private List<KeyAnalysisBigKeyDto> topKeys = new ArrayList<>();
    private List<KeyAnalysisBigKeyDto> bigKeys = new ArrayList<>();
    private List<KeyPrefixStatDto> keyPrefixTop = new ArrayList<>();
}
