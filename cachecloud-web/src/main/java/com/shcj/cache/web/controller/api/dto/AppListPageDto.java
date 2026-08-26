package com.shcj.cache.web.controller.api.dto;

import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 集群管理列表分页（M3）
 */
@Data
public class AppListPageDto implements Serializable {

    private static final long serialVersionUID = 1L;

    private List<AppListItemDto> items = new ArrayList<>();
    private int pageNo = 1;
    private int pageSize = 20;
    private int totalCount;
    private int totalPages;
    /** 是否启用主机运维能力（版本变更等） */
    private boolean hostOpsEnabled;
}
