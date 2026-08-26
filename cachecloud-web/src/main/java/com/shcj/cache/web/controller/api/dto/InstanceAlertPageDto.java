package com.shcj.cache.web.controller.api.dto;

import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

@Data
public class InstanceAlertPageDto implements Serializable {
    private static final long serialVersionUID = 1L;

    private List<EnumOptionDto> checkCycles = new ArrayList<>();
    private List<EnumOptionDto> compareTypes = new ArrayList<>();
    private List<AlertConfigOptionDto> alertConfigs = new ArrayList<>();
    private List<AlertConfigOptionDto> usedGlobalConfigs = new ArrayList<>();
    private List<InstanceAlertItemDto> globalAlerts = new ArrayList<>();
    private List<InstanceAlertItemDto> specialAlerts = new ArrayList<>();
}
