package com.shcj.cache.stats.app.impl;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Paths;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertTrue;

class EmbeddedRedisShakeServiceTest {

    @Test
    void mapsLoopbackEndpointsInGeneratedConfig() {
        EmbeddedRedisShakeService service = new EmbeddedRedisShakeService();
        ReflectionTestUtils.setField(service, "managedLoopbackHost", "host.docker.internal");

        String config = ReflectionTestUtils.invokeMethod(service, "buildConfig",
                Paths.get("/tmp/task"), Paths.get("/tmp/task/data/shake.log"), 9321,
                "127.0.0.1:7101", "", false,
                "127.0.0.1:7041", "", false, Collections.emptyMap());

        assertTrue(config.contains("address = \"host.docker.internal:7101\""));
        assertTrue(config.contains("address = \"host.docker.internal:7041\""));
    }

    @Test
    void keepsNonLoopbackEndpointsInGeneratedConfig() {
        EmbeddedRedisShakeService service = new EmbeddedRedisShakeService();
        ReflectionTestUtils.setField(service, "managedLoopbackHost", "host.docker.internal");

        String config = ReflectionTestUtils.invokeMethod(service, "buildConfig",
                Paths.get("/tmp/task"), Paths.get("/tmp/task/data/shake.log"), 9321,
                "172.30.0.21:6379", "", false,
                "redis.internal:6379", "", false, Collections.emptyMap());

        assertTrue(config.contains("address = \"172.30.0.21:6379\""));
        assertTrue(config.contains("address = \"redis.internal:6379\""));
    }
}
