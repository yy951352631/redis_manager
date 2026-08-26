package com.shcj.cache.schedule.jobs;

import com.shcj.cache.risk.service.RiskMetricCleaner;
import org.quartz.JobExecutionContext;
import org.quartz.SchedulerContext;
import org.springframework.context.ApplicationContext;

/**
 * 分钟级风险指标清理：删除超出保留期的采集样本。
 *
 * <p>与命令耗时清理分开成两个任务，是为了能在「调度任务」页面里单独暂停——
 * 两张表的数据量与重要性不同，出问题时不该被迫一起停。
 */
public class RiskMetricCleanupJob extends CacheBaseJob {

    private static final long serialVersionUID = 1L;

    @Override
    public void action(JobExecutionContext context) {
        try {
            SchedulerContext schedulerContext = context.getScheduler().getContext();
            ApplicationContext applicationContext = (ApplicationContext) schedulerContext.get(APPLICATION_CONTEXT_KEY);
            applicationContext.getBean(RiskMetricCleaner.class).cleanupExpired();
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
        }
    }
}
