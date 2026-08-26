package com.shcj.cache.web.controller.api.dto;

import lombok.Data;

import java.util.List;

@Data
public class CurrentUserDto {

    private Long id;
    private String username;
    private String chName;
    private String email;
    private String mobile;
    private String weChat;
    private String company;
    private String purpose;
    private Integer type;
    private Integer isAlert;
    private List<String> roles;
}
