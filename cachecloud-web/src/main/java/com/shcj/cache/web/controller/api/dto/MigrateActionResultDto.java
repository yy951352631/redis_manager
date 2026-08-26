package com.shcj.cache.web.controller.api.dto;

import lombok.Data;

@Data
public class MigrateActionResultDto {
    private int status;
    private String message;
    private String migrateId;
}
