package com.shcj.cache.web.service;

import com.shcj.cache.web.controller.api.dto.OnlineHealthCheckResultDto;

/**
 * 在线健康检查（基于 Redis INFO / CLUSTER / SENTINEL，对齐 check_redis_status.py）。
 * 输入格式：每行 ip:port:password。
 */
public interface OnlineVerifyService {

    /**
     * @param servers    多行 ip:port:password
     * @param verifyType 兼容旧参数，当前统一走健康检查
     */
    OnlineHealthCheckResultDto verifyDetailed(String servers, String verifyType);

    /** 兼容旧调用，仅返回纯文本 */
    default String verify(String servers, String verifyType) {
        OnlineHealthCheckResultDto dto = verifyDetailed(servers, verifyType);
        return dto != null ? dto.getResult() : "";
    }
}
