package com.shcj.cache.web.controller.api.dto;

import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

@Data
public class AppStatOverviewDto implements Serializable {
    private static final long serialVersionUID = 1L;

    private long appId;
    private String appName;
    private double mem;
    private double memUsePercent;
    private double hitPercent;
    private int instanceCount;
    private String startDate;
    private String endDate;
    private List<ChartPointDto> top5Commands = new ArrayList<>();
    private List<AppCommandClimaxDto> top5Climax = new ArrayList<>();

    private int conn;
    private String versionName;
    private String typeDesc;
    private int masterNum;
    private int slaveNum;
    private long currentObjNum;
    private String statusDesc;
    private int machineNum;
    private boolean statRangeClamped;
}
