package com.shcj.cache.web.controller.api.dto;

import lombok.Data;

import java.io.Serializable;

@Data
public class AppDetailUserUpdateRequestDto implements Serializable {
    private static final long serialVersionUID = 1L;
    private String name;
    private String chName;
    private String email;
    private String mobile;
    private String company;
    private int isAlert;
    private int type;
}
