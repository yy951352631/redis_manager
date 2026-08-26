package com.shcj.cache.web.controller.api.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class OfflineAnalysisPageDto {

    private List<OfflineAnalysisRecordDto> items = new ArrayList<OfflineAnalysisRecordDto>();
    private int pageNo;
    private int pageSize;
    private int totalCount;
}
