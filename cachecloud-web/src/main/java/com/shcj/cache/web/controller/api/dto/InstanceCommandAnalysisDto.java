package com.shcj.cache.web.controller.api.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class InstanceCommandAnalysisDto {
    private long instanceId;
    private String startDate;
    private String endDate;
    private List<String> commands = new ArrayList<>();
}
