package com.shcj.cache.schedule.jobs;

import com.shcj.cache.util.ConstUtils;
import org.apache.commons.lang.math.NumberUtils;
import org.apache.commons.lang.time.DateUtils;
import org.quartz.JobExecutionContext;
import org.quartz.SchedulerContext;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;

/**
 * 清理天维度数据任务
 * Created by yijunzhang
 */
public class CleanupDayDimensionalityJob extends CacheBaseJob {

    private static final long serialVersionUID = 8815839394475276540L;

    private static int BATCH_SIZE = 1000;

    private static final String CLEAN_APP_HOUR_COMMAND_STATISTICS = "delete from app_hour_command_statistics where create_time < ? limit " + BATCH_SIZE;

    private static final String CLEAN_APP_MINUTE_COMMAND_STATISTICS = "delete from app_minute_command_statistics where create_time < ? limit " + BATCH_SIZE;

    private static final String CLEAN_APP_HOUR_STATISTICS = "delete from app_hour_statistics where create_time < ? limit " + BATCH_SIZE;

    private static final String CLEAN_APP_MINUTE_STATISTICS = "delete from app_minute_statistics where create_time < ? limit " + BATCH_SIZE;
    /**
     * 清除客户端耗时汇总数据
     */
    private static final String CLEAN_APP_CLIENT_MINUTE_COST_TOTAL = "delete from app_client_costtime_minute_stat_total where collect_time < ? limit " + BATCH_SIZE;

    //清除服务器统计数据
    private static final String CLEAN_SERVER_STAT_STATISTICS = "delete from server_stat where cdate < ? limit " + BATCH_SIZE;

    /**
     * 清除实例基础统计
     */
    private static final String CLEAN_INSTANCE_MINUTE_STATS = "delete from instance_minute_stats where collect_time < ? limit " + BATCH_SIZE;

    // 风险评估相关表：指标窄表按 7 天、命令耗时分钟表按 24 小时、小时归档表按 14 天
    // 由已下线的 CleanupDayAppClientStatJob 迁入：该表仍由 ExternalRedisStatsCollectJob 持续写入
    private static final String CLEAN_INSTANCE_LATENCY_HISTORY = "delete from instance_latency_history where execute_date < ? limit " + BATCH_SIZE;

    private static final String CLEAN_RISK_METRIC = "delete from instance_risk_metric_minute where collect_time < ? limit " + BATCH_SIZE;

    private static final String CLEAN_COMMAND_LATENCY_MINUTE = "delete from instance_command_latency_minute where collect_time < ? limit " + BATCH_SIZE;

    private static final String CLEAN_COMMAND_LATENCY_HOUR = "delete from instance_command_latency_hour where collect_time < ? limit " + BATCH_SIZE;

    // 压测记录：一次压测一行，量很小，与其他统计表同一套保留期即可
    private static final String CLEAN_BENCHMARK_TASK =
            "delete from benchmark_task where start_time < ? limit " + BATCH_SIZE;

    JdbcTemplate jdbcTemplate = null;

    @Override
    public void action(JobExecutionContext context) {
        if (!ConstUtils.WHETHER_SCHEDULE_CLEAN_DATA) {
            logger.info("whether_schedule_clean_data is false, ignored");
            return;
        }
        try {
            SchedulerContext schedulerContext = context.getScheduler().getContext();
            ApplicationContext applicationContext = (ApplicationContext) schedulerContext.get(APPLICATION_CONTEXT_KEY);
            jdbcTemplate = applicationContext.getBean("jdbcTemplate", JdbcTemplate.class);

            Calendar calendar = Calendar.getInstance();
            calendar.setTime(new Date());

            // 清除应用&命令统计数据(保存31天)
            calendar.add(Calendar.DAY_OF_MONTH, -31);
            Date time = calendar.getTime();
            long cleanCount = 0;
            cleanCount = scrollDelete(CLEAN_APP_HOUR_COMMAND_STATISTICS, time);
            logger.info("clean_app_hour_command_statistics count={}", cleanCount);
            cleanCount = scrollDelete(CLEAN_APP_MINUTE_COMMAND_STATISTICS, time);
            logger.info("clean_app_minute_command_statistics count={}", cleanCount);
            cleanCount = scrollDelete(CLEAN_APP_HOUR_STATISTICS, time);
            logger.info("clean_app_hour_statistics count={}", cleanCount);
            cleanCount = scrollDelete(CLEAN_APP_MINUTE_STATISTICS, time);
            logger.info("clean_app_minute_statistics count={}", cleanCount);

            //清除服务器统计数据
            calendar.setTime(new Date());
            calendar.add(Calendar.DAY_OF_MONTH, -7);
            String date = new SimpleDateFormat("yyyy-MM-dd").format(calendar.getTime());
            cleanCount = scrollDelete(CLEAN_SERVER_STAT_STATISTICS, date);
            logger.info("clean_server_stat_total count={}", cleanCount);

            long timeFormat = NumberUtils.toLong(new SimpleDateFormat("yyyyMMddHHmm00").format(calendar.getTime()));
            //清除进程级别统计数据(保存5天)
            long start = System.currentTimeMillis();
            timeFormat = NumberUtils.toLong(new SimpleDateFormat("yyyyMMddHHmm").format(DateUtils.addDays(new Date(), -5)));
            cleanCount = scrollDelete(CLEAN_INSTANCE_MINUTE_STATS, timeFormat);
            logger.info("clean_instance_minute_stats timeFormat={} count={} cost={}s", timeFormat, cleanCount, (System.currentTimeMillis() - start) / 1000);

            // 实例延迟事件：沿用原 CleanupDayAppClientStatJob 的 14 天保留期。
            // execute_date 是 timestamp，这里传数值形式的 yyyyMMddHHmmss，由 MySQL 做隐式转换比较
            long latencyHistoryTime = NumberUtils.toLong(
                    new SimpleDateFormat("yyyyMMddHHmmss").format(DateUtils.addDays(new Date(), -14)));
            cleanCount = scrollDelete(CLEAN_INSTANCE_LATENCY_HISTORY, latencyHistoryTime);
            logger.info("clean_instance_latency_history timeFormat={} count={}", latencyHistoryTime, cleanCount);

            // 风险评估指标窄表：保留 7 天
            long riskMetricTime = NumberUtils.toLong(
                    new SimpleDateFormat("yyyyMMddHHmm").format(DateUtils.addDays(new Date(), -7)));
            cleanCount = scrollDelete(CLEAN_RISK_METRIC, riskMetricTime);
            logger.info("clean_instance_risk_metric_minute timeFormat={} count={}", riskMetricTime, cleanCount);

            // 命令耗时分钟样本：保留 24 小时（高基数表，靠短留存控体量）
            long latencyMinuteTime = NumberUtils.toLong(
                    new SimpleDateFormat("yyyyMMddHHmm").format(DateUtils.addHours(new Date(), -24)));
            cleanCount = scrollDelete(CLEAN_COMMAND_LATENCY_MINUTE, latencyMinuteTime);
            logger.info("clean_instance_command_latency_minute timeFormat={} count={}", latencyMinuteTime, cleanCount);

            // 命令耗时小时归档：保留 14 天，供纵向基线使用
            long latencyHourTime = NumberUtils.toLong(
                    new SimpleDateFormat("yyyyMMddHH").format(DateUtils.addDays(new Date(), -14)));
            cleanCount = scrollDelete(CLEAN_COMMAND_LATENCY_HOUR, latencyHourTime);
            logger.info("clean_instance_command_latency_hour timeFormat={} count={}", latencyHourTime, cleanCount);

            // 压测记录：保留 90 天。一次压测一行，量很小，留久一点便于纵向对比历次结果
            Date benchmarkCutoff = DateUtils.addDays(new Date(), -90);
            cleanCount = scrollDelete(CLEAN_BENCHMARK_TASK, benchmarkCutoff);
            logger.info("clean_benchmark_task before={} count={}", benchmarkCutoff, cleanCount);

            //注销此逻辑，其操作的表已废弃，待统一删除
            //清除客户端耗时数据(保存2天)
//            calendar.setTime(new Date());
//            calendar.add(Calendar.DAY_OF_MONTH, -2);
//            timeFormat = NumberUtils.toLong(new SimpleDateFormat("yyyyMMddHHmm00").format(calendar.getTime()));
//            logger.warn("clean_app_client_costtime_minute_stat count={}", cleanCount);

            //清除客户端耗时汇总数据(保存14天)
            calendar.setTime(new Date());
            calendar.add(Calendar.DAY_OF_MONTH, -14);
            timeFormat = NumberUtils.toLong(new SimpleDateFormat("yyyyMMddHHmm00").format(calendar.getTime()));
            cleanCount = jdbcTemplate.update(CLEAN_APP_CLIENT_MINUTE_COST_TOTAL, timeFormat);
            logger.info("clean_app_client_costtime_minute_stat_total count={}", cleanCount);

        } catch (Exception e) {
            logger.error(e.getMessage(), e);
        }

    }

    /**
     * 滚动删除表数据
     */
    private long scrollDelete(String sql, Object time) {
        long totalCount = 0;
        while (true) {
            int cleanCount = jdbcTemplate.update(sql, time);
            totalCount += cleanCount;
            if (cleanCount == 0) {
                break;
            }
        }
        return totalCount;
    }

}
