package com.shcj.cache.web.controller.api.dto;

import lombok.Data;

import java.io.Serializable;

@Data
public class UserListItemDto implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;
    private String name;
    private String chName;
    private String email;
    private String mobile;
    private String company;
    private int isAlert;
    private String isAlertLabel;
    private int type;
    private String typeLabel;
    private String registerTime;
}
