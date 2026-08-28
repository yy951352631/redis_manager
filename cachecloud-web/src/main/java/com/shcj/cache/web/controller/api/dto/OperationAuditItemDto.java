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

    /**
     * 操作对象的展示文案：集群名 / 节点 ip:port / 迁移的「源 → 目标」。
     *
     * <p>由服务端统一算好，界面只管展示——同一件事在主页和审计日志两处出现，
     * 各自拼一遍迟早会拼出两种说法。</p>
     */
    private String objectLabel;
    private String params;
    private String clientIp;
    private Integer statusCode;
    private boolean success;
    private String errorMsg;
    private Long costMs;
    /** yyyy-MM-dd HH:mm:ss */
    private String createTime;
}
