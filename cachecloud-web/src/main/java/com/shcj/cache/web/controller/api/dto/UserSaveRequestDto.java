package com.shcj.cache.web.controller.api.dto;

import lombok.Data;

import java.io.Serializable;

@Data
public class UserSaveRequestDto implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;
    private String name;
    private String chName;
    private String email;
    private String mobile;
    private String weChat;
    private Integer type;
    private Integer isAlert;
    private String company;
    private String purpose;
    private String password;
}
