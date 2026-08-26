package com.shcj.cache.web.service;

import com.shcj.cache.entity.AppDesc;
import com.shcj.cache.entity.DiagnosticTaskRecord;
import com.shcj.cache.entity.InstanceInfo;

import java.util.List;
import java.util.Map;

/**
 * @Author: rucao
 * @Date: 2020/6/5 5:33 下午
 */
public interface DiagnosticToolService {
    Map<Long, List<InstanceInfo>> getAppInstancesMap(List<AppDesc> appDescList);

    List<DiagnosticTaskRecord> getDiagnosticTaskRecords(Long appId, Long parentTaskId, Long auditId, Integer type, Integer status);

    List<String> getScanDiagnosticData(String redisKey);

    List<String> getScanCleanDiagnosticData(String redisKey);

    Map<String, String> getDiagnosticDataMap(String redisKey,int type,boolean err);

    List<String> getSampleScanData(Long appId, String nodes, String pattern);
}
