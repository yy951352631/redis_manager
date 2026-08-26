package com.shcj.cache.web.controller.api.dto;

import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

@Data
public class TaskListPageDto implements Serializable {
    private static final long serialVersionUID = 1L;

    private List<TaskListItemDto> items = new ArrayList<>();
    private int pageNo;
    private int pageSize;
    private int totalCount;
    private int totalPages;
}
