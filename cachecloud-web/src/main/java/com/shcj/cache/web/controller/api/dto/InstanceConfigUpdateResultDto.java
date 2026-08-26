package com.shcj.cache.web.controller.api.dto;

import lombok.Data;

import java.io.Serializable;

@Data
public class InstanceConfigUpdateResultDto implements Serializable {
    private static final long serialVersionUID = 1L;

    private long instanceId;
    private String configName;
    private String previousValue;
    private String currentValue;
    private String source;
    private boolean rewriteSuccess;
    /** CONFIG REWRITE 失败原因，成功时为 null */
    private String rewriteError;
    private String message;
}
