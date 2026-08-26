package com.shcj.cache.log;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import com.shcj.cache.exception.BizException;
import com.shcj.cache.redis.AssistRedisService;
import com.shcj.cache.task.BaseTask;
import com.shcj.cache.task.constant.TaskConstants;
import com.shcj.cache.util.ConstUtils;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Marker;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.sql.Date;
import java.text.SimpleDateFormat;

@Component
public class TaskFlowRecordAppender extends AppenderBase<ILoggingEvent> {

    @Autowired
    private AssistRedisService assistRedisService;

    @Override
    protected void append(ILoggingEvent event) {
        if (event == null) {
            return;
        }
        Marker marker = event.getMarker();
        if (marker != null && marker.getName().equals(BaseTask.MARKER_NAME)) {
            String taskFlowId = event.getMDCPropertyMap().get(TaskConstants.TASK_STEP_FLOW_ID);
            if (StringUtils.isBlank(taskFlowId)) {
                return;
            }
            String customLog = generateCustomLog(event);
            String taskFlowIdKey = ConstUtils.getTaskFlowRedisKey(taskFlowId);
            assistRedisService.rpush(taskFlowIdKey, customLog);
        }
    }

    private String generateCustomLog(ILoggingEvent event) {
        Date date = new Date(event.getTimeStamp());
        String formatDate = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").format(date);
        String threadName = event.getThreadName();
        String logLevel = event.getLevel().toString();
        String className = event.getLoggerName();
        String simpleClassName = getSimpleClassName(className);
        String formatMessage = event.getFormattedMessage();
        if (event.getThrowableProxy() != null
                && event.getThrowableProxy().getCause() != null
                && BizException.class.getName().equals(event.getThrowableProxy().getCause().getClassName())) {
            formatMessage = event.getThrowableProxy().getCause().getMessage();
        }
        return String.format("%s {%s} %s %s  - %s", formatDate, threadName, logLevel, simpleClassName, formatMessage);
    }

    private String getSimpleClassName(String className) {
        int index = className.lastIndexOf(".");
        if (index >= 0) {
            return className.substring(index + 1);
        }
        return className;
    }

}
