package com.shcj.cache.web.controller.api.dto;

import lombok.Data;

@Data
public class SelectOptionDto {
    private long id;
    private String name;
    private String label;

    public SelectOptionDto() {
    }

    public SelectOptionDto(long id, String name) {
        this.id = id;
        this.name = name;
        this.label = name;
    }
}
