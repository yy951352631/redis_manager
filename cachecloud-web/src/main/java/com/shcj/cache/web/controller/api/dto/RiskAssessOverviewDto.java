package com.shcj.cache.web.controller.api.dto;

import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 风险评估总览清单
 */
@Data
public class RiskAssessOverviewDto implements Serializable {

    private static final long serialVersionUID = 1L;

    private List<RiskAssessOverviewItemDto> items = new ArrayList<>();
    private int pageNo = 1;
    private int pageSize = 20;
    private int totalCount;
    private int totalPages;

    /** 各等级集群数，含 UNASSESSED */
    private Map<String, Integer> levelCount = new LinkedHashMap<>();
}
