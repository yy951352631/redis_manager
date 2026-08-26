package com.shcj.cache.web.controller.api.dto;

import lombok.Data;

@Data
public class KeyAnalysisBigKeyDto {
    private String instance;
    private String keyName;
    private String type;
    private String sizeLabel;
    private long length;
}
