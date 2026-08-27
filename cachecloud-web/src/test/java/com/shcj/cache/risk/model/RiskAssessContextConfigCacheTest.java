package com.shcj.cache.risk.model;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * 密码强度和持久化两个维度读的是同一份 CONFIG GET *，这里锁住「每个实例每次评估只取一次」。
 */
public class RiskAssessContextConfigCacheTest {

    private RiskAssessContext contextWith(final AtomicInteger calls, final Map<String, String> value) {
        RiskAssessContext context = new RiskAssessContext();
        context.setConfigLoader(instanceId -> {
            calls.incrementAndGet();
            return value;
        });
        return context;
    }

    @Test
    public void 同一个实例被读两次只发一趟命令() {
        AtomicInteger calls = new AtomicInteger();
        Map<String, String> config = new HashMap<>();
        config.put("requirepass", "");
        RiskAssessContext context = contextWith(calls, config);

        assertSame(config, context.getInstanceConfig(7));
        assertSame(config, context.getInstanceConfig(7));

        assertEquals(1, calls.get());
    }

    @Test
    public void 不同实例各取一次() {
        AtomicInteger calls = new AtomicInteger();
        RiskAssessContext context = contextWith(calls, new HashMap<>());

        context.getInstanceConfig(1);
        context.getInstanceConfig(2);
        context.getInstanceConfig(1);

        assertEquals(2, calls.get());
    }

    @Test
    public void 读取失败也只探测一次() {
        AtomicInteger calls = new AtomicInteger();
        RiskAssessContext context = new RiskAssessContext();
        context.setConfigLoader(instanceId -> {
            calls.incrementAndGet();
            throw new IllegalStateException("connect timeout");
        });

        assertNull(context.getInstanceConfig(3));
        assertNull(context.getInstanceConfig(3));

        // 不可达的节点不该被第二个维度再等一次连接超时
        assertEquals(1, calls.get());
    }

    @Test
    public void 没有注入加载器时返回空而不是抛异常() {
        RiskAssessContext context = new RiskAssessContext();
        assertNull(context.getInstanceConfig(1));
    }
}
