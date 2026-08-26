package com.shcj.cache.dao;

import com.shcj.cache.entity.DiagnosticTaskRecord;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * @Author: rucao
 * @Date: 2020/6/9 16:49
 */
public interface DiagnosticTaskRecordDao {

    long insertDiagnosticTaskRecord(DiagnosticTaskRecord diagnosticTaskRecord);

    int updateDiagnosticStatus(@Param("id") long id, @Param("redisKey") String redisKey, @Param("status") int status, @Param("cost") long cost);

    DiagnosticTaskRecord getById(@Param("id") long id);

    int deleteById(@Param("id") long id);

    int updateDeletePreview(@Param("id") long id, @Param("redisKey") String redisKey,
                            @Param("deleteStatus") int deleteStatus, @Param("resultCount") long resultCount,
                            @Param("status") int status, @Param("cost") long cost);

    int markDeleteStarted(@Param("id") long id);

    int updateDeleteFinished(@Param("id") long id, @Param("deleteStatus") int deleteStatus,
                             @Param("resultCount") long resultCount, @Param("status") int status,
                             @Param("cost") long cost);

    List<DiagnosticTaskRecord> getDiagnosticTaskRecords(@Param("appId") Long appId, @Param("parentTaskId") Long parentTaskId, @Param("auditId") Long auditId, @Param("type") Integer type, @Param("status") Integer status);
}
