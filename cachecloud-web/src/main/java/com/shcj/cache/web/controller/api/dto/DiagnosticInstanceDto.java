package com.shcj.cache.web.controller.api.dto;

import lombok.Data;

import java.io.Serializable;

@Data
public class DiagnosticInstanceDto implements Serializable {
    private static final long serialVersionUID = 1L;

    private int id;
    private String ip;
    private int port;
    private String hostPort;
}
