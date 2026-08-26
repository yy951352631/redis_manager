package com.shcj.cache.schedule.jobs;

import com.shcj.cache.stats.app.AppDailyDataCenter;
import com.shcj.cache.util.EnvUtil;
import org.quartz.JobExecutionContext;
import org.quartz.SchedulerContext;
import org.quartz.SchedulerException;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;

/**
 * 发送日报
 *
 * @author leifu
 * @Date 2016年8月12日
 * @Time 上午11:25:09
 */
public class AppDailyJob extends CacheBaseJob {

    private static final long serialVersionUID = 7751425759758902400L;

    @Override
    public void action(JobExecutionContext context) {
        try {
            SchedulerContext schedulerContext = context.getScheduler().getContext();
            ApplicationContext applicationContext = (ApplicationContext) schedulerContext.get(APPLICATION_CONTEXT_KEY);
            Environment env = applicationContext.getBean(Environment.class);
            // 开发/测试环境只关掉邮件通知，日报仍然要落库——它是「日报统计」页的唯一数据源，
            // 之前在这里整体 return 会导致非线上环境的日报页永远无数据。
            boolean notify = !EnvUtil.isDev(env);

            try {
                AppDailyDataCenter appDailyDataCenter = applicationContext.getBean("appDailyDataCenter", AppDailyDataCenter.class);
                int count = appDailyDataCenter.collectAppDaily(notify);
                logger.info("app daily collected apps={} notify={}", count, notify);
            } catch (Exception e) {
                logger.error("collectAppDaily error", e.getMessage());
            }
        } catch (SchedulerException e) {
            logger.error(e.getMessage(), e);
        }

    }
}
