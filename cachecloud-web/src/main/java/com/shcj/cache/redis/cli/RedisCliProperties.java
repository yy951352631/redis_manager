package com.shcj.cache.redis.cli;

import com.shcj.cache.util.ConstUtils;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;

/**
 * 目标机上 redis-cli 的路径配置。
 *
 * <p>平台自身执行 Redis 命令一律走 Jedis，不再调用本机 redis-cli；这里只用于
 * SSH 备用通道——当 CacheCloud 无法直连实例端口（网络隔离）时，改为登录目标机
 * 在本地执行 redis-cli。
 */
@Component
public class RedisCliProperties {

    private static final Logger logger = LoggerFactory.getLogger(RedisCliProperties.class);

    /**
     * 经 SSH 在 Redis 机器上执行时 redis-cli 所在目录（不含可执行文件名）。
     */
    @Value("${cachecloud.redis.cli.remote-bin:/home/redis/bin}")
    private String remoteBin;

    @PostConstruct
    public void logResolvedPaths() {
        logger.info("redis-cli config (SSH fallback only): remote={}", getRemoteCliPath());
    }

    public String getRemoteBin() {
        return StringUtils.isNotBlank(remoteBin) ? remoteBin.trim() : ConstUtils.REDIS_DEFAULT_BIN;
    }

    public String getRemoteCliPath() {
        String bin = getRemoteBin();
        if (bin.endsWith("redis-cli")) {
            return bin;
        }
        return bin + "/redis-cli";
    }
}
