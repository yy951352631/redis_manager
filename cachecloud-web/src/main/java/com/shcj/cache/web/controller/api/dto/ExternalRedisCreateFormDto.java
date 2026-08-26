package com.shcj.cache.web.controller.api.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class ExternalRedisCreateFormDto {
    private int defaultVersionId;
    private long currentUserId;
    private List<SelectOptionDto> users = new ArrayList<>();
    private List<SelectOptionDto> versions = new ArrayList<>();
}
