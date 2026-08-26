package com.shcj.cache.web.controller.api.dto;

import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

@Data
public class AppTopologyInstanceDto implements Serializable {
    private static final long serialVersionUID = 1L;

    private long id;
    private String ip;
    private int port;
    private String hostPort;
    private long hostId;
    private int status;
    private String statusDesc;
    private String roleDesc;
    private long masterInstanceId;
    private long usedMemory;
    private long maxMemory;
    private double memUsePercent;
    private long currItems;
    private int currConnections;
    private String hitPercent;
    private double memFragmentationRatio;
    private boolean external;
    private int instanceType;
    private String updateTimeDesc;
    private long effectiveMaxMemory;
    private boolean masterStar;
    private List<String> modules = new ArrayList<>();
}
