package com.shcj.cache.web.controller.api.dto;

import lombok.Data;

import java.io.Serializable;

@Data
public class AppAlertConfigUpdateRequestDto implements Serializable {
    private static final long serialVersionUID = 1L;
    private int isAccessMonitor;
}
