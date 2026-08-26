package com.shcj.cache.web.service;

import com.shcj.cache.entity.TriggerInfo;
import com.shcj.cache.schedule.SchedulerCenter;
import com.shcj.cache.web.controller.api.dto.QuartzJobDto;
import org.apache.commons.lang.StringUtils;
import org.quartz.TriggerKey;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;

@Service
public class QuartzApiService {

    @Resource
    private SchedulerCenter schedulerCenter;

    public List<QuartzJobDto> listJobs(String query) {
        List<TriggerInfo> triggers = StringUtils.isBlank(query)
                ? schedulerCenter.getAllTriggers()
                : schedulerCenter.getTriggersByNameOrGroup(query);
        List<QuartzJobDto> items = new ArrayList<>();
        if (triggers == null) {
            return items;
        }
        for (TriggerInfo trigger : triggers) {
            QuartzJobDto dto = new QuartzJobDto();
            dto.setTriggerName(trigger.getTriggerName());
            dto.setTriggerGroup(trigger.getTriggerGroup());
            dto.setCron(trigger.getCron());
            dto.setNextFireDate(trigger.getNextFireDate());
            dto.setPrevFireDate(trigger.getPrevFireDate());
            dto.setStartDate(trigger.getStartDate());
            dto.setTriggerState(trigger.getTriggerState());
            dto.setPaused("PAUSED".equalsIgnoreCase(trigger.getTriggerState()));
            items.add(dto);
        }
        return items;
    }

    public void pause(String name, String group) {
        schedulerCenter.pauseTrigger(new TriggerKey(name, group));
    }

    public void resume(String name, String group) {
        schedulerCenter.resumeTrigger(new TriggerKey(name, group));
    }

    public void remove(String name, String group) {
        schedulerCenter.unscheduleJob(new TriggerKey(name, group));
    }
}
