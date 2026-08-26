package com.shcj.cache.web.controller.api.dto;

import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

@Data
public class ServerMonitorChartDto implements Serializable {
    private static final long serialVersionUID = 1L;

    private String title;
    private String subtitle;
    private List<String> categories = new ArrayList<>();
    private List<ServerMonitorChartSeriesDto> series = new ArrayList<>();
    private int yAxisCount = 1;
}
