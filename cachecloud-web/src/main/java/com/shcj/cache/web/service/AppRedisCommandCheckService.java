package com.shcj.cache.web.service;

import com.shcj.cache.entity.AppUser;
import com.shcj.cache.web.vo.*;

import java.util.List;

/**
 * @Author: zengyizhao
 * @DateTime: 2021/9/26 17:24
 * @Description: redis命令检测
 */
public interface AppRedisCommandCheckService {

    RedisCommandCheckResult checkRedisCommand(AppUser appUser, AppRedisCommandCheckVo appRedisCommandCheckVo);

    void saveRedisCommandCheckResult(RedisCommandCheckResult redisCommandCheckResult, AppRedisCommandCheckResult resultList);

    List<RedisCommandCheckResult> getRedisCommandCheckResult();

    AppRedisCommandCheckResult getRedisCommandCheckDetailResult(String uuid);

}
