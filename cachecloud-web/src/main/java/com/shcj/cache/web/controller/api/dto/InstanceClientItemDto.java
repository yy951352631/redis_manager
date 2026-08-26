package com.shcj.cache.web.controller.api.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class InstanceClientItemDto {
    private String addr;
    private int count;
    private List<String> clientTypes = new ArrayList<>();
    private List<String> connections = new ArrayList<>();
    private List<InstanceClientConnectionDto> connectionDetails = new ArrayList<>();
    private int detailTotal;
    private boolean detailsTruncated;
}
