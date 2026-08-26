package com.shcj.cache.web.chart.model;

import com.shcj.cache.entity.AppStats;
import com.shcj.cache.web.util.DateUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang.time.DateUtils;

import java.text.DecimalFormat;
import java.text.ParseException;
import java.util.Date;

/**
 * highchart最简单的点 double y
 *
 * @author leifu
 * @Date 2016年8月1日
 * @Time 下午12:59:29
 */
@Slf4j
public class HighchartDoublePoint {
    /**
     * 时间戳
     */
    private Long x;

    /**
     * 用于表示y轴数量
     */
    private Double y;

    /**
     * 日期
     */
    private String date;

    public HighchartDoublePoint() {

    }

    public HighchartDoublePoint(Long x, Double y, String date) {
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

    public Double getY() {
        return y;
    }

    public void setY(Double y) {
        this.y = y;
    }

    public String getDate() {
        return date;
    }

    public void setDate(String date) {
        this.date = date;
    }

    public static HighchartDoublePoint getFromAppStats(AppStats appStat, String statName, Date currentDate, int diffDays) throws ParseException {
        Date collectDate = getDateTime(appStat.getCollectTime());
        if (!DateUtils.isSameDay(currentDate, collectDate)) {
            return null;
        }
        return buildFromAppStats(appStat, statName, collectDate, diffDays);
    }

    /** 区间趋势图：保留完整时间轴，不做同日过滤 */
    public static HighchartDoublePoint getFromAppStatsTimeline(AppStats appStat, String statName) throws ParseException {
        return buildFromAppStats(appStat, statName, getDateTime(appStat.getCollectTime()), 0);
    }

    private static HighchartDoublePoint buildFromAppStats(AppStats appStat, String statName, Date collectDate, int diffDays)
            throws ParseException {
        String date;
        try {
            date = DateUtil.formatDate(collectDate, "yyyy-MM-dd HH:mm");
        } catch (Exception e) {
            date = DateUtil.formatDate(collectDate, "yyyy-MM-dd HH");
        }
        DecimalFormat df = new DecimalFormat("##.##");
        double count = 0D;
        if ("memFragRatio".equals(statName)) {
            long rss = appStat.getUsedMemoryRss();
            long mem = appStat.getUsedMemory();
            if (mem > 0) {
                count = Double.parseDouble(df.format(rss * 1.0D / mem));
            }
        }
        if (diffDays > 0) {
            collectDate = DateUtils.addDays(collectDate, diffDays);
        }
        return new HighchartDoublePoint(collectDate.getTime(), count, date);
    }

    private static Date getDateTime(long collectTime) throws ParseException {
        try {
            return DateUtil.parseYYYYMMddHHMM(String.valueOf(collectTime));
        } catch (Exception e) {
            return DateUtil.parseYYYYMMddHH(String.valueOf(collectTime));
        }
    }
}
