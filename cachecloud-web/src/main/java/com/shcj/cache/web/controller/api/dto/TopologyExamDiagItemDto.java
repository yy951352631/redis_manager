package com.shcj.cache.web.controller.api.dto;

import lombok.Data;

@Data
public class TopologyExamDiagItemDto {
    private int no;
    private String title;
    private boolean ok;
    private String statusText;
    private String conclusion;
}
