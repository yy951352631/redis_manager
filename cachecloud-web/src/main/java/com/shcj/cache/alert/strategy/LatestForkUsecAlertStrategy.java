package com.shcj.cache.alert.strategy;

import java.util.Arrays;
import java.util.List;

import org.apache.commons.lang.math.NumberUtils;

import com.shcj.cache.alert.bean.AlertConfigBaseData;
import com.shcj.cache.entity.InstanceAlertConfig;
import com.shcj.cache.entity.InstanceAlertValueResult;
import com.shcj.cache.entity.InstanceInfo;
import com.shcj.cache.redis.enums.RedisInfoEnum;

/**
 * 上一次fork的微秒
 * 
 * @author leifu
 * @Date 2017年6月16日
 * @Time 下午2:34:10
 */
public class LatestForkUsecAlertStrategy extends AlertConfigStrategy {
    @Override
    public List<InstanceAlertValueResult> checkConfig(InstanceAlertConfig instanceAlertConfig, AlertConfigBaseData alertConfigBaseData) {
        Object object = getValueFromRedisInfo(alertConfigBaseData.getStandardStats(), RedisInfoEnum.latest_fork_usec.getValue());
        if (object == null) {
            return null;
        }
        // 关系比对
        long latestForkUsec = NumberUtils.toLong(object.toString());
        boolean compareRight = isCompareLongRight(instanceAlertConfig, latestForkUsec);
        if (compareRight) {
            return null;
        }
        InstanceInfo instanceInfo = alertConfigBaseData.getInstanceInfo();
        return Arrays.asList(new InstanceAlertValueResult(instanceAlertConfig, instanceInfo, String.valueOf(latestForkUsec),
                instanceInfo.getAppId(), EMPTY));
    }

}
