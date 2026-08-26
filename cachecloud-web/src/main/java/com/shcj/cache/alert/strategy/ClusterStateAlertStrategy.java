package com.shcj.cache.alert.strategy;

import java.util.Arrays;
import java.util.List;

import com.shcj.cache.alert.bean.AlertConfigBaseData;
import com.shcj.cache.entity.InstanceAlertConfig;
import com.shcj.cache.entity.InstanceAlertValueResult;
import com.shcj.cache.entity.InstanceInfo;
import com.shcj.cache.redis.enums.RedisClusterInfoEnum;

/**
 * 集群状态监控
 * @author leifu
 * @Date 2017年6月21日
 * @Time 下午3:01:21
 */
public class ClusterStateAlertStrategy extends AlertConfigStrategy {

    @Override
    public List<InstanceAlertValueResult> checkConfig(InstanceAlertConfig instanceAlertConfig, AlertConfigBaseData alertConfigBaseData) {
        Object object = getValueFromClusterInfo(alertConfigBaseData.getStandardStats(), RedisClusterInfoEnum.cluster_state.getValue());
        if (object == null) {
            return null;
        }
        // 关系比对
        String clusterState = object.toString();
        boolean compareRight = isCompareStringRight(instanceAlertConfig, clusterState);
        if (compareRight) {
            return null;
        }
        InstanceInfo instanceInfo = alertConfigBaseData.getInstanceInfo();
        return Arrays.asList(new InstanceAlertValueResult(instanceAlertConfig, instanceInfo, String.valueOf(clusterState),
                instanceInfo.getAppId(), EMPTY));
    }

}
