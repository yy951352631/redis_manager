package com.shcj.cache.web.controller.api.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 集群详情 Tab 元数据（M3）
 */
@Data
public class AppDetailTabDto implements Serializable {

    private static final long serialVersionUID = 1L;

    private String key;
    private String label;
    /** 过渡期：旧版 Tab 页面相对路径（含 query） */
    private String legacyPath;
}
