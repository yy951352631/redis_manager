package com.shcj.cache.dao;

import com.shcj.cache.entity.OfflineAnalysisRecord;
import org.apache.ibatis.annotations.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface OfflineAnalysisRecordDao {

    int save(OfflineAnalysisRecord record);

    OfflineAnalysisRecord getById(@Param("id") long id);

    List<OfflineAnalysisRecord> list(@Param("offset") int offset, @Param("limit") int limit);

    int count();

    /** 只更新进度相关字段，避免把体积很大的 result_json 反复写回 */
    int updateProgress(@Param("id") long id, @Param("status") int status,
                       @Param("progress") int progress, @Param("keyCount") long keyCount);

    int finish(@Param("id") long id, @Param("keyCount") long keyCount,
               @Param("resultJson") String resultJson);

    int fail(@Param("id") long id, @Param("errorMsg") String errorMsg);

    int deleteById(@Param("id") long id);

    /** 应用重启后把残留的"分析中"标记为失败——内存里的解析线程已经没了 */
    int markStaleAsFailed(@Param("errorMsg") String errorMsg);
}
