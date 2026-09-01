package com.shcj.cache.dao;

import com.shcj.cache.benchmark.BenchmarkTask;

import org.apache.ibatis.annotations.Param;
import org.springframework.stereotype.Repository;

import java.util.Date;
import java.util.List;

@Repository
public interface BenchmarkDao {

    int save(BenchmarkTask task);

    int update(BenchmarkTask task);

    BenchmarkTask get(@Param("id") long id);

    List<BenchmarkTask> search(@Param("appId") Long appId, @Param("offset") int offset, @Param("limit") int limit);

    int count(@Param("appId") Long appId);

    int delete(@Param("id") long id);

    int deleteBefore(@Param("before") Date before, @Param("batchSize") int batchSize);
}
