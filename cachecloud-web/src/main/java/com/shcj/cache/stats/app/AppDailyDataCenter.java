package com.shcj.cache.stats.app;

import java.util.Date;

import com.shcj.cache.entity.AppDailyData;


/**
 * 应用日数据统计
 * @author leifu
 * @Date 2016年8月10日
 * @Time 下午5:11:03
 */
public interface AppDailyDataCenter {

    /**
     * 发送所有应用日报
     */
    int sendAppDailyEmail();

    /**
     * 发送单个应用日报
     */
    boolean sendAppDailyEmail(long appId, Date startDate, Date endDate);

    /**
     * 采集所有应用日报并落库。
     *
     * <p>与 {@link #sendAppDailyEmail()} 的区别只在于是否发邮件：日报数据是「日报统计」页的
     * 唯一数据源，落库这件事在任何环境都必须做，能按环境关掉的只有邮件通知。
     *
     * @param notify 是否发送日报邮件
     * @return 成功落库的应用数
     */
    int collectAppDaily(boolean notify);
    
    /**
     * 获取单天应用日报
     */
    AppDailyData getAppDailyData(long appId, Date date);

    /**
     * 按分钟/客户端采集表实时汇总日报（不落库，供查询页展示）
     */
    AppDailyData buildAppDailySnapshot(long appId, Date startDate, Date endDate);
}
