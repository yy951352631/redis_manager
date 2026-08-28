package com.shcj.cache.entity;

import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 平台变更操作审计记录：一条记录对应一次写操作请求。
 */
@Data
public class OperationAudit implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    /** 操作人（未登录时为空） */
    private String userName;

    /** 操作分类，取自请求路径，便于按业务域筛选，如 apps / instances / users */
    private String module;

    /** HTTP 方法 */
    private String httpMethod;

    /** 请求路径（不含 query） */
    private String requestUri;

    /** 处理该请求的 Controller#方法 */
    private String handler;

    /** 涉及的应用 id（能识别时填充） */
    private Long appId;

    /** 涉及的实例 id（能识别时填充） */
    private Long instanceId;

    /**
     * 操作对象，在请求处理时就固化下来。
     *
     * <p>不能等到展示时再回查：删除类操作会把被引用的记录一并带走，回查得到的
     * 只会是「已删除」。审计要能独立于其他数据存在，当时是什么就永远是什么。</p>
     */
    private String objectLabel;

    /** 请求参数，敏感字段已脱敏，超长会截断 */
    private String params;

    /** 客户端 IP */
    private String clientIp;

    /** HTTP 响应码 */
    private Integer statusCode;

    /** 是否成功 */
    private Integer success;

    /** 失败原因 */
    private String errorMsg;

    /** 耗时(ms) */
    private Long costMs;

    private Date createTime;
}
