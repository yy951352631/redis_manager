package com.shcj.cache.web.controller.api.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 审计日志列表项
 */
@Data
public class OperationAuditItemDto implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;
    private String userName;
    private String module;
    private String httpMethod;
    private String requestUri;
    private String handler;
    private Long appId;
    /** 集群名称，appId 已删除或查不到时为 null，前端回退显示 appId */
    private String appName;
    private Long instanceId;
    private String params;
    private String clientIp;
    private Integer statusCode;
    private boolean success;
    private String errorMsg;
    private Long costMs;
    /** yyyy-MM-dd HH:mm:ss */
    private String createTime;
}
