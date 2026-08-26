package com.shcj.cache.web.controller.api.dto;

import lombok.Data;

import java.io.Serializable;

@Data
public class AppCommandClimaxDto implements Serializable {
    private static final long serialVersionUID = 1L;

    private String commandName;
    private long commandCount;
    private String createTime;
}
