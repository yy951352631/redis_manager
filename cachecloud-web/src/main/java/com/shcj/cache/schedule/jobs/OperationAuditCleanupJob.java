package com.shcj.cache.schedule.jobs;

import com.shcj.cache.web.service.OperationAuditService;
import org.quartz.JobExecutionContext;
import org.quartz.SchedulerContext;
import org.springframework.context.ApplicationContext;

/**
 * 审计日志清理：删除超出保留期的操作记录。
 *
 * <p>这张表此前没有任何清理，只增不减。</p>
 */
public class OperationAuditCleanupJob extends CacheBaseJob {

    private static final long serialVersionUID = 1L;

    @Override
    public void action(JobExecutionContext context) {
        try {
            SchedulerContext schedulerContext = context.getScheduler().getContext();
            ApplicationContext applicationContext = (ApplicationContext) schedulerContext.get(APPLICATION_CONTEXT_KEY);
            applicationContext.getBean(OperationAuditService.class).cleanupExpired();
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
        }
    }
}
