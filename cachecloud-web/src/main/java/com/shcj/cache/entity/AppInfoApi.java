package com.shcj.cache.entity;

import lombok.Data;

import java.util.List;

/**
 * 构建应用对象
 *
 * @author chenshi
 */
@Data
public class AppInfoApi {

    /**
     * 应用名称
     */
    private String name;

    /**
     * 应用介绍
     */
    private String desc;

    /**
     * 申请人信息
     */
    private AppUser user;

    /**
     * 机器列表信息
     */
    private List<String> iplist;

    /**
     * 应用内存总量
     */
    private int memTotalSize;

    /**
     * 是否有从节点 0:无 1:有
     */
    private int hasSlave;

    /**
     * 应用申请类型: 2:cluster 5:sentinel 6:standalone
     */
    private int type;

    /**
     * 是否测试: 0:测试 1:正式
     */
    private int isTest;

    /**
     * redis version版本
     */
    private String redisVersion;

    /**
     * 机房名称
     */
    private String room;
    private int instanceNum;
    private int sentinelNum;

}
