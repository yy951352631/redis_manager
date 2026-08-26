package com.shcj.cache.dao;

import com.shcj.cache.entity.InstanceCommandLatency;
import org.apache.ibatis.annotations.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface InstanceCommandLatencyDao {

    int batchSaveMinute(List<InstanceCommandLatency> list);

    List<InstanceCommandLatency> listMinuteByAppAndRange(@Param("appId") long appId,
                                                         @Param("beginTime") long beginTime,
                                                         @Param("endTime") long endTime);

    int batchSaveHour(List<InstanceCommandLatency> list);

    List<InstanceCommandLatency> listHourByAppAndRange(@Param("appId") long appId,
                                                       @Param("beginTime") long beginTime,
                                                       @Param("endTime") long endTime);

    int deleteMinuteBefore(@Param("collectTime") long collectTime);

    int deleteHourBefore(@Param("collectTime") long collectTime);
}
