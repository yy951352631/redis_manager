package com.shcj.cache.redis;

import redis.clients.jedis.Tuple;
import redis.clients.jedis.params.SetParams;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * CacheCloud 内部使用的辅助redis
 */
public interface AssistRedisService {

    boolean reloadSentinel();

    <T> boolean set(String key, T value);

    <T> boolean set(String key, T value, int timeout);

    boolean setNx(String key, String value) ;

    String set(String key, String value, SetParams params) ;

    <T> boolean setWithNoSerialize(String key, T value);

    String getWithNoSerialize(String key);

    <T> boolean setWithNoSerialize(String key, T value, int seconds);

    boolean remove(String key);

    <T> T get(String key);

    boolean rpush(String key, String item);

    boolean rpushList(String key, List<String> items);

    boolean saddSet(String key, Set<String> items);

    boolean sadd(String key, String item);

    Set<String> smembers(String key);

    boolean srem(String key, String item);

    List<String> lrange(String key, int start, int end);

    Long llen(final String key);

    String lpop(final String key);

    boolean zadd(String key, long score, String member);

    boolean hset(String key, String field, String value);

    boolean hmset(String key, Map<String, String> map);

    Map<String, String> hgetAll(String key);

    boolean del(String key);

    void zincrby(String key, double score, String member);

    /**
     * 检测辅助 Redis 是否可读写（含 zset 写入）
     */
    String getAssistRedisEndpoint();

    boolean pingAssistRedis();

    boolean hasZsetData(String key);

    /**
     * 删除键值分析统计 key 中类型非 zset 的脏数据（WRONGTYPE）
     */
    void repairKeyAnalysisStatKeys(long appId, long auditId);

    void clearKeyAnalysisRisks(long appId, long auditId);

    /**
     * 删除某次键值分析在辅助 Redis 上的全部统计 key（含风险列表）
     */
    int clearKeyAnalysisStats(long appId, long auditId);

    /**
     * 删除某应用在辅助 Redis 上所有键值分析相关 key（cc:key:*:{appId}:*）
     */
    int clearKeyAnalysisStatsForApp(long appId);

    void appendKeyAnalysisRisk(long appId, long auditId, String message);

    List<String> getKeyAnalysisRisks(long appId, long auditId);

    Set<Tuple> zrangeWithScores(String key, long start, long end);

    Set<Tuple> zrevrangeWithScores(String key, long start, long end);

    boolean exists(String key);

}
