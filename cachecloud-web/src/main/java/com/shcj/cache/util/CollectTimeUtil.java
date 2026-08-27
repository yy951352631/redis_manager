package com.shcj.cache.util;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * 采集时间戳（{@code yyyyMMddHHmm} 的十进制整数）相关计算。
 *
 * <p>存在的理由：这个格式看着像数字，直接做算术就会错。相邻两分钟的
 * {@code 202608270617} 与 {@code 202608270616} 相减是 1，除以 100 又都变成
 * {@code 2026082706}——分钟位被整除抹掉了。CPU 使用率原先正是这么算间隔的，
 * 结果同一小时内恒为 0，只有跨小时那一分钟才碰巧算对。</p>
 */
public final class CollectTimeUtil {

    private static final String PATTERN = "yyyyMMddHHmm";

    private CollectTimeUtil() {
    }

    /**
     * 两个采集时间之间的秒数，解析失败或顺序颠倒时返回 0。
     */
    public static long secondsBetween(long fromCollectTime, long toCollectTime) {
        Date from = toDate(fromCollectTime);
        Date to = toDate(toCollectTime);
        if (from == null || to == null) {
            return 0L;
        }
        long seconds = (to.getTime() - from.getTime()) / 1000L;
        return seconds > 0 ? seconds : 0L;
    }

    /**
     * 由累计 CPU 秒数的增量与时间跨度算出使用率百分比，保留一位小数。
     *
     * <p>增量为负说明实例在这期间重启过、累计计数器归了零，此时给不出结论，返回 0。</p>
     */
    public static double cpuUsePercent(double cpuSecondsDelta, long spanSeconds) {
        if (spanSeconds <= 0 || cpuSecondsDelta < 0) {
            return 0.0D;
        }
        return Math.round(cpuSecondsDelta * 100.0D / spanSeconds * 10.0D) / 10.0D;
    }

    private static Date toDate(long collectTime) {
        if (collectTime <= 0) {
            return null;
        }
        try {
            return new SimpleDateFormat(PATTERN).parse(String.valueOf(collectTime));
        } catch (ParseException e) {
            return null;
        }
    }
}
