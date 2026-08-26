package com.shcj.cache.web.controller.api.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 报警记录列表项
 */
@Data
public class AlertRecordItemDto implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;
    /** yyyy-MM-dd HH:mm:ss */
    private String createTime;
    /** 0：一般；1：重要；2：紧急 */
    private int importantLevel;
    private String importantLevelDesc;
    private Long appId;
    private String appName;
    private Long instanceId;
    private String ip;
    private Integer port;
    private String title;
    private String content;
}
