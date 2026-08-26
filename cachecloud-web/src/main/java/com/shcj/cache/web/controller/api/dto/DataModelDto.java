package com.shcj.cache.web.controller.api.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 「数据模型」页四个主题共用的返回结构。
 *
 * <p>每个主题都带上 {@code sampleCount} 与 {@code sampleNote}——这一页的价值全在于从数据推断结论，
 * 3 个点也能画出一条很有说服力的趋势线，不把样本量摆出来，读图的人无从判断该给它多少信任。
 */
@Data
public class DataModelDto {

    /** 参与统计的样本量（散点为实例数，柱状为分组数） */
    private int sampleCount;
    /** 参与统计的实例数 */
    private int instanceCount;
    /** 数据覆盖的天数 */
    private double coverDays;
    /** 样本不足时的说明，充足时为 null */
    private String sampleNote;
    /** 完全无数据时的空态说明 */
    private String emptyReason;

    // ---- 分组柱状（命令耗时基线）----
    /** 图例，即分组名，如 ARM / 6.2 */
    private List<String> groups = new ArrayList<String>();
    /** X 轴类目，如命令名 */
    private List<String> categories = new ArrayList<String>();
    /** 与 groups 一一对应，每项长度等于 categories 长度；无数据处为 null */
    private List<List<Double>> series = new ArrayList<List<Double>>();
    /** 参考线，如现行阈值；无则为 null */
    private List<DataModelReferenceLine> referenceLines = new ArrayList<DataModelReferenceLine>();

    // ---- 散点 ----
    private List<DataModelScatterSeries> scatterSeries = new ArrayList<DataModelScatterSeries>();
    private String xAxisName;
    private String yAxisName;
    /** 相关性结论；样本不足或无相关时仍返回，由 strength 区分 */
    private DataModelCorrelation correlation;

    // ---- 横向对比 ----
    private List<DataModelComparisonRow> comparisonRows = new ArrayList<DataModelComparisonRow>();

    @Data
    public static class DataModelReferenceLine {
        private String label;
        private double value;
    }

    @Data
    public static class DataModelScatterSeries {
        /** 系列名，如架构 ARM */
        private String name;
        /** 每项为 {x, y}，另附实例标识便于 tooltip */
        private List<DataModelPoint> points = new ArrayList<DataModelPoint>();
    }

    @Data
    public static class DataModelPoint {
        private double x;
        private double y;
        private String label;
    }

    @Data
    public static class DataModelCorrelation {
        private int sampleCount;
        private Double coefficient;
        private Double slope;
        /** NONE / WEAK / MODERATE / STRONG / INSUFFICIENT */
        private String strength;
        /** 直接可展示的一句话结论 */
        private String conclusion;
        /** 拟合线两端点，样本不足时为空 */
        private List<DataModelPoint> line = new ArrayList<DataModelPoint>();
    }

    @Data
    public static class DataModelComparisonRow {
        private long appId;
        private String appName;
        private String typeDesc;
        private String osArch;
        private String majorVersion;
        private int instanceCount;
        private Double hitPercent;
        private Double memUsePercent;
        private Double qps;
        private Double memFragRatio;
        private Double avgKeyBytes;
        private Double connectedClients;
        private Long keysCount;
        private Double usedMemoryMb;
    }
}
