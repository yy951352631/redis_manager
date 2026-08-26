package com.shcj.cache.web.controller.api.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 数据模型页筛选项的可选值。由平台现有数据动态给出——
 * 写死选项会出现「选了却查不到任何数据」的情况。
 */
@Data
public class DataModelOptionsDto {

    private List<AppOption> apps = new ArrayList<AppOption>();
    private List<String> archs = new ArrayList<String>();
    private List<String> majorVersions = new ArrayList<String>();
    /** 按调用量排序的命令，供命令耗时主题选择 */
    private List<String> commands = new ArrayList<String>();

    @Data
    public static class AppOption {
        private long appId;
        private String appName;
        private boolean testApp;
    }
}
