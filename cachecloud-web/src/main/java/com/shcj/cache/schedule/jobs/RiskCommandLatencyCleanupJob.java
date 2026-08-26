package com.shcj.cache.schedule.jobs;

import com.shcj.cache.risk.service.RiskCommandLatencyArchiver;
import org.quartz.JobExecutionContext;
import org.quartz.SchedulerContext;
import org.springframework.context.ApplicationContext;

/**
 * 命令耗时记录清理：删除超出保留期的分钟样本与小时归档。
 *
 * <p>独立成一个调度任务而不是挂在归档任务后面，是为了让它在「调度任务」页面里可见、
 * 可单独暂停——清理是删数据的操作，出问题时需要能立刻停掉而不影响归档。
 */
public class RiskCommandLatencyCleanupJob extends CacheBaseJob {

    private static final long serialVersionUID = 1L;

    @Override
    public void action(JobExecutionContext context) {
        try {
            SchedulerContext schedulerContext = context.getScheduler().getContext();
            ApplicationContext applicationContext = (ApplicationContext) schedulerContext.get(APPLICATION_CONTEXT_KEY);
            RiskCommandLatencyArchiver archiver =
                    applicationContext.getBean(RiskCommandLatencyArchiver.class);
            archiver.cleanupExpired();
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
        }
    }
}
