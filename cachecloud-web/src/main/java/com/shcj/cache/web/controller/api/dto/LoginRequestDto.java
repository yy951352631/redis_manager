package com.shcj.cache.web.controller.api.dto;

import lombok.Data;

@Data
public class LoginRequestDto {

    private String username;
    private String password;
    /** 是否以管理端身份登录，默认 true */
    private Boolean isAdmin;
}
