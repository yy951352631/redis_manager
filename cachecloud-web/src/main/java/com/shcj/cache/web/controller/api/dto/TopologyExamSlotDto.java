package com.shcj.cache.web.controller.api.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class TopologyExamSlotDto {
    private int totalSlots;
    private int coveredSlots;
    private int lostSlotsCount;
    private boolean fetchOk;
    private boolean slotComplete;
    private boolean imbalance;
    private boolean zeroSlotMaster;
    private String imbalanceRatio;
    private int minSlotCount;
    private int maxSlotCount;
    private int masterCount;
    private boolean ok;
    private List<TopologyExamSlotLossRowDto> lossRows = new ArrayList<>();
    private List<TopologyExamSlotRowDto> distributionRows = new ArrayList<>();
}
