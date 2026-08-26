package com.shcj.cache.web.controller.api.dto;

import lombok.Data;

import java.io.Serializable;

@Data
public class DiagnosticAppOptionDto implements Serializable {
    private static final long serialVersionUID = 1L;

    private long appId;
    private int clusterNo;
    private int type;
    private String appName;
    private String versionName;
    private String typeDesc;
}
