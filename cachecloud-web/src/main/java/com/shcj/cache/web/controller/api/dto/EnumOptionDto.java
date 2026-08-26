package com.shcj.cache.web.controller.api.dto;

import lombok.Data;

import java.io.Serializable;

@Data
public class EnumOptionDto implements Serializable {
    private static final long serialVersionUID = 1L;

    private int value;
    private String label;

    public EnumOptionDto() {
    }

    public EnumOptionDto(int value, String label) {
        this.value = value;
        this.label = label;
    }
}
