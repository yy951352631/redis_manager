package com.shcj.cache.entity;

import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 离线数据分析记录：一次 RDB 文件上传对应一行。
 *
 * <p>结果不复用 app_key_analysis_stats——那张表以 auditId 为主键且 app_id 为无符号列，
 * 离线文件不属于任何集群，硬塞进去会污染集群维度的分析历史。这里自带 result_json，
 * 内容结构与 KeyAnalysisStatsSnapshotDto 完全一致，因此前端可以共用同一套渲染。
 */
@Data
public class OfflineAnalysisRecord implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 待分析 */
    public static final int STATUS_PENDING = 0;
    /** 分析中 */
    public static final int STATUS_RUNNING = 1;
    /** 已完成 */
    public static final int STATUS_DONE = 2;
    /** 失败 */
    public static final int STATUS_FAILED = 3;

    private Long id;
    private String fileName;
    private long fileSize;
    private String filePath;
    private String fileType;
    private int status;
    private int progress;
    private long keyCount;
    private String errorMsg;
    private String resultJson;
    private String userName;
    private Date createTime;
    private Date updateTime;
}
