package com.shcj.cache.web.controller.api.dto;

import lombok.Data;

@Data
public class MigrateAppOptionDto {
    private long value;
    private String label;
    private int appType;
}
