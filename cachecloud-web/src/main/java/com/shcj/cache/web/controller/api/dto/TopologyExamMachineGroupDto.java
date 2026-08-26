package com.shcj.cache.web.controller.api.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class TopologyExamMachineGroupDto {
    private String realIp;
    private String room;
    private String rack;
    private int instanceCount;
    private List<TopologyExamInstanceItemDto> instances = new ArrayList<>();
}
