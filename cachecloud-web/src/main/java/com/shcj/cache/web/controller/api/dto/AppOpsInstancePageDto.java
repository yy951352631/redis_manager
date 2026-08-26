package com.shcj.cache.web.controller.api.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Data
public class AppOpsInstancePageDto {
    private long appId;
    private int clusterNo;
    private int appType;
    private String appName;
    private boolean sentinelApp;
    private boolean hostOpsEnabled;
    /** 节点 IP 是否均已关联可 SSH 的 machine_info */
    private boolean sshCapable;
    /** 是否全部为外部纳管节点 */
    private boolean externalManaged;
    /** 集群关联的管控机器数量 */
    private int managedMachineCount;
    private Set<String> k8sIps = new HashSet<>();
    private List<AppTopologyInstanceDto> instances = new ArrayList<>();
    private Map<String, String> lossSlotsSegmentMap;
}
