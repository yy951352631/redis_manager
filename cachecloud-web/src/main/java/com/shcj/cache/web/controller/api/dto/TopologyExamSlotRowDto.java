package com.shcj.cache.web.controller.api.dto;

import lombok.Data;

@Data
public class TopologyExamSlotRowDto {
    private String hostPort;
    private String slotRanges;
    private int slotCount;
    private String percent;
}
