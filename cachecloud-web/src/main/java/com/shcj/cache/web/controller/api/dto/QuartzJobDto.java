package com.shcj.cache.web.controller.api.dto;

import lombok.Data;

import java.io.Serializable;

@Data
public class QuartzJobDto implements Serializable {

    private static final long serialVersionUID = 1L;

    private String triggerName;
    private String triggerGroup;
    private String cron;
    private String nextFireDate;
    private String prevFireDate;
    private String startDate;
    private String triggerState;
    private boolean paused;
}
