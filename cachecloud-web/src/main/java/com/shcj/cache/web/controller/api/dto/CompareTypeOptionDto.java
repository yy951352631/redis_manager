package com.shcj.cache.web.controller.api.dto;

import lombok.Data;

import java.io.Serializable;

@Data
public class CompareTypeOptionDto implements Serializable {

    private static final long serialVersionUID = 1L;

    private int type;
    private String label;
}
