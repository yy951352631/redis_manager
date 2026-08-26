package com.shcj.cache.dao;

import com.shcj.cache.entity.ExternalRedis;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface ExternalRedisDao {

    List<ExternalRedis> listAll();

    ExternalRedis getById(@Param("id") long id);

    ExternalRedis getByAppId(@Param("appId") long appId);

    int save(ExternalRedis externalRedis);

    int updateStatus(@Param("id") long id, @Param("status") int status);

    /** 追加/调整纳管节点清单后回写登记记录 */
    int updateInstanceInfo(@Param("appId") long appId, @Param("instanceInfo") String instanceInfo);

    /** 集群改名/改描述后同步登记记录，避免与 app_desc 脱节 */
    int updateNameAndIntro(@Param("appId") long appId, @Param("name") String name,
                           @Param("intro") String intro);

    int deleteByAppId(@Param("appId") long appId);
}
