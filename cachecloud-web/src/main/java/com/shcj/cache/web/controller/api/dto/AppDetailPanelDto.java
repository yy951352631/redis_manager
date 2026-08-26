package com.shcj.cache.web.controller.api.dto;

import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

@Data
public class AppDetailPanelDto implements Serializable {
    private static final long serialVersionUID = 1L;

    private boolean hasAuth;
    private AppInfoSectionDto appInfo = new AppInfoSectionDto();
    private AppAlertConfigFormDto alertConfig = new AppAlertConfigFormDto();
    private List<AppAlertMetricDto> alertMetrics = new ArrayList<>();
    private List<AppDetailUserDto> users = new ArrayList<>();

    @Data
    public static class AppAlertConfigFormDto implements Serializable {
        private static final long serialVersionUID = 1L;
        private int isAccessMonitor;
    }

    @Data
    public static class AppInfoSectionDto implements Serializable {
        private static final long serialVersionUID = 1L;
        private long appId;
        private int clusterNo;
        private String appName;
        private String typeDesc;
        private String importantLevelLabel;
        private String alertUsersText;
        private String officerText;
        private String officerId;
        private String memTotalGb;
        private int machineNum;
        private int masterNum;
        private int slaveNum;
        private String intro;
        private String masterName;
        private String hasSentinelPwd;
    }

    @Data
    public static class AppAlertMetricDto implements Serializable {
        private static final long serialVersionUID = 1L;
        private int id;
        private String alertKey;
        private String threshold;
        private String cycle;
    }

    @Data
    public static class AppDetailUserDto implements Serializable {
        private static final long serialVersionUID = 1L;
        private long id;
        private String name;
        private String chName;
        private String email;
        private String mobile;
        private String company;
        private boolean alert;
        private int type;
    }
}
