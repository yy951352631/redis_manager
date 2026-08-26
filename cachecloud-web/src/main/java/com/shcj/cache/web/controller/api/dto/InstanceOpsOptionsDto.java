package com.shcj.cache.web.controller.api.dto;

import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

@Data
public class InstanceOpsOptionsDto implements Serializable {

    private static final long serialVersionUID = 1L;

    private List<RedisVersionOptionDto> redisVersions = new ArrayList<>();
    private List<CompareTypeOptionDto> compareTypes = new ArrayList<>();
}
