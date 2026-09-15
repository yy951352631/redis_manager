package com.shcj.cache.redis.impl;

import com.shcj.cache.constant.InstanceStatusEnum;
import com.shcj.cache.dao.AppDao;
import com.shcj.cache.dao.InstanceDao;
import com.shcj.cache.entity.AppDesc;
import com.shcj.cache.entity.InstanceInfo;
import com.shcj.cache.redis.RedisCenter;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import redis.clients.jedis.Jedis;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisDeployCenterPasswordCheckTest {

    @Test
    void passwordCheckPassesWithoutAuthWhenClusterHasNoPassword() {
        long appId = 1L;
        AppDao appDao = mock(AppDao.class);
        InstanceDao instanceDao = mock(InstanceDao.class);
        RedisCenter redisCenter = mock(RedisCenter.class);
        Jedis jedis = mock(Jedis.class);

        AppDesc app = new AppDesc();
        app.setAppId(appId);

        InstanceInfo instance = new InstanceInfo();
        instance.setAppId(appId);
        instance.setIp("127.0.0.1");
        instance.setPort(6379);
        instance.setType(6);
        instance.setStatus(InstanceStatusEnum.GOOD_STATUS.getStatus());

        when(appDao.getAppDescById(appId)).thenReturn(app);
        when(instanceDao.getInstListByAppId(appId)).thenReturn(Collections.singletonList(instance));
        when(redisCenter.getJedis("127.0.0.1", 6379, null)).thenReturn(jedis);

        RedisDeployCenterImpl service = new RedisDeployCenterImpl();
        ReflectionTestUtils.setField(service, "appDao", appDao);
        ReflectionTestUtils.setField(service, "instanceDao", instanceDao);
        ReflectionTestUtils.setField(service, "redisCenter", redisCenter);

        assertTrue(service.checkAuths(appId));
        verify(jedis, never()).auth(isNull());
    }
}
