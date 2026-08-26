package com.shcj.cache.web.controller.api.dto;

import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

@Data
public class AppConfigConsistencyDto implements Serializable {
    private static final long serialVersionUID = 1L;

    private long appId;
    private String checkedAt;
    private boolean consistent;
    private int totalInstances;
    private int checkedInstances;
    private int failedInstances;
    private int comparedConfigCount;
    private int inconsistentConfigCount;
    private int ignoredConfigCount;
    private List<String> ignoredConfigs = new ArrayList<>();
    private List<AppConfigConsistencyNodeDto> nodes = new ArrayList<>();
    private List<AppConfigConsistencyItemDto> items = new ArrayList<>();
}
