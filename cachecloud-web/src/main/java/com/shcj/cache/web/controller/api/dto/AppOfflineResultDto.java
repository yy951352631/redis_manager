package com.shcj.cache.web.controller.api.dto;

import lombok.Data;

import java.io.Serializable;

@Data
public class AppOfflineResultDto implements Serializable {

    private static final long serialVersionUID = 1L;

    private long appId;
    private long taskId;
    private String message;
}
