package com.shcj.cache.web.controller.api.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class InstanceDetailDto {
    private long instanceId;
    private long appId;
    private String appName;
    private String hostPort;
    private int type;
    private String typeDesc;
    private int status;
    private String statusDesc;
    private boolean nodeManageContext;
    private String defaultTab;
    private List<InstanceDetailTabDto> tabs = new ArrayList<>();
}
