package com.shcj.cache.web.controller.api.dto;

import lombok.Data;

@Data
public class InstanceFaultItemDto {
    private int id;
    private int appId;
    private int instId;
    private String ip;
    private int port;
    private int status;
    private String statusDesc;
    private int type;
    private String typeDesc;
    private String createTime;
    private String reason;
}
