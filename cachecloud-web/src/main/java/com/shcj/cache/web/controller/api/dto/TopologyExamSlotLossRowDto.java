package com.shcj.cache.web.controller.api.dto;

import lombok.Data;

@Data
public class TopologyExamSlotLossRowDto {
    private String hostPort;
    private String segments;
}
