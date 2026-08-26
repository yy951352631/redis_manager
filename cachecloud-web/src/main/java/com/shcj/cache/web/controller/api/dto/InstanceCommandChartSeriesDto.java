package com.shcj.cache.web.controller.api.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class InstanceCommandChartSeriesDto {
    private String name;
    private List<Long> data = new ArrayList<>();
}
