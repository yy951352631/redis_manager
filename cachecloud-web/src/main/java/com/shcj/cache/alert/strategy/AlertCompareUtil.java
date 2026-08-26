package com.shcj.cache.alert.strategy;

import com.shcj.cache.entity.InstanceAlertConfig;
import com.shcj.cache.redis.enums.InstanceAlertCompareTypeEnum;
import org.apache.commons.lang.math.NumberUtils;

/**
 * 报警阈值比较。
 *
 * <p>{@code compare_type} 描述的是<b>触发</b>条件而非正常区间：配置「大于 90」表示
 * 实际值大于 90 时报警。{@link AlertConfigStrategy} 里的 {@code isCompareXxxRight}
 * 返回的是「不触发」，语义相反且容易读错，新增评估路径统一走这里。</p>
 */
public final class AlertCompareUtil {

    private AlertCompareUtil() {
    }

    /**
     * @return true 表示命中触发条件，应当报警
     */
    public static boolean isTriggered(InstanceAlertConfig config, double currentValue) {
        double alertValue = NumberUtils.toDouble(config.getAlertValue());
        int compareType = config.getCompareType();
        if (compareType == InstanceAlertCompareTypeEnum.LESS_THAN.getValue()) {
            return currentValue < alertValue;
        }
        if (compareType == InstanceAlertCompareTypeEnum.MORE_THAN.getValue()) {
            return currentValue > alertValue;
        }
        if (compareType == InstanceAlertCompareTypeEnum.EQUAL.getValue()) {
            return currentValue == alertValue;
        }
        if (compareType == InstanceAlertCompareTypeEnum.NOT_EQUAL.getValue()) {
            return currentValue != alertValue;
        }
        return false;
    }

    /**
     * @return true 表示命中触发条件，应当报警
     */
    public static boolean isTriggered(InstanceAlertConfig config, String currentValue) {
        String alertValue = config.getAlertValue();
        int compareType = config.getCompareType();
        if (compareType == InstanceAlertCompareTypeEnum.EQUAL.getValue()) {
            return currentValue != null && currentValue.equals(alertValue);
        }
        if (compareType == InstanceAlertCompareTypeEnum.NOT_EQUAL.getValue()) {
            return currentValue == null || !currentValue.equals(alertValue);
        }
        return false;
    }
}
