package com.shcj.cache.web.controller.api.dto;

import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

@Data
public class AppClientItemDto implements Serializable {
    private static final long serialVersionUID = 1L;

    private String addr;
    private long size;
    private List<String> flags = new ArrayList<>();
    private List<AppClientInstanceStatDto> instanceStats = new ArrayList<>();
}
