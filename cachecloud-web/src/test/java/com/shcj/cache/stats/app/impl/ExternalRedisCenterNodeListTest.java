package com.shcj.cache.stats.app.impl;

import com.shcj.cache.constant.InstanceStatusEnum;
import com.shcj.cache.dao.ExternalRedisDao;
import com.shcj.cache.dao.InstanceDao;
import com.shcj.cache.dao.InstanceStatsDao;
import com.shcj.cache.entity.AppDesc;
import com.shcj.cache.entity.ExternalRedis;
import com.shcj.cache.entity.InstanceInfo;
import com.shcj.cache.redis.RedisCenter;
import com.shcj.cache.util.ConstUtils;
import com.shcj.cache.web.service.AppService;
import com.shcj.cache.web.vo.ExternalNodeVO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class ExternalRedisCenterNodeListTest {

    private static final long APP_ID = 7L;

    @Mock
    private ExternalRedisDao externalRedisDao;
    @Mock
    private InstanceDao instanceDao;
    @Mock
    private InstanceStatsDao instanceStatsDao;
    @Mock
    private RedisCenter redisCenter;
    @Mock
    private AppService appService;

    @InjectMocks
    private ExternalRedisCenterImpl center;

    @Test
    public void removedInstanceRemainsSearchableWithoutLiveProbe() {
        ExternalRedis app = new ExternalRedis();
        app.setAppId(APP_ID);
        app.setName("managed-app");
        app.setType(ConstUtils.CACHE_REDIS_STANDALONE);

        AppDesc appDesc = new AppDesc();
        appDesc.setAppId(APP_ID);
        appDesc.setName("managed-app");

        InstanceInfo removed = new InstanceInfo();
        removed.setId(3);
        removed.setAppId(APP_ID);
        removed.setIp("192.0.2.10");
        removed.setPort(6379);
        removed.setType(ConstUtils.CACHE_REDIS_STANDALONE);
        removed.setStatus(InstanceStatusEnum.FORGET_STATUS.getStatus());

        when(externalRedisDao.listAll()).thenReturn(Collections.singletonList(app));
        when(instanceDao.getInstListByAppId(APP_ID)).thenReturn(Collections.singletonList(removed));
        when(appService.getByAppId(APP_ID)).thenReturn(appDesc);

        List<ExternalNodeVO> result = center.listExternalNodes("");

        assertEquals(1, result.size());
        assertEquals(InstanceStatusEnum.FORGET_STATUS.getStatus(), result.get(0).getStatus());
        assertEquals("已下线", result.get(0).getStatusDesc());
        verify(redisCenter, never()).isRun(APP_ID, "192.0.2.10", 6379);
    }
}
