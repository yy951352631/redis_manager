package com.shcj.cache.web.controller.api.dto;

import lombok.Data;

@Data
public class DashboardMachineStatsDto {
    private String machineRoom;
    private double machineMemUsedGb;
    private double machineMemTotalGb;
    private double machineMemUsedRatio;
    private double instanceMemUsedGb;
    private double instanceMemTotalGb;
    private double instanceMemUsedRatio;
}
