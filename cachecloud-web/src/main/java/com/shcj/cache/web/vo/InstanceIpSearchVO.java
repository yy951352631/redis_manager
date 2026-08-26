package com.shcj.cache.web.vo;

import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 按节点 IP（或 ip:port）反查应用时的展示对象。
 */
@Data
public class InstanceIpSearchVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private long appId;
    private String appName;
    private String typeDesc;
    /** 是否在「实例管理」外部纳管表中登记 */
    private boolean externalManaged;
    /** 与搜索条件匹配的节点，如 139.1.1.1:7001 */
    private List<String> matchedNodes = new ArrayList<String>();
}
