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

@Service
public class TaskApiService {

    @Autowired
    private TaskService taskService;

    @Autowired
    private AppService appService;

    @Autowired
    private AssistRedisService assistRedisService;

    public TaskListPageDto list(Long searchTaskId, Long appId, String className, Integer status,
                                int pageNo, int pageSize) {
        TaskSearch search = new TaskSearch();
        if (appId != null) {
            Long resolvedAppId = appService.resolveAppId(appId);
            if (resolvedAppId == null) {
                TaskListPageDto empty = new TaskListPageDto();
                empty.setPageNo(Math.max(pageNo, 1));
                empty.setPageSize(pageSize > 0 ? pageSize : 20);
                return empty;
            }
            search.setAppId(resolvedAppId);
        }
        if (className != null) {
            search.setClassName(className);
        }
        if (status != null) {
            search.setStatus(status);
        }

        List<TaskQueue> queues;
        int total;
        int safePageNo = Math.max(pageNo, 1);
        int safePageSize = pageSize > 0 ? Math.min(pageSize, 100) : 30;

        if (searchTaskId != null && searchTaskId > 0) {
            queues = taskService.getTaskQueueTreeByTaskId(searchTaskId);
            total = queues == null ? 0 : queues.size();
        } else {
            total = taskService.getTaskQueueCount(search);
            Page page = new Page(safePageNo, safePageSize, total);
            search.setPage(page);
            queues = taskService.getTaskQueueList(search);
        }

        if (queues == null) {
            queues = Collections.emptyList();
        }
        for (TaskQueue q : queues) {
            q.setTaskStepFlowList(taskService.getTaskStepFlowList(q.getId()));
        }
        queues.sort(Comparator.comparingLong(TaskQueue::getId).reversed());

        TaskListPageDto result = new TaskListPageDto();
        result.setPageNo(safePageNo);
        result.setPageSize(safePageSize);
        result.setTotalCount(total);
        result.setTotalPages(total == 0 ? 0 : (int) Math.ceil(total * 1.0 / safePageSize));
        for (TaskQueue q : queues) {
            result.getItems().add(toListItem(q));
        }
        return result;
    }

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

    public void execute(long taskId) {
        TaskQueue task = taskService.getTaskQueueById(taskId);
        if (task != null) {
            new Thread(() -> taskService.executeTask(taskId)).start();
        }
    }

    public void updateParam(long taskId, String prettyParam) {
        JSONObject json = JSONObject.parseObject(prettyParam);
        OperateResult result = taskService.updateParam(taskId, json.toJSONString());
        if (!result.isSuccess()) {
            throw new com.shcj.cache.exception.BizException(result.getMessage());
        }
    }

    public void updateFlowStatus(long flowId, int status) {
        OperateResult result = taskService.updateTaskFlowStatus(flowId, status);
        if (!result.isSuccess()) {
            throw new com.shcj.cache.exception.BizException(result.getMessage());
        }
    }

    private TaskListItemDto toListItem(TaskQueue q) {
        TaskListItemDto dto = new TaskListItemDto();
        dto.setId(q.getId());
        dto.setAppId(q.getAppId());
        AppDesc appDesc = appService.getByAppId(q.getAppId());
        if (appDesc != null) {
            dto.setClusterNo(com.shcj.cache.util.AppClusterNoSupport.displayClusterNo(appDesc));
        }
        dto.setClassName(q.getClassName());
        dto.setStatus(q.getStatus());
        dto.setStatusDesc(q.getStatusDesc());
        dto.setProgress(q.getProgress());
        dto.setProgressValue(q.getProgressValue());
        dto.setExecuteIpPort(q.getExecuteIpPort());
        dto.setFinished(q.isFinished());
        dto.setRunning(q.isRunning());
        if (q.getCreateTime() != null) {
            dto.setCreateTime(DateUtil.formatDate(q.getCreateTime(), "yyyy-MM-dd HH:mm:ss"));
        }
        if (q.getStartTime() != null) {
            dto.setStartTime(DateUtil.formatDate(q.getStartTime(), "yyyy-MM-dd HH:mm:ss"));
        }
        if (q.getEndTime() != null) {
            dto.setEndTime(DateUtil.formatDate(q.getEndTime(), "yyyy-MM-dd HH:mm:ss"));
        }
        dto.setCostSeconds(q.getCostSeconds());
        return dto;
    }
}
