package com.shcj.cache.web.controller.api.dto;

import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 集群详情框架（M3）
 */
@Data
public class AppDetailDto implements Serializable {

    private static final long serialVersionUID = 1L;

    private long appId;
    private int clusterNo;
    private String appName;
    private String intro;
    private int type;
    private String typeDesc;
    private int runtimeStatus;
    private String runtimeStatusLabel;
    private String versionName;
    private boolean externalManaged;
    private String sourceLabel;
    private boolean installModuleFlag;
    private int instanceCount;
    private long mem;
    private double memUsePercent;
    private double hitPercent;
    private int appRunDays;
    private boolean hasOfflineInstances;
    private boolean opsEnabled;
    private String defaultTab;
    private List<AppDetailTabDto> tabs = new ArrayList<>();
}
