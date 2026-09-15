package com.shcj.cache.web.service;

import com.shcj.cache.constant.InstanceStatusEnum;
import com.shcj.cache.entity.AppDesc;
import com.shcj.cache.entity.InstanceInfo;
import com.shcj.cache.util.ConstUtils;
import com.shcj.cache.web.controller.api.dto.AppMachineTopologyDto;
import com.shcj.cache.web.controller.api.dto.AppTopologyDto;
import com.shcj.cache.web.enums.BooleanEnum;
import com.shcj.cache.web.support.AppRouteIdSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class AppDetailTabApiServiceTest {

    private static final long APP_ID = 7L;

    @Mock
    private AppRouteIdSupport appRouteIdSupport;
    @Mock
    private AppService appService;

    @InjectMocks
    private AppDetailTabApiService service;

    @Test
    public void machineTopologyExcludesRemovedInstances() {
        AppDesc app = appDesc();
        InstanceInfo active = instance(1, "redis-active", InstanceStatusEnum.GOOD_STATUS.getStatus());
        InstanceInfo removed = instance(2, "redis-removed", InstanceStatusEnum.FORGET_STATUS.getStatus());

        when(appRouteIdSupport.requireAppId(APP_ID)).thenReturn(APP_ID);
        when(appService.getByAppId(APP_ID)).thenReturn(app);
        when(appService.getAppInstanceInfo(APP_ID))
                .thenReturn(new ArrayList<>(Arrays.asList(active, removed)));

        AppMachineTopologyDto result = service.getMachineTopology(APP_ID);

        assertEquals(1, result.getMachines().size());
        assertEquals("redis-active", result.getMachines().get(0).getIp());
        assertEquals(1, result.getMachines().get(0).getNodes().size());
    }

    @Test
    public void evictTopologyCacheMakesRemovalVisibleImmediately() {
        InstanceInfo beforeRemoval = instance(1, "redis-removed", InstanceStatusEnum.GOOD_STATUS.getStatus());
        InstanceInfo afterRemoval = instance(2, "redis-active", InstanceStatusEnum.GOOD_STATUS.getStatus());

        when(appRouteIdSupport.requireAppId(APP_ID)).thenReturn(APP_ID);
        when(appService.getByAppId(APP_ID)).thenReturn(appDesc());
        when(appService.getAppInstanceStats(APP_ID)).thenReturn(Collections.emptyList());
        when(appService.getAppBasicInstanceInfo(APP_ID))
                .thenReturn(Collections.singletonList(beforeRemoval), Collections.singletonList(afterRemoval));

        AppTopologyDto cached = service.getTopology(APP_ID);
        assertEquals(1, cached.getInstances().get(0).getId());
        assertEquals(1, service.getTopology(APP_ID).getInstances().get(0).getId());

        service.evictTopologyCache(APP_ID);

        assertEquals(2, service.getTopology(APP_ID).getInstances().get(0).getId());
    }

    private AppDesc appDesc() {
        AppDesc app = new AppDesc();
        app.setAppId(APP_ID);
        app.setType(ConstUtils.CACHE_REDIS_STANDALONE);
        return app;
    }

    private InstanceInfo instance(int id, String ip, int status) {
        InstanceInfo instance = new InstanceInfo();
        instance.setId(id);
        instance.setAppId(APP_ID);
        instance.setIp(ip);
        instance.setPort(6379);
        instance.setStatus(status);
        instance.setType(ConstUtils.CACHE_REDIS_STANDALONE);
        instance.setRoleDesc(BooleanEnum.TRUE);
        return instance;
    }
}
