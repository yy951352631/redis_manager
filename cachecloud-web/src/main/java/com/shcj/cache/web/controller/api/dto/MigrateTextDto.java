package com.shcj.cache.web.controller.api.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class MigrateTextDto {
    private List<String> lines = new ArrayList<>();
}
