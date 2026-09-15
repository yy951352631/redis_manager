package com.shcj.cache.web.service;

import com.shcj.cache.constant.AppAuditType;
import com.shcj.cache.constant.InstanceStatusEnum;
import com.shcj.cache.entity.InstanceInfo;
import com.shcj.cache.redis.AssistRedisService;
import com.shcj.cache.util.ConstUtils;
import com.shcj.cache.web.controller.api.dto.KeyAnalysisPageDto;
import com.shcj.cache.web.support.AppRouteIdSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class KeyAnalysisRemovedInstanceTest {

    private static final long APP_ID = 7L;

    @Mock
    private AppRouteIdSupport appRouteIdSupport;
    @Mock
    private AppService appService;
    @Mock
    private AssistRedisService assistRedisService;

    @InjectMocks
    private KeyAnalysisApiService service;

    @Test
    public void pageExcludesRemovedInstances() {
        InstanceInfo active = instance(1, "redis-active", InstanceStatusEnum.GOOD_STATUS.getStatus());
        InstanceInfo offline = instance(2, "redis-offline", InstanceStatusEnum.OFFLINE_STATUS.getStatus());
        InstanceInfo forgotten = instance(3, "redis-forgotten", InstanceStatusEnum.FORGET_STATUS.getStatus());

        when(appRouteIdSupport.requireAppId(APP_ID)).thenReturn(APP_ID);
        when(appService.getAppAudits(APP_ID, AppAuditType.KEY_ANALYSIS.getValue()))
                .thenReturn(Collections.emptyList());
        when(appService.getAppInstanceStats(APP_ID)).thenReturn(Collections.emptyList());
        when(appService.getAppInstanceInfo(APP_ID)).thenReturn(Arrays.asList(active, offline, forgotten));
        when(assistRedisService.getAssistRedisEndpoint()).thenReturn("redis:6379");

        KeyAnalysisPageDto result = service.getPage(APP_ID);

        assertEquals(1, result.getInstances().size());
        assertEquals("redis-active:6379", result.getInstances().get(0).getHostPort());
    }

    private InstanceInfo instance(int id, String ip, int status) {
        InstanceInfo instance = new InstanceInfo();
        instance.setId(id);
        instance.setAppId(APP_ID);
        instance.setIp(ip);
        instance.setPort(6379);
        instance.setStatus(status);
        instance.setType(ConstUtils.CACHE_REDIS_STANDALONE);
        return instance;
    }
}
