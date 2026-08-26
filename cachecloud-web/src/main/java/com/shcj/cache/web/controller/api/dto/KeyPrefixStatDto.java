package com.shcj.cache.web.controller.api.dto;

import lombok.Data;

@Data
public class KeyPrefixStatDto {
    private String type;
    private String prefix;
    private long count;
    private long bytes;
    private String bytesLabel;
}
