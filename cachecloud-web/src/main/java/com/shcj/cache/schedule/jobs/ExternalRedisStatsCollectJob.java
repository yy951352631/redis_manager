package com.shcj.cache.schedule.jobs;

import com.shcj.cache.stats.app.ExternalRedisCenter;
import org.quartz.JobExecutionContext;
import org.quartz.SchedulerContext;
import org.springframework.context.ApplicationContext;

/**
 * Collects minute statistics for Redis clusters managed without machine agents.
 */
public class ExternalRedisStatsCollectJob extends CacheBaseJob {
    private static final long serialVersionUID = 1L;

    @Override
    public void action(JobExecutionContext context) {
        try {
            SchedulerContext schedulerContext = context.getScheduler().getContext();
            ApplicationContext applicationContext =
                    (ApplicationContext) schedulerContext.get(APPLICATION_CONTEXT_KEY);
            ExternalRedisCenter externalRedisCenter =
                    applicationContext.getBean("externalRedisCenter", ExternalRedisCenter.class);
            externalRedisCenter.collectStatistics();
        } catch (Exception e) {
            logger.error("ExternalRedisStatsCollectJob failed: {}", e.getMessage(), e);
        }
    }
}
