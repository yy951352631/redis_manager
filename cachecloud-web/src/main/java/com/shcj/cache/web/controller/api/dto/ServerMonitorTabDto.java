package com.shcj.cache.web.controller.api.dto;

import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

@Data
public class ServerMonitorTabDto implements Serializable {
    private static final long serialVersionUID = 1L;

    private String ip;
    private String date;
    private boolean empty;
    private List<ServerMonitorChartDto> charts = new ArrayList<>();
}
