package com.shcj.cache.web.controller.api.dto;

import lombok.Data;

import java.io.Serializable;

@Data
public class DiagnosticSubmitRequestDto implements Serializable {
    private static final long serialVersionUID = 1L;

    private String tabTag;
    private Long appId;
    private Long auditId;
    private String nodes;
    private String params;
}
