package com.shcj.cache.web.service;

import com.shcj.cache.constant.AppStatusEnum;
import com.shcj.cache.constant.InstanceStatusEnum;
import com.shcj.cache.dao.AppDao;
import com.shcj.cache.dao.AppToUserDao;
import com.shcj.cache.dao.ExternalRedisDao;
import com.shcj.cache.dao.InstanceDao;
import com.shcj.cache.entity.AppDesc;
import com.shcj.cache.entity.ExternalRedis;
import com.shcj.cache.entity.InstanceInfo;
import com.shcj.cache.stats.app.AppDeployCenter;
import com.shcj.cache.stats.app.AppStatsCenter;
import com.shcj.cache.stats.app.ExternalRedisCenter;
import com.shcj.cache.web.controller.api.dto.AppListPageDto;
import com.shcj.cache.web.vo.AppDetailVO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AppManageRemovedInstanceStatusTest {

    private static final long APP_ID = 1L;

    @InjectMocks
    private AppManageApiService service;

    @Mock
    private AppService appService;
    @Mock
    private ExternalRedisDao externalRedisDao;
    @Mock
    private ExternalRedisCenter externalRedisCenter;
    @Mock
    private InstanceDao instanceDao;
    @Mock
    private AppDao appDao;
    @Mock
    private AppToUserDao appToUserDao;
    @Mock
    private AppStatsCenter appStatsCenter;
    @Mock
    private AppDeployCenter appDeployCenter;

    @Test
    void removedNodeDoesNotMakeRunningClusterAbnormal() {
        AppDesc app = new AppDesc();
        app.setAppId(APP_ID);
        app.setName("managed redis");
        app.setStatus(AppStatusEnum.STATUS_PUBLISHED.getStatus());

        ExternalRedis externalRedis = new ExternalRedis();
        externalRedis.setAppId(APP_ID);

        InstanceInfo running = instance("cachecloud-redis", 6379,
                InstanceStatusEnum.GOOD_STATUS.getStatus());
        InstanceInfo removed = instance("172.21.0.2", 6379,
                InstanceStatusEnum.FORGET_STATUS.getStatus());

        when(appService.getAppDescCount(any(), any())).thenReturn(1);
        when(appService.getAppDescList(any(), any())).thenReturn(Collections.singletonList(app));
        when(externalRedisDao.listAll()).thenReturn(Collections.singletonList(externalRedis));
        when(instanceDao.getInstListByAppId(APP_ID)).thenReturn(Arrays.asList(running, removed));
        when(appStatsCenter.getAppDetail(APP_ID)).thenReturn(new AppDetailVO());

        AppListPageDto result = service.listApps(null, "", "", -1, 1, 20);

        assertEquals(1, result.getItems().size());
        assertEquals(AppStatusEnum.STATUS_PUBLISHED.getStatus(), result.getItems().get(0).getRuntimeStatus());
        assertTrue(result.getItems().get(0).getRuntimeStatusDetail() == null
                || result.getItems().get(0).getRuntimeStatusDetail().isEmpty());
        verify(externalRedisCenter, never()).listExternalNodes("");
        verify(externalRedisCenter, never()).detectRedisVersionName(externalRedis);
    }

    private InstanceInfo instance(String ip, int port, int status) {
        InstanceInfo instance = new InstanceInfo();
        instance.setAppId(APP_ID);
        instance.setIp(ip);
        instance.setPort(port);
        instance.setStatus(status);
        instance.setType(6);
        return instance;
    }
}
