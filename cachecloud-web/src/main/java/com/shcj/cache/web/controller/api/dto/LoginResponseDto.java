package com.shcj.cache.web.controller.api.dto;

import lombok.Data;

import java.util.List;

@Data
public class LoginResponseDto {

    /** 会话标识（写入 localStorage；实际鉴权依赖 Cookie） */
    private String token;
    private String username;
    private List<String> roles;
}
