package com.shcj.cache.web.controller.api.dto;

import lombok.Data;

/**
 * 离线分析记录列表项。不含结果 JSON——单条可达数 MB，列表不该带上它。
 */
@Data
public class OfflineAnalysisRecordDto {

    private long id;
    private String fileName;
    private long fileSize;
    private String fileSizeLabel;
    private String fileType;
    private int status;
    private String statusDesc;
    private long keyCount;
    private String errorMsg;
    private String userName;
    private String createTime;
    private String updateTime;
}
