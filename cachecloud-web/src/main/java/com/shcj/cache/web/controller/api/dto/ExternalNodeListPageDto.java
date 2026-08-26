package com.shcj.cache.web.controller.api.dto;

import com.shcj.cache.web.vo.ExternalNodeVO;
import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 节点管理列表分页
 */
@Data
public class ExternalNodeListPageDto implements Serializable {

    private static final long serialVersionUID = 1L;

    private List<ExternalNodeVO> items = new ArrayList<>();
    private int pageNo = 1;
    private int pageSize = 20;
    private int totalCount;
    private int totalPages;
}
