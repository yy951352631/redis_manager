package com.shcj.cache.web.controller.api.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
public class AppLatencyDto {
    private long appId;
    private String searchDate;
    private boolean latencyDataEmpty;
    private List<LatencyChartSeriesDto> chartSeries = new ArrayList<>();
    private List<LatencyEventDetailDto> latencyEvents = new ArrayList<>();
    private List<LatencyInstanceCountDto> instanceLatencies = new ArrayList<>();
    private List<LatencyInstanceCountDto> instanceSlowLogCounts = new ArrayList<>();
    private List<AppSlowLogItemDto> slowLogs = new ArrayList<>();
    private Map<String, List<AppSlowLogItemDto>> groupedSlowLogs = new LinkedHashMap<>();
    private Map<String, Long> instanceIdByHostPort = new LinkedHashMap<>();
    private List<String> instances = new ArrayList<>();
}
