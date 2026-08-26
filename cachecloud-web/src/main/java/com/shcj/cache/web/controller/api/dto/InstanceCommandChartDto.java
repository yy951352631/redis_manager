package com.shcj.cache.web.controller.api.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class InstanceCommandChartDto {
    private String commandName;
    private String startDate;
    private String endDate;
    private List<String> categories = new ArrayList<>();
    private List<InstanceCommandChartSeriesDto> series = new ArrayList<>();
}
