package com.shcj.cache.risk.service;

import com.shcj.cache.dao.InstanceRiskMetricDao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * 分钟级风险指标的保留期清理。
 *
 * <p>{@code InstanceRiskMetricDao.deleteBefore} 早就写好了却一直没有调用方，表会无限增长。
 * 「数据模型」页的实时聚合方案建立在这张表体量受控的前提上，所以把清理接上。
 *
 * <p>保留 35 天而不是 30 天：页面上最长的查询窗口是 30 天，两个数字取相等会让最早那几天
 * 恰好在查询时被清掉，留 5 天余量。
 */
@Service
public class RiskMetricCleaner {

    private static final Logger LOGGER = LoggerFactory.getLogger(RiskMetricCleaner.class);

    private static final SimpleDateFormat MINUTE_FORMAT = new SimpleDateFormat("yyyyMMddHHmm");

    /** 保留天数，比页面最长查询窗口（30 天）多留 5 天余量 */
    private static final int RETENTION_DAYS = 35;

    /** 必须与 InstanceRiskMetricDao.xml 中 delete 语句的 limit 保持一致 */
    private static final int DELETE_BATCH_SIZE = 5000;

    /** 单次执行的轮次上限，防止异常情况下空转 */
    private static final int MAX_DELETE_ROUNDS = 500;

    @Autowired
    private InstanceRiskMetricDao instanceRiskMetricDao;

    public void cleanupExpired() {
        long start = System.currentTimeMillis();
        long cutoff = Long.parseLong(MINUTE_FORMAT.format(
                new Date(System.currentTimeMillis() - RETENTION_DAYS * 24L * 3600 * 1000)));
        int total = 0;
        for (int round = 0; round < MAX_DELETE_ROUNDS; round++) {
            int deleted;
            try {
                deleted = instanceRiskMetricDao.deleteBefore(cutoff);
            } catch (Exception e) {
                LOGGER.warn("delete risk metric failed cutoff={}: {}", cutoff, e.getMessage());
                break;
            }
            total += deleted;
            // 单轮不足批量上限说明已删干净
            if (deleted < DELETE_BATCH_SIZE) {
                break;
            }
        }
        LOGGER.info("risk metric cleaned collect_time<{} rows={} cost={}ms",
                cutoff, total, System.currentTimeMillis() - start);
    }
}
