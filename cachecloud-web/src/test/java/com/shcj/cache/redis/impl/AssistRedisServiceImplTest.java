package com.shcj.cache.redis.impl;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class AssistRedisServiceImplTest {

    @Test
    public void shouldParseAndDeduplicateSentinelNodes() {
        Set<String> nodes = AssistRedisServiceImpl.parseSentinelNodes(
                " sentinel-1:26379, sentinel-2:26379, sentinel-1:26379 ");

        assertEquals(new LinkedHashSet<>(Arrays.asList(
                "sentinel-1:26379", "sentinel-2:26379")), nodes);
    }

    @Test
    public void shouldRejectInvalidSentinelPort() {
        assertThrows(IllegalArgumentException.class,
                () -> AssistRedisServiceImpl.parseSentinelNodes("sentinel-1:70000"));
    }

    @Test
    public void shouldRejectEmptySentinelNodes() {
        assertThrows(IllegalArgumentException.class,
                () -> AssistRedisServiceImpl.parseSentinelNodes(" , "));
    }

    @Test
    public void shouldRejectPartialSentinelConfiguration() {
        AssistRedisServiceImpl service = new AssistRedisServiceImpl();
        ReflectionTestUtils.setField(service, "sentinelMaster", "cachecloud-master");
        ReflectionTestUtils.setField(service, "sentinelNodes", "");

        assertThrows(IllegalArgumentException.class, service::init);
    }
}
