package com.shcj.cache.web.controller.api.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChartItemDto {
    private String name;
    private double value;
}
