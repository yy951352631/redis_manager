package com.shcj.cache.datamodel;

import lombok.Data;

import java.util.List;

/**
 * 「数据模型」页的全局筛选条件。
 *
 * <p>四个主题共用同一组条件，是为了保证跨主题的一致性——这一页要回答的是
 * 「同一批实例在不同维度下分别表现如何」，如果每个主题各带一套筛选，
 * 两张图对不上时就无从判断差异来自数据还是来自条件不一致。
 */
@Data
public class DataModelQuery {

    /** 时间窗口（小时），默认 7 天 */
    private int windowHours = 168;
    /** 集群，空表示全部 */
    private List<Long> appIds;
    /** 架构，空表示全部 */
    private List<String> archs;
    /** Redis 大版本，空表示全部 */
    private List<String> majorVersions;
    /** 实例角色：1 master、2 slave，null 表示全部 */
    private Integer role;
    /** 是否排除测试集群，默认排除 */
    private boolean excludeTestApp = true;
    /** 命令耗时主题专用：指定命令，空表示按调用量取 Top N */
    private List<String> commands;
}
