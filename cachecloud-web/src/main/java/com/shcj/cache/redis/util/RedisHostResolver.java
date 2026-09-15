package com.shcj.cache.redis.util;

import org.apache.commons.lang.StringUtils;

/** Resolves a managed Redis loopback address for containerized deployments. */
public final class RedisHostResolver {

    private static final String LOOPBACK = "127.0.0.1";

    private RedisHostResolver() {
    }

    public static String resolve(String managedLoopbackHost, String requestedHost) {
        if (!LOOPBACK.equals(StringUtils.trim(requestedHost))) {
            return requestedHost;
        }
        String configuredHost = StringUtils.trimToEmpty(managedLoopbackHost);
        return StringUtils.isBlank(configuredHost) ? LOOPBACK : configuredHost;
    }
}
