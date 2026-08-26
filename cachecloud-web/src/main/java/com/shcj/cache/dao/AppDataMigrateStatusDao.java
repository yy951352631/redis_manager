package com.shcj.cache.dao;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import com.shcj.cache.entity.AppDataMigrateSearch;
import com.shcj.cache.entity.AppDataMigrateStatus;

/**
 * 迁移状态Dao
 * 
 * @author leifu
 * @Date 2016-6-9
 * @Time 下午5:25:53
 */
public interface AppDataMigrateStatusDao {

    int save(AppDataMigrateStatus appDataMigrateStatus);

    AppDataMigrateStatus get(@Param("id") long id);

    AppDataMigrateStatus getByMigrateId(@Param("migrateId") long migrateId);

    int updateStatus(@Param("id") long id, @Param("status") int status);

    int resetForResync(@Param("id") long id, @Param("machinePort") int machinePort,
                       @Param("userId") long userId, @Param("logPath") String logPath,
                       @Param("configPath") String configPath);

    int delete(@Param("id") long id);

    int updateVersions(@Param("id") long id, @Param("sourceVersion") String sourceVersion,
                       @Param("targetVersion") String targetVersion);

    int getMigrateTaskCount(@Param("appDataMigrateSearch") AppDataMigrateSearch appDataMigrateSearch);

    List<AppDataMigrateStatus> search(@Param("appDataMigrateSearch") AppDataMigrateSearch appDataMigrateSearch);

    List<Long> getAllOnMigrateId();
}
