package com.shcj.cache.entity;

import lombok.Data;

import java.sql.Timestamp;
import java.text.DecimalFormat;
import java.util.Map;

/**
 * 实例的简化的统计信息
 *
 * User: lingguo
 */
@Data
public class InstanceStats {
    /* id */
    private long id;

    /* 实例id */
    private long instId;

    /* app id */
    private long appId;

    /* host id */
    private long hostId;

    /* ip地址 */
    private String ip;

    /* port */
    private int port;

    /* 主从，1主2从 */
    private byte role;

    /* 启用实例时设置的内存，单位：byte */
    private long maxMemory;

    /* 实例当前已用的内存，单位：byte */
    private long usedMemory;

    /*
     * 实例内存使用率
     */
    private double memUsePercent;

    /* 当前的item数 */
    private long currItems;

    /* 当前的连接数 */
    private int currConnections;

    /* 未命中数*/
    private long misses;

    /* 命中数 */
    private long hits;

    /* 开始收集时间 */
    private Timestamp createTime;

    /* 最后更新时间 */
    private Timestamp modifyTime;
    
    /**
     * 内存碎片率
     */
    private double memFragmentationRatio;
    
    /**
     * aof阻塞次数
     */
    private int aofDelayedFsync;

    /** 实例已运行秒数，采集时从 INFO Server.uptime_in_seconds 落库 */
    private long uptimeInSeconds;

    private boolean isRun;

    /**
     * 实例相关全部统计指标
     */
    private Map<String,Object> infoMap;

    /** maxmemory / 主机内存都拿不到时的展示默认上限 */
    private static final long DEFAULT_DISPLAY_MAX_BYTES = 999L * 1024 * 1024 * 1024;

    /**
     * 展示用容量上限：
     * 1) 已配置 maxmemory → 用 maxmemory
     * 2) 未配置时 → 用 INFO Memory.total_system_memory（主机内存）
     * 3) 都没有 → 默认 999G
     */
    public long getEffectiveMaxMemory() {
        if (maxMemory > 0) {
            return maxMemory;
        }
        long systemMemory = resolveTotalSystemMemoryFromInfo();
        if (systemMemory > 0) {
            return systemMemory;
        }
        return DEFAULT_DISPLAY_MAX_BYTES;
    }

    @SuppressWarnings("unchecked")
    private long resolveTotalSystemMemoryFromInfo() {
        if (infoMap == null || infoMap.isEmpty()) {
            return 0L;
        }
        Object memorySection = infoMap.get("Memory");
        if (!(memorySection instanceof Map)) {
            return 0L;
        }
        Object value = ((Map<String, Object>) memorySection).get("total_system_memory");
        if (value == null) {
            return 0L;
        }
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    public double getMemUsePercent() {
        long effectiveMax = getEffectiveMaxMemory();
        if (effectiveMax <= 0 || usedMemory <= 0) {
            return 0.0D;
        }
        double percent = 100.0 * usedMemory / effectiveMax;
        if (percent > 100) {
            percent = 100;
        }
        DecimalFormat df = new DecimalFormat("##.##");
        return Double.parseDouble(df.format(percent));
    }
    
    /**
     * 命中率
     * @return
     */
    public String getHitPercent(){
		long totalHits = hits + misses;
		if (totalHits <= 0) {
			return "无命令执行";
		}
		double percent = 100 * (double) hits / totalHits;
		DecimalFormat df = new DecimalFormat("##.##");
		return df.format(percent) + "%";
    }

    public Timestamp getCreateTime() {
        if(createTime != null){
            return (Timestamp) createTime.clone();
        }
        return null;
    }

    public void setCreateTime(Timestamp createTime) {
        this.createTime = (Timestamp) createTime.clone();
    }

    public Timestamp getModifyTime() {
        return (Timestamp) modifyTime.clone();
    }

    public void setModifyTime(Timestamp modifyTime) {
        this.modifyTime = (Timestamp) modifyTime.clone();
    }
}
