package com.shcj.cache.web.controller.api.dto;

import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

@Data
public class TaskFlowStepDto implements Serializable {
    private static final long serialVersionUID = 1L;

    private long id;
    private String stepName;
    private int orderNo;
    private int status;
    private String statusDesc;
    private String startTime;
    private String endTime;
    private List<String> logs = new ArrayList<>();
}
