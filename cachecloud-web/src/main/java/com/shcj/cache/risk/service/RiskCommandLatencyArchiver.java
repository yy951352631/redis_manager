package com.shcj.cache.risk.service;

import com.shcj.cache.dao.AppDao;
import com.shcj.cache.dao.InstanceCommandLatencyDao;
import com.shcj.cache.entity.AppDesc;
import com.shcj.cache.entity.InstanceCommandLatency;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang.time.DateUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 命令耗时分钟样本 → 小时归档。
 *
 * <p>按集群分批处理以控制内存：一个集群一小时的样本量级是
 * 实例数 × 白名单命令数 × 12（5 分钟采样），远小于全平台一次性载入。
 *
 * <p>分钟样本存的是<b>累计值</b>，因此该小时的增量取首尾差；同时保留
 * {@code maxAvgUsec}——该小时内单个采样区间的最大平均耗时，让突发信息以极低成本
 * 延长到小时表的保留期，单纯的 Σusec/Σcalls 会把尖刺抹平。
 */
@Service
public class RiskCommandLatencyArchiver {

    private static final Logger LOGGER = LoggerFactory.getLogger(RiskCommandLatencyArchiver.class);

    private static final SimpleDateFormat MINUTE_FORMAT = new SimpleDateFormat("yyyyMMddHHmm");
    private static final SimpleDateFormat HOUR_FORMAT = new SimpleDateFormat("yyyyMMddHH");

    private static final int BATCH_SIZE = 500;

    /** 分钟样本保留天数：原始记录量最大，只用于近期突发检测 */
    private static final int MINUTE_RETENTION_DAYS = 7;

    /** 小时归档保留天数：数据量约为分钟表的 1/12，留久一点才有纵向基线可比 */
    private static final int HOUR_RETENTION_DAYS = 30;

    /** 单条 delete 受 mapper 里的 limit 5000 约束，循环删除时的轮次上限，防止异常情况下空转 */
    private static final int MAX_DELETE_ROUNDS = 200;

    /** 必须与 InstanceCommandLatencyDao.xml 中 delete 语句的 limit 保持一致 */
    private static final int DELETE_BATCH_SIZE = 5000;

    @Autowired
    private AppDao appDao;

    @Autowired
    private InstanceCommandLatencyDao instanceCommandLatencyDao;

    public void archivePreviousHour() {
        Date previousHour = DateUtils.addHours(new Date(), -1);
        long hour = Long.parseLong(HOUR_FORMAT.format(previousHour));
        long begin = Long.parseLong(MINUTE_FORMAT.format(DateUtils.truncate(previousHour, java.util.Calendar.HOUR)));
        long end = begin + 59;

        List<AppDesc> apps = appDao.getOnlineApps();
        if (CollectionUtils.isEmpty(apps)) {
            return;
        }
        long start = System.currentTimeMillis();
        int total = 0;
        for (AppDesc app : apps) {
            try {
                total += archiveApp(app.getAppId(), hour, begin, end);
            } catch (Exception e) {
                LOGGER.warn("archive command latency failed appId={}: {}", app.getAppId(), e.getMessage());
            }
        }
        LOGGER.info("command latency archived hour={} rows={} cost={}ms", hour, total,
                System.currentTimeMillis() - start);
    }

    /**
     * 清理过期的命令耗时记录。
     *
     * <p>mapper 里的 delete 带 {@code limit 5000}，避免一次删除锁表太久，
     * 因此这里循环调用直到单轮删除数小于批量上限。
     */
    public void cleanupExpired() {
        long start = System.currentTimeMillis();
        long minuteCutoff = Long.parseLong(MINUTE_FORMAT.format(
                DateUtils.addDays(new Date(), -MINUTE_RETENTION_DAYS)));
        long hourCutoff = Long.parseLong(HOUR_FORMAT.format(
                DateUtils.addDays(new Date(), -HOUR_RETENTION_DAYS)));

        int minuteDeleted = deleteInRounds(true, minuteCutoff);
        int hourDeleted = deleteInRounds(false, hourCutoff);
        LOGGER.info("command latency cleaned minute<{} rows={} hour<{} rows={} cost={}ms",
                minuteCutoff, minuteDeleted, hourCutoff, hourDeleted,
                System.currentTimeMillis() - start);
    }

    private int deleteInRounds(boolean minuteTable, long cutoff) {
        int total = 0;
        for (int round = 0; round < MAX_DELETE_ROUNDS; round++) {
            int deleted;
            try {
                deleted = minuteTable
                        ? instanceCommandLatencyDao.deleteMinuteBefore(cutoff)
                        : instanceCommandLatencyDao.deleteHourBefore(cutoff);
            } catch (Exception e) {
                LOGGER.warn("delete command latency failed minuteTable={} cutoff={}: {}",
                        minuteTable, cutoff, e.getMessage());
                break;
            }
            total += deleted;
            // 单轮不足批量上限说明已删干净
            if (deleted < DELETE_BATCH_SIZE) {
                break;
            }
        }
        return total;
    }

    private int archiveApp(long appId, long hour, long begin, long end) {
        List<InstanceCommandLatency> samples =
                instanceCommandLatencyDao.listMinuteByAppAndRange(appId, begin, end);
        if (CollectionUtils.isEmpty(samples)) {
            return 0;
        }
        // 同一 instance+command 的样本已按 collect_time 升序，逐段做差分
        Map<String, List<InstanceCommandLatency>> grouped = new LinkedHashMap<>();
        for (InstanceCommandLatency sample : samples) {
            grouped.computeIfAbsent(sample.getInstanceId() + "|" + sample.getCommand(),
                    k -> new ArrayList<>()).add(sample);
        }

        List<InstanceCommandLatency> rows = new ArrayList<>();
        for (List<InstanceCommandLatency> list : grouped.values()) {
            if (list.size() < 2) {
                continue;
            }
            long calls = 0L;
            long usec = 0L;
            double maxAvg = 0D;
            for (int i = 1; i < list.size(); i++) {
                long deltaCalls = list.get(i).getCalls() - list.get(i - 1).getCalls();
                long deltaUsec = list.get(i).getUsec() - list.get(i - 1).getUsec();
                // 负值说明实例在这一小时内重启过，计数器归零，跳过该段
                if (deltaCalls <= 0 || deltaUsec < 0) {
                    continue;
                }
                calls += deltaCalls;
                usec += deltaUsec;
                maxAvg = Math.max(maxAvg, deltaUsec * 1.0D / deltaCalls);
            }
            if (calls <= 0) {
                continue;
            }
            InstanceCommandLatency first = list.get(0);
            InstanceCommandLatency row = new InstanceCommandLatency();
            row.setCollectTime(hour);
            row.setAppId(first.getAppId());
            row.setInstanceId(first.getInstanceId());
            row.setCommand(first.getCommand());
            row.setCalls(calls);
            row.setUsec(usec);
            row.setMaxAvgUsec(Math.round(maxAvg * 100) / 100.0D);
            rows.add(row);
        }

        int saved = 0;
        for (int i = 0; i < rows.size(); i += BATCH_SIZE) {
            List<InstanceCommandLatency> batch = rows.subList(i, Math.min(i + BATCH_SIZE, rows.size()));
            instanceCommandLatencyDao.batchSaveHour(batch);
            saved += batch.size();
        }
        return saved;
    }
}
