package com.shcj.cache.dao;

import com.shcj.cache.entity.AppAlertRecord;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;

/**
 * @Author: zengyizhao
 * @DateTime: 2021/9/3 13:38
 * @Description: 报警记录
 */
public interface AppAlertRecordDao {

    /**
     * 保存报警信息
     *
     * @param appAlertRecord
     * @return
     */
    public int save(AppAlertRecord appAlertRecord);

    /**
     * 批量保存报警信息
     *
     * @param appAlertRecordList
     * @return
     */
    public int batchSave(List<AppAlertRecord> appAlertRecordList);

    /**
     * 分页查询报警记录，所有条件均可为空。
     *
     * @param importantLevel 重要程度（0/1/2），null 表示不限
     * @param keyword        标题或内容关键字
     */
    List<AppAlertRecord> search(@Param("importantLevel") Integer importantLevel,
                                @Param("appId") Long appId,
                                @Param("ip") String ip,
                                @Param("keyword") String keyword,
                                @Param("startTime") Date startTime,
                                @Param("endTime") Date endTime,
                                @Param("offset") int offset,
                                @Param("limit") int limit);

    int count(@Param("importantLevel") Integer importantLevel,
              @Param("appId") Long appId,
              @Param("ip") String ip,
              @Param("keyword") String keyword,
              @Param("startTime") Date startTime,
              @Param("endTime") Date endTime);

    /**
     * 出现过报警的集群 id，供前端按集群名称筛选。
     */
    List<Long> listAlertAppIds();

    /**
     * 清理过期报警记录。
     */
    int deleteBefore(@Param("before") Date before);

}
