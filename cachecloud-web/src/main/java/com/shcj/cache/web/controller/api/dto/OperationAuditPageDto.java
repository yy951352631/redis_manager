package com.shcj.cache.web.controller.api.dto;

import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 审计日志分页结果
 */
@Data
public class OperationAuditPageDto implements Serializable {

    private static final long serialVersionUID = 1L;

    private List<OperationAuditItemDto> items = new ArrayList<>();
    private int pageNo = 1;
    private int pageSize = 20;
    private int totalCount;
    private int totalPages;
    /** 可选的模块过滤项 */
    private List<String> modules = new ArrayList<>();
}
