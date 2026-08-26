package com.shcj.cache.web.controller.api.dto;

import lombok.Data;

import java.io.Serializable;

@Data
public class RedisVersionOptionDto implements Serializable {

    private static final long serialVersionUID = 1L;

    private int id;
    private String name;
}
