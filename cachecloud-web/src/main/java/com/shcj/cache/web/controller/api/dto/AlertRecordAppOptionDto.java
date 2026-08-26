package com.shcj.cache.web.controller.api.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 报警记录里出现过的集群，用于按集群名称筛选。
 */
@Data
public class AlertRecordAppOptionDto implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long appId;
    private String appName;

    public AlertRecordAppOptionDto() {
    }

    public AlertRecordAppOptionDto(Long appId, String appName) {
        this.appId = appId;
        this.appName = appName;
    }
}
