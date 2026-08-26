package com.shcj.cache.web.controller.api.dto;

import lombok.Data;

@Data
public class ParamCountDto {
    private String name;
    private double count;
    private String extra;

    public ParamCountDto() {
    }

    public ParamCountDto(String name, double count, String extra) {
        this.name = name;
        this.count = count;
        this.extra = extra;
    }
}
