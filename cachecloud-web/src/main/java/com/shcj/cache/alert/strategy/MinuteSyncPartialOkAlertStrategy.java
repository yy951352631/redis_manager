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
 * 分钟部分复制成功次数
 * @author leifu
 * @Date 2017年6月16日
 * @Time 下午2:34:10
 */
public class MinuteSyncPartialOkAlertStrategy extends AlertConfigStrategy {

    @Override
    public List<InstanceAlertValueResult> checkConfig(InstanceAlertConfig instanceAlertConfig, AlertConfigBaseData alertConfigBaseData) {
        Object object = getValueFromDiffInfo(alertConfigBaseData.getStandardStats(), RedisInfoEnum.sync_partial_ok.getValue());
        if (object == null) {
            return null;
        }
        long minuteSyncPartialOk = NumberUtils.toLong(object.toString());
        boolean compareRight = isCompareLongRight(instanceAlertConfig, minuteSyncPartialOk);
        if (compareRight) {
            return null;
        }
        InstanceInfo instanceInfo = alertConfigBaseData.getInstanceInfo();
        return Arrays.asList(new InstanceAlertValueResult(instanceAlertConfig, instanceInfo, String.valueOf(minuteSyncPartialOk),
                instanceInfo.getAppId(), EMPTY));
    }

}
