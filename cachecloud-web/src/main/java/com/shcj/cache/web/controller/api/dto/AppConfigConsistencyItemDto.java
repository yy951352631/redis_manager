package com.shcj.cache.web.controller.api.dto;

import lombok.Data;

import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.Map;

@Data
public class AppConfigConsistencyItemDto implements Serializable {
    private static final long serialVersionUID = 1L;

    private String group;
    private String configName;
    private boolean consistent;
    private boolean sensitive;
    private Map<String, String> values = new LinkedHashMap<>();
}
