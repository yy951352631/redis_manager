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

    int deleteBefore(@Param("before") Date before);
}
