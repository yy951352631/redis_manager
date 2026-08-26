package com.shcj.cache.redis.cli;

import com.shcj.cache.constant.RedisConstant;
import org.apache.commons.lang.StringUtils;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 解析 redis INFO / INFO ALL 文本为分段 Map，兼容 Jedis 与 redis-cli 换行差异。
 */
public final class RedisInfoParser {

    private RedisInfoParser() {
    }

    public static Map<RedisConstant, Map<String, Object>> parse(String statResult) {
        Map<RedisConstant, Map<String, Object>> redisStatMap = new HashMap<>();
        if (StringUtils.isBlank(statResult)) {
            return redisStatMap;
        }
        String[] data = splitLines(statResult);
        int i = 0;
        int length = data.length;
        while (i < length) {
            if (StringUtils.isBlank(data[i])) {
                i++;
                continue;
            }
            if (data[i].contains("#")) {
                int index = data[i].indexOf('#');
                String key = data[i].substring(index + 1);
                ++i;
                RedisConstant redisConstant = RedisConstant.value(key.trim());
                if (redisConstant == null) {
                    continue;
                }
                Map<String, Object> sectionMap = new LinkedHashMap<>();
                while (i < length && data[i].contains(":")) {
                    int colonIdx = data[i].indexOf(':');
                    if (colonIdx > 0) {
                        sectionMap.put(data[i].substring(0, colonIdx), data[i].substring(colonIdx + 1));
                    }
                    i++;
                }
                redisStatMap.put(redisConstant, sectionMap);
            } else {
                i++;
            }
        }
        return redisStatMap;
    }

    public static String[] splitLines(String statResult) {
        if (StringUtils.isBlank(statResult)) {
            return new String[0];
        }
        String normalized = statResult.replace("\r\n", "\n").replace('\r', '\n');
        return normalized.split("\n");
    }
}
