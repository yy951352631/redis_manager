package com.shcj.cache.web.controller.api.dto;

import lombok.Data;

import java.io.Serializable;

@Data
public class AppDailyDto implements Serializable {
    private static final long serialVersionUID = 1L;

    private String dailyDate;
    private boolean hasData;
    /** 是否来自 app_daily 表持久化记录 */
    private boolean persisted;
    /** 是否为测试应用（定时任务不会为其写入日报） */
    private boolean testApp;
    /** 是否有客户端 SDK 上报数据 */
    private boolean clientMetricsAvailable;
    private long bigKeyTimes;
    private String bigKeyInfo;
    private String valueSizeDistributeDescHtml;
    private long slowLogCount;
    private long latencyCount;
    private long clientExceptionCount;
    private long clientCmdCount;
    private double clientAvgCmdCost;
    private long clientConnExpCount;
    private double clientAvgConnExpCost;
    private long clientCmdExpCount;
    private double clientAvgCmdExpCost;
    private long maxMinuteClientCount;
    private long avgMinuteClientCount;
    private long maxMinuteCommandCount;
    private long avgMinuteCommandCount;
    private double avgHitRatio;
    private double minMinuteHitRatio;
    private double maxMinuteHitRatio;
    private long avgUsedMemory;
    private long maxUsedMemory;
    private long expiredKeysCount;
    private long evictedKeysCount;
    private long avgObjectSize;
    private long maxObjectSize;
    private double avgMinuteNetInputByte;
    private double maxMinuteNetInputByte;
    private double avgMinuteNetOutputByte;
    private double maxMinuteNetOutputByte;
}
