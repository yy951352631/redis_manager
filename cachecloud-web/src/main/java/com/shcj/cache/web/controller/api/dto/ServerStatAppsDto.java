package com.shcj.cache.web.controller.api.dto;

import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

@Data
public class ServerStatAppsDto implements Serializable {

    private static final long serialVersionUID = 1L;

    private String searchDate;
    private List<ServerStatAppRowDto> items = new ArrayList<>();
}
