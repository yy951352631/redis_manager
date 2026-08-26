package com.shcj.cache.web.controller.api.dto;

import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

@Data
public class AppScrollRestartRequestDto implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long recordId;
    private boolean configFlag;
    private boolean transferFlag;
    private List<Integer> instanceIds = new ArrayList<>();
    private List<ScrollRestartConfigItemDto> configList = new ArrayList<>();
}
