package com.shcj.cache.dao;

import com.shcj.cache.entity.OperationAudit;
import org.apache.ibatis.annotations.Param;
import org.springframework.stereotype.Repository;

import java.util.Date;
import java.util.List;

@Repository
public interface OperationAuditDao {

    int save(OperationAudit operationAudit);

    List<OperationAudit> search(@Param("userName") String userName,
                                @Param("module") String module,
                                @Param("keyword") String keyword,
                                @Param("success") Integer success,
                                @Param("startTime") Date startTime,
                                @Param("endTime") Date endTime,
                                @Param("offset") int offset,
                                @Param("limit") int limit);

    int count(@Param("userName") String userName,
              @Param("module") String module,
              @Param("keyword") String keyword,
              @Param("success") Integer success,
              @Param("startTime") Date startTime,
              @Param("endTime") Date endTime);

    List<String> listModules();

    /**
     * 删除 {@code before} 之前的审计记录，单次最多 {@code batchSize} 条。
     *
     * <p>分批是为了避免首次接上清理时一条语句删掉整段积压——那会是一个长事务。</p>
     */
    int deleteBefore(@Param("before") Date before, @Param("batchSize") int batchSize);
}
