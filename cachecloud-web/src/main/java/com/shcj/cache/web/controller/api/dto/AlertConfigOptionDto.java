package com.shcj.cache.web.controller.api.dto;

import lombok.Data;

import java.io.Serializable;

@Data
public class AlertConfigOptionDto implements Serializable {
    private static final long serialVersionUID = 1L;

    private String value;
    private String info;
}
