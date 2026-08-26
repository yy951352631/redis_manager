package com.shcj.cache.web.controller.api.dto;

import lombok.Data;

import java.io.Serializable;

@Data
public class AppDetailUpdateRequestDto implements Serializable {
    private static final long serialVersionUID = 1L;
    private String appName;
    private String intro;
    private String officerId;
}
