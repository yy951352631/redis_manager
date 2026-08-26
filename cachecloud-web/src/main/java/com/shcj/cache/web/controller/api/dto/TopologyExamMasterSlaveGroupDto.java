package com.shcj.cache.web.controller.api.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class TopologyExamMasterSlaveGroupDto {
    private TopologyExamInstanceItemDto master;
    private List<TopologyExamInstanceItemDto> slaves = new ArrayList<>();
    /** yes / no / no_slave，对齐 JSP「同一物理机」列 */
    private String samePhysicalMachine;
}
