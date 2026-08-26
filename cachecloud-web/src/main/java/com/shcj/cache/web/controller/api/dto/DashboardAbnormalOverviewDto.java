package com.shcj.cache.web.controller.api.dto;

import lombok.Data;

import java.util.List;

@Data
public class DashboardAbnormalOverviewDto {
    private String topologySearchDate;
    private int topologyAbnormalTotal;
    private int slotAbnormalTotal;
    private int instanceAbnormalTotal;
    private int abnormalOverviewTotal;
    private boolean hasSlotAbnormal;
    private boolean hasInstanceAbnormal;
    private List<DashboardAbnormalAppDto> apps;
}
