package com.shcj.cache.web.chart.model;

import java.text.ParseException;
import java.util.Date;

import org.apache.commons.lang.time.DateUtils;

import com.shcj.cache.entity.AppCommandStats;
import com.shcj.cache.entity.AppStats;
import com.shcj.cache.web.util.DateUtil;

/**
 * highchart最简单的点
 * 
 * @author leifu
 * @Date 2016年8月1日
 * @Time 下午12:59:29
 */
public class HighchartPoint {
    /**
     * 时间戳
     */
    private Long x;

    /**
     * 用于表示y轴数量
     */
    private Long y;
    
    /**
     * 日期
     */
    private String date;

    public HighchartPoint() {

    }

    public HighchartPoint(Long x, Long y, String date) {
        this.x = x;
        this.y = y;
        this.date = date;
    }


    public Long getX() {
        return x;
    }

    public void setX(Long x) {
        this.x = x;
    }

    public Long getY() {
        return y;
    }

    public void setY(Long y) {
        this.y = y;
    }

    public String getDate() {
        return date;
    }

    public void setDate(String date) {
        this.date = date;
    }

    public static HighchartPoint getFromAppCommandStats(AppCommandStats appCommandStats, Date currentDate, int diffDays) throws ParseException {
        Date collectDate = getDateTime(appCommandStats.getCollectTime());
        if (!DateUtils.isSameDay(currentDate, collectDate)) {
            return null;
        }
        
        //显示用的时间
        String date = null;
        try {
            date = DateUtil.formatDate(collectDate, "yyyy-MM-dd HH:mm");
        } catch (Exception e) {
            date = DateUtil.formatDate(collectDate, "yyyy-MM-dd HH");
        }
        // y坐标
        long commandCount = appCommandStats.getCommandCount();
        // x坐标
        //为了显示在一个时间范围内
        if (diffDays > 0) {
            collectDate = DateUtils.addDays(collectDate, diffDays);
        }
        
        return new HighchartPoint(collectDate.getTime(), commandCount, date);
    }

    public static HighchartPoint getFromAppStats(AppStats appStat, String statName, Date currentDate, int diffDays) throws ParseException {
        Date collectDate = getDateTime(appStat.getCollectTime());
        if (!DateUtils.isSameDay(currentDate, collectDate)) {
            return null;
        }
        String date = formatCollectDate(collectDate);
        long count = resolveStatValue(appStat, statName);
        if (diffDays > 0) {
            collectDate = DateUtils.addDays(collectDate, diffDays);
        }
        return new HighchartPoint(collectDate.getTime(), count, date);
    }

    /** 区间趋势图：保留完整时间轴，不做同日过滤 */
    public static HighchartPoint getFromAppStatsTimeline(AppStats appStat, String statName) throws ParseException {
        Date collectDate = getDateTime(appStat.getCollectTime());
        return new HighchartPoint(collectDate.getTime(), resolveStatValue(appStat, statName), formatCollectDate(collectDate));
    }

    private static String formatCollectDate(Date collectDate) {
        try {
            return DateUtil.formatDate(collectDate, "yyyy-MM-dd HH:mm");
        } catch (Exception e) {
            return DateUtil.formatDate(collectDate, "yyyy-MM-dd HH");
        }
    }

    private static long resolveStatValue(AppStats appStat, String statName) {
        if ("hits".equals(statName)) {
            return appStat.getHits();
        } else if ("misses".equals(statName)) {
            return appStat.getMisses();
        } else if ("usedMemory".equals(statName)) {
            return appStat.getUsedMemory() / 1024 / 1024;
        } else if ("usedMemoryRss".equals(statName)) {
            return appStat.getUsedMemoryRss() / 1024 / 1024;
        } else if ("netInput".equals(statName)) {
            return appStat.getNetInputByte();
        } else if ("netOutput".equals(statName)) {
            return appStat.getNetOutputByte();
        } else if ("connectedClient".equals(statName)) {
            return appStat.getConnectedClients();
        } else if ("objectSize".equals(statName)) {
            return appStat.getObjectSize();
        } else if ("hitPercent".equals(statName)) {
            return appStat.getHitPercent();
        } else if ("cpuSys".equals(statName)) {
            return appStat.getCpuSys();
        } else if ("cpuUser".equals(statName)) {
            return appStat.getCpuUser();
        } else if ("cpuSysChildren".equals(statName)) {
            return appStat.getCpuSysChildren();
        } else if ("cpuUserChildren".equals(statName)) {
            return appStat.getCpuUserChildren();
        } else if ("expiredKeys".equals(statName)) {
            return appStat.getExpiredKeys();
        } else if ("evictedKeys".equals(statName)) {
            return appStat.getEvictedKeys();
        } else if ("commandCount".equals(statName)) {
            return appStat.getCommandCount();
        }
        return 0;
    }

    private static Date getDateTime(long collectTime) throws ParseException {
        try {
            return DateUtil.parseYYYYMMddHHMM(String.valueOf(collectTime));
        } catch (Exception e) {
            return DateUtil.parseYYYYMMddHH(String.valueOf(collectTime));
        }
    }

}
