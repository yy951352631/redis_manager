package com.shcj.cache.redis.enums;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Redis报警配置枚举
 * @author leifu
 * @Date 2017年6月13日
 * @Time 下午5:34:42
 */
public enum RedisAlertConfigEnum {
    aof_current_size("aof_current_size", "aof当前尺寸(单位：MB)"),
    minute_aof_delayed_fsync("aof_delayed_fsync", "分钟aof阻塞个数"),
    client_biggest_input_buf("client_biggest_input_buf", "输入缓冲区最大buffer大小(单位：MB)"),
    client_longest_output_list("client_longest_output_list", "输出缓冲区最大队列长度"),
    instantaneous_ops_per_sec("instantaneous_ops_per_sec", "实时ops"),
    latest_fork_usec("latest_fork_usec", "上次fork所用时间(单位：微秒)"),
    mem_fragmentation_ratio("mem_fragmentation_ratio", "内存碎片率(检测大于500MB)"),
    rdb_last_bgsave_status("rdb_last_bgsave_status", "上一次bgsave状态"),
    minute_rejected_connections("rejected_connections", "分钟拒绝连接数"),
    minute_sync_partial_err("sync_partial_err", "分钟部分复制失败次数"),
    minute_sync_partial_ok("sync_partial_ok", "分钟部分复制成功次数"),
    minute_sync_full("sync_full", "分钟全量复制执行次数"),
    minute_total_net_input_bytes("total_net_input_bytes", "分钟网络输入流量(单位：MB)"),
    minute_total_net_output_bytes("total_net_output_bytes", "分钟网络输出流量(单位：MB)"),
    master_slave_offset_diff("master_slave_offset_diff", "主从节点偏移量差(单位：字节)"),
    cluster_state("cluster_state", "集群状态"),
    cluster_slots_ok("cluster_slots_ok", "集群成功分配槽个数"),
    used_cpu_sys("used_cpu_sys","系统cpu消耗(单位：秒)"),
    used_cpu_user("used_cpu_user","用户cpu消耗(单位：秒)"),
    used_cpu_sys_children("used_cpu_sys_children","系统子进程cpu消耗(单位：秒)"),
    used_cpu_user_children("used_cpu_user_children","用户子进程cpu消耗(单位：秒)"),
    // 以下为非 INFO 来源的报警项，由分钟报警作业按各自作用域评估
    instance_alive("instance_alive", "节点存活探测(Redis看INFO采集/哨兵PING)", AlertConfigScopeEnum.INSTANCE_STATE),
    node_mem_used_ratio("node_mem_used_ratio", "节点内存使用率(单位：%)", AlertConfigScopeEnum.APP_SCOPED),
    node_client_conn("node_client_conn", "节点客户端连接数", AlertConfigScopeEnum.APP_SCOPED),
    app_hit_percent("app_hit_percent", "集群平均命中率(单位：%)", AlertConfigScopeEnum.APP_SCOPED),
    other_default_common_config("other_default_common_config","除上述之外的其他默认通用配置")
    ;
    private final static List<RedisAlertConfigEnum> redisAlertConfigEnumList = new ArrayList<RedisAlertConfigEnum>();
    static {
        for (RedisAlertConfigEnum redisAlertConfigEnum : RedisAlertConfigEnum.values()) {
            redisAlertConfigEnumList.add(redisAlertConfigEnum);
        }
    }
    
    private final static Map<String, RedisAlertConfigEnum> redisAlertConfigEnumMap = new HashMap<String, RedisAlertConfigEnum>();
    static {
        for (RedisAlertConfigEnum redisAlertConfigEnum : RedisAlertConfigEnum.values()) {
            redisAlertConfigEnumMap.put(redisAlertConfigEnum.getValue(), redisAlertConfigEnum);
        }
    }
    
    private String value;
    
    private String info;

    private AlertConfigScopeEnum scope;
    

    public static List<RedisAlertConfigEnum> getRedisAlertConfigEnumList() {
        return redisAlertConfigEnumList;
    }

    public static Map<String, RedisAlertConfigEnum> getRedisAlertConfigEnumMap() {
        return redisAlertConfigEnumMap;
    }
    
    public static RedisAlertConfigEnum getRedisAlertConfig(String alertConfig) {
        return redisAlertConfigEnumMap.get(alertConfig);
    }

    private RedisAlertConfigEnum(String value, String info) {
        this(value, info, AlertConfigScopeEnum.INSTANCE_INFO);
    }

    private RedisAlertConfigEnum(String value, String info, AlertConfigScopeEnum scope) {
        this.value = value;
        this.info = info;
        this.scope = scope;
    }

    /**
     * 配置项作用域；未登记的配置项按 INFO 兜底处理。
     */
    public static AlertConfigScopeEnum getScope(String alertConfig) {
        RedisAlertConfigEnum configEnum = getRedisAlertConfig(alertConfig);
        return configEnum == null ? AlertConfigScopeEnum.INSTANCE_INFO : configEnum.getScope();
    }

    public AlertConfigScopeEnum getScope() {
        return scope;
    }

    public String getValue() {
        return value;
    }

    public String getInfo() {
        return info;
    }
    
}
