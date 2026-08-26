package com.shcj.cache.web.controller.api.dto;

import lombok.Data;

@Data
public class AppOpsMachineItemDto {
    private String ip;
    private String realIp;
    private String roomName;
    private long machineMemory;
    private long usedMemory;
    private long memoryTotal;
    private long memoryFree;
    private int memoryAllocated;
    private String memoryUsageRatio;
    private String memoryAllocatedRatio;
    private String cpuUsage;
    private String traffic;
    private String load;
    private String modifyTime;
    private boolean virtual;
    private int instanceCount;
}
