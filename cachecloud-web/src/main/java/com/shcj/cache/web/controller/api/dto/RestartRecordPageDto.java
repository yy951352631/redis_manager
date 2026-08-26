package com.shcj.cache.web.controller.api.dto;

import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

@Data
public class RestartRecordPageDto implements Serializable {

    private static final long serialVersionUID = 1L;

    private List<RestartRecordDto> items = new ArrayList<>();
    private int pageNo = 1;
    private int pageSize = 10;
    private int totalCount;
    private int totalPages;
}
