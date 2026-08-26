package com.shcj.cache.redis.cli;

import org.apache.commons.lang.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 解析 redis-cli CONFIG 命令输出（--raw 模式）。
 */
public final class RedisCliConfigHelper {

    private RedisCliConfigHelper() {
    }

    /**
     * CONFIG GET 返回多行：key、value 交替。
     */
    public static Map<String, String> parseConfigGet(String output) {
        Map<String, String> configMap = new LinkedHashMap<>();
        if (StringUtils.isBlank(output)) {
            return configMap;
        }
        if (isError(output)) {
            return configMap;
        }
        String[] lines = RedisInfoParser.splitLines(output);
        for (int i = 0; i < lines.length - 1; i += 2) {
            String key = lines[i].trim();
            String value = lines[i + 1].trim();
            if (StringUtils.isBlank(key) || StringUtils.isBlank(value)) {
                continue;
            }
            configMap.put(key, value);
        }
        return configMap;
    }

    public static boolean isOk(String output) {
        String first = firstLine(output);
        return first != null && "OK".equalsIgnoreCase(first);
    }

    public static boolean isError(String output) {
        String first = firstLine(output);
        return first != null && first.startsWith("ERR");
    }

    private static String firstLine(String output) {
        if (StringUtils.isBlank(output)) {
            return null;
        }
        String[] lines = RedisInfoParser.splitLines(output);
        return lines.length > 0 ? lines[0].trim() : null;
    }
}
