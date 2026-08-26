package com.shcj.cache.dao;

import org.apache.ibatis.annotations.Param;

public interface AppKeyAnalysisStatsDao {

    String getStatsJson(@Param("auditId") long auditId);

    void upsertStats(@Param("appId") long appId, @Param("auditId") long auditId, @Param("statsJson") String statsJson);

    int deleteByAuditId(@Param("auditId") long auditId);
}
