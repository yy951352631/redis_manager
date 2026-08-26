package com.shcj.cache.web.controller.api.dto;

import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

@Data
public class AppTopologyDto implements Serializable {
    private static final long serialVersionUID = 1L;

    private List<AppTopologyInstanceDto> instances = new ArrayList<>();
    private List<String> connectErrors = new ArrayList<>();
    private boolean hasExternalInstance;
}
