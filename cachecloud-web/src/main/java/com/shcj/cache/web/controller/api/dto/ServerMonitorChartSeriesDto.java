package com.shcj.cache.web.controller.api.dto;

import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

@Data
public class ServerMonitorChartSeriesDto implements Serializable {
    private static final long serialVersionUID = 1L;

    private String name;
    private String type = "line";
    private Integer yAxisIndex;
    private List<Number> data = new ArrayList<>();
}
