package com.shcj.cache.web.controller.api.dto;

import lombok.Data;

@Data
public class KeyAnalysisAuditItemDto {
    private long id;
    private int status;
    private String statusDesc;
    private long taskId;
    private String info;
    private String userName;
    private String createTime;
    private String refuseReason;
    private String nodeInfo;
    private long totalKeyCount = -1;
    private String riskInfo;
    private boolean running;
    private boolean passed;
    private boolean rejected;
}
