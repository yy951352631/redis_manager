package com.shcj.cache.web.controller.api.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class KeyAnalysisPageDto {
    private long appId;
    private Long runningTaskId;
    private String assistRedisEndpoint;
    private List<KeyAnalysisInstanceDto> instances = new ArrayList<>();
    private List<KeyAnalysisAuditItemDto> audits = new ArrayList<>();
}
