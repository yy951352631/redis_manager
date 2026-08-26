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
 * 分钟拒绝客户端连接数
 * @author leifu
 * @Date 2017年6月16日
 * @Time 下午2:34:10
 */
public class MinuteRejectedConnectionsAlertStrategy extends AlertConfigStrategy {

    @Override
    public List<InstanceAlertValueResult> checkConfig(InstanceAlertConfig instanceAlertConfig, AlertConfigBaseData alertConfigBaseData) {
        Object object = getValueFromDiffInfo(alertConfigBaseData.getStandardStats(), RedisInfoEnum.rejected_connections.getValue());
        if (object == null) {
            return null;
        }
        long minuteRejectedConnections = NumberUtils.toLong(object.toString());
        boolean compareRight = isCompareLongRight(instanceAlertConfig, minuteRejectedConnections);
        if (compareRight) {
            return null;
        }
        InstanceInfo instanceInfo = alertConfigBaseData.getInstanceInfo();
        return Arrays.asList(new InstanceAlertValueResult(instanceAlertConfig, instanceInfo, String.valueOf(minuteRejectedConnections),
                instanceInfo.getAppId(), EMPTY));
    }

}
