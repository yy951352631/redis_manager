package com.shcj.cache.web.controller.api.dto;

import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 运维主面板的一次性聚合结果。
 *
 * <p>刻意做成单个接口返回：面板上有态势条、告警表、哨兵计数、6 个排行榜，
 * 拆成多个请求会让前端并发打十几次库，且各块数据的时间口径可能对不齐。</p>
 */
@Data
public class DashboardOpsDto implements Serializable {
    private static final long serialVersionUID = 1L;

    /** 数据时间口径，前端展示"截至 xx:xx" */
    private String dataTime;
    /** 排行榜统计窗口（分钟） */
    private int windowMinutes;

    /** 顶部 6 张 KPI 卡 */
    private KpiDto kpi = new KpiDto();
    /** 集群健康状态（环形图 + 明细表） */
    private ClusterHealthDto clusterHealth = new ClusterHealthDto();
    /** 指标趋势：按集群分线 */
    private TrendDto trend = new TrendDto();
    /** 资源使用汇总 */
    private List<ResourceGaugeDto> resources = new ArrayList<>();
    /** 慢命令 Top-N */
    private List<SlowCommandDto> slowCommands = new ArrayList<>();
    /** 最近操作 */
    private List<RecentOpDto> recentOps = new ArrayList<>();

    private PostureDto posture = new PostureDto();
    private List<ActiveAlertDto> activeAlerts = new ArrayList<>();
    private List<SentinelCounterDto> sentinels = new ArrayList<>();
    private List<TopBoardDto> boards = new ArrayList<>();

    /** 顶部 KPI：每张卡一个主值 + 两个副值 */
    @Data
    public static class KpiDto implements Serializable {
        private static final long serialVersionUID = 1L;
        private long instanceTotal;
        private long instanceOnline;
        private long instanceOffline;
        private long clusterTotal;
        private long masterCount;
        private long slaveCount;
        /** 已用内存（字节） */
        private long memUsed;
        /** 内存上限（字节）；0 表示未设 maxmemory */
        private long memTotal;
        private double memRatio;
        private long keyTotal;
        private long keyExpires;
        private double keyExpiresRatio;
        private long qps;
        private long qpsPeak;
        /** 窗口内命中率；窗口无任何查找时为 null，前端显示「—」而不是 0% */
        private Double hitRate;
        /** 命中率较昨日变化的百分点；无昨日数据时为 null */
        private Double hitRateDelta;
    }

    @Data
    public static class ClusterHealthDto implements Serializable {
        private static final long serialVersionUID = 1L;
        private int healthy;
        private int warning;
        private int abnormal;
        private int offline;
        private List<ClusterHealthRowDto> rows = new ArrayList<>();
    }

    @Data
    public static class ClusterHealthRowDto implements Serializable {
        private static final long serialVersionUID = 1L;
        private long appId;
        private String appName;
        /** healthy / warning / abnormal / offline */
        private String status;
        private String statusDesc;
        private int masterCount;
        private int slaveCount;
        private double memRatio;
        private String memDisplay;
    }

    /** 趋势图：x 轴时间戳 + 每集群一条线 */
    @Data
    public static class TrendDto implements Serializable {
        private static final long serialVersionUID = 1L;
        private List<String> times = new ArrayList<>();
        private List<TrendSeriesDto> series = new ArrayList<>();
    }

    @Data
    public static class TrendSeriesDto implements Serializable {
        private static final long serialVersionUID = 1L;
        private long appId;
        private String name;
        /** 与 times 等长，缺采样点为 null */
        private List<Double> values = new ArrayList<>();
    }

    /** 资源汇总的单个指标 */
    @Data
    public static class ResourceGaugeDto implements Serializable {
        private static final long serialVersionUID = 1L;
        private String key;
        private String label;
        /** 仪表盘型有百分比，数值型为 null */
        private Double percent;
        private String display;
        private String sub;
        /** 迷你趋势线数据 */
        private List<Double> spark = new ArrayList<>();
    }

    @Data
    public static class SlowCommandDto implements Serializable {
        private static final long serialVersionUID = 1L;
        private String command;
        private double avgCostMs;
        private long calls;
        /** 该命令累计耗时占全部慢查询的比例(%) */
        private double costRatio;
    }

    @Data
    public static class RecentOpDto implements Serializable {
        private static final long serialVersionUID = 1L;
        private String time;
        private String userName;
        private String action;
        private String target;
        private boolean success;
    }

    /** 全局态势条 */
    @Data
    public static class PostureDto implements Serializable {
        private static final long serialVersionUID = 1L;
        private int severeCount;
        private int warningCount;
        private int normalCount;
        /** 关闭了告警开关的集群——面板必须显式暴露，否则它们与"真的没问题"无法区分 */
        private List<UnmonitoredAppDto> unmonitoredApps = new ArrayList<>();
    }

    @Data
    public static class UnmonitoredAppDto implements Serializable {
        private static final long serialVersionUID = 1L;
        private long appId;
        private String appName;
    }

    /** 归并后的告警：同一(集群,节点,指标)只占一行 */
    @Data
    public static class ActiveAlertDto implements Serializable {
        private static final long serialVersionUID = 1L;
        private long appId;
        private String appName;
        private long instanceId;
        private String hostPort;
        /** 指标展示名，解析自告警内容 */
        private String metric;
        private int importantLevel;
        private String importantLevelDesc;
        /** true=仍在持续，false=判定为已恢复 */
        private boolean active;
        private String firstTime;
        private String lastTime;
        /** 持续时长，已恢复时为最后一次到首次的跨度 */
        private String duration;
        private int count;
        /** 最近一条原文，用于悬停查看细节 */
        private String latestContent;
    }

    /** 哨兵计数：这些指标平时恒为 0，非 0 即异常 */
    @Data
    public static class SentinelCounterDto implements Serializable {
        private static final long serialVersionUID = 1L;
        private String key;
        private String label;
        private long value;
        /** 贡献了非零值的节点，便于直接定位 */
        private List<String> hotNodes = new ArrayList<>();
    }

    /** Top-N 排行榜 */
    @Data
    public static class TopBoardDto implements Serializable {
        private static final long serialVersionUID = 1L;
        private String key;
        private String label;
        private String unit;
        /** 该榜的告警参考线，前端据此标红；无参考线时为 null */
        private Double threshold;
        /** 榜下小字说明，例如统计口径限制 */
        private String hint;
        /**
         * 点击行的跳转目标：
         * instance=节点详情，instanceClients=节点连接信息，appLatency=集群延迟监控
         */
        private String linkType;
        private List<TopBoardRowDto> rows = new ArrayList<>();
    }

    @Data
    public static class TopBoardRowDto implements Serializable {
        private static final long serialVersionUID = 1L;
        private long appId;
        private String appName;
        private long instanceId;
        private String hostPort;
        private double value;
        private String display;
        /** 是否越过该榜的参考线 */
        private boolean exceeded;
    }
}
