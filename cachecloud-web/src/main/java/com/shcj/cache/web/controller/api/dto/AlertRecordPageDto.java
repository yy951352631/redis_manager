package com.shcj.cache.web.controller.api.dto;

import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 报警记录分页结果
 */
@Data
public class AlertRecordPageDto implements Serializable {

    private static final long serialVersionUID = 1L;

    private List<AlertRecordItemDto> items = new ArrayList<>();
    private int pageNo = 1;
    private int pageSize = 20;
    private int totalCount;
    private int totalPages;
    /** 重要程度可选项，供前端筛选下拉使用 */
    private List<EnumOptionDto> importantLevels = new ArrayList<>();
    /** 出现过报警的集群，供前端按集群名称筛选 */
    private List<AlertRecordAppOptionDto> apps = new ArrayList<>();
}
