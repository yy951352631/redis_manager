package com.shcj.cache.schedule.jobs;

import com.shcj.cache.task.TaskService;
import org.quartz.JobExecutionContext;
import org.quartz.SchedulerContext;
import org.springframework.context.ApplicationContext;

/**
 * @author fulei
 */
public class TaskExecuteJob extends CacheBaseJob {

	private static final long serialVersionUID = -1697673324465500314L;

	@Override
    public void action(JobExecutionContext context) {
		long startTime = System.currentTimeMillis();
		logger.debug("TaskExecuteJob start");
        try {
            SchedulerContext schedulerContext = context.getScheduler().getContext();
            ApplicationContext applicationContext = (ApplicationContext) schedulerContext.get(APPLICATION_CONTEXT_KEY);
            TaskService taskService = applicationContext.getBean(TaskService.class);
            taskService.executeNewTask();
        } catch (Exception e) {
        	logger.error(e.getMessage(), e);
		}
		logger.debug("TaskExecuteJob end, cost time is {} ms", (System.currentTimeMillis() - startTime));
    }
	
	
}
