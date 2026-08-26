package com.shcj.cache.web.controller.api.dto;

import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

@Data
public class AppMachineTopologyDto implements Serializable {
    private static final long serialVersionUID = 1L;

    private int appType;
    private boolean sentinelApp;
    private String groupLabel;
    private int groupCount;
    private List<String> warnings = new ArrayList<>();
    private List<MachineRowDto> machines = new ArrayList<>();

    @Data
    public static class MachineRowDto implements Serializable {
        private static final long serialVersionUID = 1L;
        private String ip;
        private List<TopologyNodeDto> nodes = new ArrayList<>();
    }

    @Data
    public static class TopologyNodeDto implements Serializable {
        private static final long serialVersionUID = 1L;
        private long id;
        private String ip;
        private int port;
        private String hostPort;
        private int groupId;
        private String roleDesc;
        private int instanceType;
        private int status;
        private boolean offline;
    }
}
