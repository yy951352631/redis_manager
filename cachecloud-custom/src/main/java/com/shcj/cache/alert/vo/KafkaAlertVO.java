package com.shcj.cache.alert.vo;

import lombok.Data;

/**
 * Kafka告警对象
 *
 * @author zoushunqing 2023/2/16 19:28
 * @since Dev_1.0.1
 */
@Data
public class KafkaAlertVO {

    public static final String WARN = "50";
    public static final String INFO = "20";

    /**
     * 原始告警内容 是 主机192.168.1.1的总CPU使用率达到93.33%
     */
    private String content;
    /**
     * 原始等级	是	包括严重、一般、通知与恢复
     * 严重	50
     * 一般	40
     * 通知	20	不需要进行电话通知的告警
     * 恢复	0	有告警恢复事件需要把恢复告警的级别设置为0
     */
    private String severity;
    /**
     * 原始告警时间	是	2022-04-27 17:11:00；1535358529000（API对接时使用时间戳）
     * 告警时间建议使用：yyyy-MM-dd HH:mm:ss(eg.2022-04-27 17:11:00)
     */
    private String eventTime;
    /**
     * 告警对象	是	IP、IP:PORT、业务名称、交易码等
     * 目前告警平台接入的告警中，告警对象包含：IP、IP+端口、IP+磁盘挂载点、业务名称、交易码、F5地址池名称。
     */
    private String object;
    /**
     * 告警来源	是	zabbix、Prometheus
     */
    private String location;
    /**
     * 告警标题	否	CPU使用率告警
     */
    private String title;
    /**
     * IP地址	否	192.168.0.1
     */
    private String host;
    /**
     * 主机名称	否	vm01
     */
    private String hostname;
    /**
     * 告警对象名称	否
     */
    private String objectName;
    /**
     * 告警对象类型	否	IP、主机、交易码、业务等
     */
    private String objectType;
    /**
     * 告警对象实例	否  如InstanceId
     */
    private String objectInstance;
    /**
     * 压缩标识	否	如：${host}+${object}+${objectInstance}+${metricname}
     */
    private String mergeKey;
    /**
     * 优先级	否	1级到5级，1级优先级别最高
     */
    private String priority;
    /**
     * 告警系统名称	否	如：Redis管理平台，XX业务系统
     */
    private String business;
    /**
     * 业务系统英文名称	否	ESBN
     */
    private String businessEnName;
    /**
     * 业务编号	否	F35
     */
    private String businessNo;
    /**
     * 服务对象	否	NAPP-SRV-00000593
     */
    private String application;
    /**
     * 服务名称	否	Redis管理平台
     */
    private String applicationName;
    /**
     * 分类	否	基础架构-操作系统-CPU
     */
    private String type;
    /**
     * 指标名称	否	NJCB_LZ_CpuBusyPct_C
     */
    private String metricName;
    /**
     * 指标值	否	99
     */
    private String metricValue;
    /**
     * 指标单位	否	%
     */
    private String metricUnit;
    /**
     * 数据中心	否	浦口数据中心
     */
    private String datacenter;
    /**
     * 原始告警ID	否	83297
     */
    private String originId;
    /**
     * 原始告警恢复ID	否	83297
     */
    private String recoveryOriginId;
    /**
     * 通知人	否	XXX
     */
    private String notifier;
    /**
     * 通知人工号	否	1009951
     */
    private String noticeNo;
    /**
     * 通知人电话	否	1234567890
     */
    private String noticePhone;

}
