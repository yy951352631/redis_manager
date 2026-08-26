package com.shcj.cache.web.controller.api.dto;

import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

@Data
public class CommandCheckDetailDto implements Serializable {
    private static final long serialVersionUID = 1L;

    private String key;
    private List<CommandCheckDetailRowDto> rows = new ArrayList<>();
}
