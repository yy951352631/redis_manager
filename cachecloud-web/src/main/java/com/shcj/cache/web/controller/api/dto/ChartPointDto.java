package com.shcj.cache.web.controller.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.io.Serializable;

@Data
public class ChartPointDto implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long x;
    private Long y;
    @JsonProperty("yDouble")
    private Double yDouble;
    private String date;
    private String commandName;
}
