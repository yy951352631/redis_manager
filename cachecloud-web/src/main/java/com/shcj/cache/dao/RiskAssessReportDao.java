package com.shcj.cache.dao;

import com.shcj.cache.entity.RiskAssessDimension;
import com.shcj.cache.entity.RiskAssessReport;
import org.apache.ibatis.annotations.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RiskAssessReportDao {

    int saveReport(RiskAssessReport report);

    int batchSaveDimensions(List<RiskAssessDimension> dimensions);

    RiskAssessReport getReport(@Param("id") long id);

    /** 每个集群最近一次评估结果，供总览清单展示 */
    List<RiskAssessReport> listLatestByApps(@Param("appIds") List<Long> appIds);

    List<RiskAssessReport> listHistory(@Param("appId") long appId, @Param("limit") int limit);

    List<RiskAssessDimension> listDimensions(@Param("reportId") long reportId);

    /** 防重复：取该集群在指定时间之后、同窗口的最近一份报告 */
    RiskAssessReport getRecentReport(@Param("appId") long appId,
                                     @Param("windowHours") int windowHours,
                                     @Param("after") java.util.Date after);
}
