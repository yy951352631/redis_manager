package com.shcj.cache.web.controller.api.dto;

import lombok.Data;

import java.io.Serializable;

@Data
public class ServerMonitorServerInfoDto implements Serializable {
    private static final long serialVersionUID = 1L;

    private String ip;
    private String host;
    private int cpus;
    private String nmon;
    private String maxFile;
    private String maxProcs;
    private String cpuModel;
    private String dist;
    private String kernel;
}
