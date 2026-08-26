package com.shcj.cache.web.controller.api.dto;

import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

@Data
public class AppClientInstanceStatDto implements Serializable {
    private static final long serialVersionUID = 1L;

    private int instanceId;
    private String hostPort;
    private long count;
    private List<InstanceClientConnectionDto> connectionDetails = new ArrayList<>();
}
