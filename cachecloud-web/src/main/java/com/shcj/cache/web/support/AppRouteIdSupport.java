package com.shcj.cache.web.support;

import com.shcj.cache.exception.BizException;
import com.shcj.cache.web.service.AppService;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

/**
 * 将路由/API 入参（cluster_no 或历史 app_id）解析为内部 app_id。
 */
@Component
public class AppRouteIdSupport {

    @Resource(name = "appService")
    private AppService appService;

    public long requireAppId(long idOrClusterNo) {
        Long appId = appService.resolveAppId(idOrClusterNo);
        if (appId == null) {
            throw new BizException("集群不存在: " + idOrClusterNo);
        }
        return appId;
    }

    public Long resolveAppIdOrNull(long idOrClusterNo) {
        return appService.resolveAppId(idOrClusterNo);
    }

    public long normalizeAppId(long idOrClusterNo) {
        Long appId = appService.resolveAppId(idOrClusterNo);
        return appId != null ? appId : idOrClusterNo;
    }
}
