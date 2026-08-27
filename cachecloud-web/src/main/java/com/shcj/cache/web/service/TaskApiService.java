package com.shcj.cache.web.service;

import com.alibaba.fastjson.JSONObject;
import com.shcj.cache.constant.OperateResult;
import com.shcj.cache.entity.AppDesc;
import com.shcj.cache.redis.AssistRedisService;
import com.shcj.cache.task.TaskService;
import com.shcj.cache.task.entity.TaskQueue;
import com.shcj.cache.task.entity.TaskSearch;
import com.shcj.cache.task.entity.TaskStepFlow;
import com.shcj.cache.util.ConstUtils;
import com.shcj.cache.web.controller.api.dto.*;
import com.shcj.cache.web.util.DateUtil;
import com.shcj.cache.web.util.Page;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang.math.NumberUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 任务流详情。
 *
 * <p>任务管理页面已下线，列表与手工重跑随之移除；这里只保留「键值分析」用来
 * 查询分析任务进度的 getFlow。底层任务框架仍在为数据迁移、键值分析、故障诊断服务。</p>
 */
@Service
public class TaskApiService {

    @Autowired
    private TaskService taskService;

    @Autowired
    private AppService appService;

    @Autowired
    private AssistRedisService assistRedisService;

    public TaskFlowDetailDto getFlow(long taskId) {
        TaskQueue task = taskService.getTaskQueueById(taskId);
        if (task == null) {
            return null;
        }
        List<TaskStepFlow> flows = taskService.getTaskStepFlowList(taskId);
        task.setTaskStepFlowList(flows);
        TaskStepFlow current = taskService.getCurrentTaskStepFlow(taskId);

        TaskFlowDetailDto dto = new TaskFlowDetailDto();
        dto.setTaskId(taskId);
        dto.setAppId(task.getAppId());
        AppDesc app = appService.getByAppId(task.getAppId());
        dto.setAppName(app != null ? app.getName() : "");
        dto.setClassName(task.getClassName());
        dto.setStatus(task.getStatus());
        dto.setStatusDesc(task.getStatusDesc());
        dto.setProgress(task.getProgress());
        dto.setProgressValue(task.getProgressValue());
        dto.setPrettyParam(task.getPrettyParam());
        dto.setCurrentStep(current != null ? current.getStepName() : "");

        if (CollectionUtils.isNotEmpty(flows)) {
            for (TaskStepFlow flow : flows) {
                TaskFlowStepDto step = new TaskFlowStepDto();
                step.setId(flow.getId());
                step.setStepName(flow.getStepName());
                step.setOrderNo(flow.getOrderNo());
                step.setStatus(flow.getStatus());
                step.setStatusDesc(flow.getStatusDesc());
                if (flow.getStartTime() != null) {
                    step.setStartTime(DateUtil.formatDate(flow.getStartTime(), "yyyy-MM-dd HH:mm:ss"));
                }
                if (flow.getEndTime() != null) {
                    step.setEndTime(DateUtil.formatDate(flow.getEndTime(), "yyyy-MM-dd HH:mm:ss"));
                }
                String key = ConstUtils.getTaskFlowRedisKey(String.valueOf(flow.getId()));
                List<String> logs = assistRedisService.lrange(key, 0, -1);
                if (logs != null) {
                    step.setLogs(logs);
                }
                dto.getSteps().add(step);
            }
        }
        return dto;
    }

}
