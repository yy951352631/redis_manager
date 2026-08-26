package com.shcj.cache.schedule.jobs;

import com.shcj.cache.web.service.AppService;
import org.quartz.JobExecutionContext;
import org.quartz.SchedulerContext;
import org.springframework.context.ApplicationContext;

/**
 * 定时将 Redis 实际主从关系同步到 instance_info.parent_id（读路径不写库）。
 */
public class InstanceTopologySyncJob extends CacheBaseJob {
    private static final long serialVersionUID = 1L;

    @Override
    public void action(JobExecutionContext context) {
        try {
            logger.info("begin-InstanceTopologySyncJob");
            SchedulerContext schedulerContext = context.getScheduler().getContext();
            ApplicationContext applicationContext = (ApplicationContext) schedulerContext.get(APPLICATION_CONTEXT_KEY);
            AppService appService = applicationContext.getBean("appService", AppService.class);
            int updated = appService.syncAllOnlineAppTopology();
            logger.info("end-InstanceTopologySyncJob, totalInstancesUpdated={}", updated);
        } catch (Exception e) {
            logger.error("InstanceTopologySyncJob failed: {}", e.getMessage(), e);
        }
    }
}
