package com.shcj.cache.schedule.jobs;

import com.shcj.cache.entity.TimeBetween;
import com.shcj.cache.web.service.AppStatGatherService;
import com.shcj.cache.web.util.DateUtil;
import org.apache.commons.lang.math.NumberUtils;
import org.apache.commons.lang.time.DateUtils;
import org.quartz.JobExecutionContext;
import org.quartz.SchedulerContext;
import org.springframework.context.ApplicationContext;

import java.util.Date;

/**
 * 按应用聚合慢日志数、延迟事件数、应用统计与连接数，写入 app_client_statistic_gather，
 * 供「服务端统计」页与日报读取。
 *
 * <p>取 5 分钟前往前推 5 分钟的区间，留出采集与入库的延迟余量。
 */
public class AppStatGatherJob extends CacheBaseJob {

    private static final long serialVersionUID = 1L;

    /** 与 app_client_statistic_gather 的 gather_time 口径一致，秒位固定为 00 */
    private static final String COLLECT_TIME_FORMAT = "yyyyMMddHHmm00";

    @Override
    public void action(JobExecutionContext context) {
        try {
            SchedulerContext schedulerContext = context.getScheduler().getContext();
            ApplicationContext applicationContext = (ApplicationContext) schedulerContext.get(APPLICATION_CONTEXT_KEY);
            AppStatGatherService service = applicationContext.getBean(AppStatGatherService.class);

            TimeBetween range = resolveRange();
            service.gatherIncrement(range.getStartTime(), range.getEndTime());
            logger.info("app stat gathered startTime={} endTime={}", range.getStartTime(), range.getEndTime());
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
        }
    }

    private TimeBetween resolveRange() {
        Date endDate = DateUtils.addMinutes(new Date(), -5);
        Date startDate = DateUtils.addMinutes(endDate, -5);
        long startTime = NumberUtils.toLong(DateUtil.formatDate(startDate, COLLECT_TIME_FORMAT));
        long endTime = NumberUtils.toLong(DateUtil.formatDate(endDate, COLLECT_TIME_FORMAT));
        return new TimeBetween(startTime, endTime, startDate, endDate);
    }
}
