package com.shcj.cache.web.controller.api.dto;

import lombok.Data;

@Data
public class OpsActionResultDto {
    private boolean success;
    private String message;
    private int status;
}
