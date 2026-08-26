package com.shcj.cache.web.controller.api.dto;

import lombok.Data;

@Data
public class AppPasswordDto {
    private long appId;
    private String appPassword;
    private String customPassword;
}
