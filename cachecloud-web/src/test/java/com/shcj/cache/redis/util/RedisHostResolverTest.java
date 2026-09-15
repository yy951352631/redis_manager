package com.shcj.cache.redis.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RedisHostResolverTest {

    @Test
    void mapsLoopbackToConfiguredDockerHost() {
        assertEquals("host.docker.internal",
                RedisHostResolver.resolve("host.docker.internal", "127.0.0.1"));
    }

    @Test
    void leavesOtherManagedHostsUntouched() {
        assertEquals("172.30.0.11", RedisHostResolver.resolve("host.docker.internal", "172.30.0.11"));
    }

    @Test
    void keepsLoopbackWhenNoOverrideIsConfigured() {
        assertEquals("127.0.0.1", RedisHostResolver.resolve("", "127.0.0.1"));
    }
}
