package com.shcj.cache.util;

import com.google.common.collect.Sets;
import org.springframework.core.env.Environment;

import java.util.Set;

/**
 * Created by yijunzhang
 */
public class EnvUtil {

    /** 报警/巡检作业开关；留空表示按环境自动判定 */
    private static final String ALERT_ENABLED_KEY = "cachecloud.alert.enabled";

    public static Set<String> getProfiles(Environment environment) {
        return Sets.newHashSet(environment.getActiveProfiles());
    }

    public static boolean isOnline(Environment environment) {
        return getProfiles(environment).contains("online");
    }

    public static boolean isDev(Environment environment) {
        Set<String> profiles = getProfiles(environment);
        return profiles.contains("test") || profiles.contains("test-redis") || profiles.contains("local") || profiles.contains("local-redis");
    }

    public static boolean isLocal(Environment environment) {
        Set<String> profiles = getProfiles(environment);
        return profiles.contains("local") || profiles.contains("local-redis");
    }

    public static boolean isTest(Environment environment) {
        Set<String> profiles = getProfiles(environment);
        return profiles.contains("test") || profiles.contains("test-redis");
    }

    /**
     * 报警与巡检类作业是否执行。
     *
     * <p>默认沿用历史行为：dev（test/local）环境不跑，其余环境跑。配置
     * {@code cachecloud.alert.enabled} 可显式覆盖，用于在测试环境验证整条报警链路，
     * 或在生产临时静默报警。</p>
     */
    public static boolean isAlertEnabled(Environment environment) {
        String explicit = environment.getProperty(ALERT_ENABLED_KEY);
        if (explicit != null && !explicit.trim().isEmpty()) {
            return Boolean.parseBoolean(explicit.trim());
        }
        return !isDev(environment);
    }

    public static boolean isOpen(Environment environment) {
        Set<String> profiles = getProfiles(environment);
        return profiles.contains("open") || profiles.contains("open-redis");
    }

}
