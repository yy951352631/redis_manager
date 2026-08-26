package com.shcj.cache.alert.strategy;

import com.shcj.cache.alert.bean.AlertConfigBaseData;
import com.shcj.cache.entity.InstanceAlertConfig;
import com.shcj.cache.entity.InstanceAlertValueResult;
import com.shcj.cache.entity.InstanceInfo;
import com.shcj.cache.redis.enums.RedisInfoEnum;
import org.apache.commons.lang.math.NumberUtils;

import java.util.Arrays;
import java.util.List;

/**
 * @Author: rucao
 * @Date: 2021/6/9 上午11:03
 */
public class MinuteUsedCpuSysChStrategy extends AlertConfigStrategy{

    @Override
    public List<InstanceAlertValueResult> checkConfig(InstanceAlertConfig instanceAlertConfig, AlertConfigBaseData alertConfigBaseData) {
        Object object = getValueFromDiffInfo(alertConfigBaseData.getStandardStats(), RedisInfoEnum.used_cpu_sys_children.getValue());
        if (object == null) {
            return null;
        }
        double min_used_cpu_sys_children_err= NumberUtils.toDouble(object.toString());
        boolean compareRight = isCompareDoubleRight(instanceAlertConfig, min_used_cpu_sys_children_err);
        if (compareRight) {
            return null;
        }
        InstanceInfo instanceInfo = alertConfigBaseData.getInstanceInfo();
        return Arrays.asList(new InstanceAlertValueResult(instanceAlertConfig, instanceInfo, String.valueOf(min_used_cpu_sys_children_err),
                instanceInfo.getAppId(), EMPTY));
    }
}
