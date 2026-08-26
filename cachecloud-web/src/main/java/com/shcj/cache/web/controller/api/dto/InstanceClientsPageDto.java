package com.shcj.cache.web.controller.api.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class InstanceClientsPageDto {
    private List<InstanceClientItemDto> items = new ArrayList<>();
    private List<String> rawConnections = new ArrayList<>();
    private int totalConnections;
}
