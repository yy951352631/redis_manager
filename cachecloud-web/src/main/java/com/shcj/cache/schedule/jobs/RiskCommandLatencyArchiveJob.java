package com.shcj.cache.schedule.jobs;

import com.shcj.cache.risk.service.RiskCommandLatencyArchiver;
import org.quartz.JobExecutionContext;
import org.quartz.SchedulerContext;
import org.springframework.context.ApplicationContext;

/**
 * 命令耗时归档：把上一小时的分钟样本聚合成小时行。
 *
 * <p>分钟表只保留 7 天用于突发检测，小时表保留更久用于纵向基线——
 * 基线要稳，用小时级聚合反而比分钟级更合适，分钟抖动对基线是噪声。
 */
public class RiskCommandLatencyArchiveJob extends CacheBaseJob {

    private static final long serialVersionUID = 1L;

    @Override
    public void action(JobExecutionContext context) {
        try {
            SchedulerContext schedulerContext = context.getScheduler().getContext();
            ApplicationContext applicationContext = (ApplicationContext) schedulerContext.get(APPLICATION_CONTEXT_KEY);
            RiskCommandLatencyArchiver archiver =
                    applicationContext.getBean(RiskCommandLatencyArchiver.class);
            archiver.archivePreviousHour();
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
        }
    }
}
