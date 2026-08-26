package com.shcj.cache.web.controller.api.dto;

import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Data
public class DiagnosticResultDto implements Serializable {
    private static final long serialVersionUID = 1L;

    private int count;
    private List<String> listResult = new ArrayList<>();
    private Map<String, String> mapResult;
}
