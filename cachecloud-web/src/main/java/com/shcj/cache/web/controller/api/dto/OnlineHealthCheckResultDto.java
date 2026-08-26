package com.shcj.cache.web.controller.api.dto;

import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

@Data
public class OnlineHealthCheckResultDto implements Serializable {
    private static final long serialVersionUID = 1L;

    /** 纯文本结果，供 PDF / 兼容旧接口 */
    private String result = "";

    private List<OnlineHealthTargetDto> targets = new ArrayList<>();

    @Data
    public static class OnlineHealthTargetDto implements Serializable {
        private static final long serialVersionUID = 1L;
        private String host;
        /** standalone / cluster / sentinel */
        private String mode;
        private String modeLabel;
        private boolean allOk;
        private String connectError;
        /** 是否命中平台纳管实例并使用了应用密码 */
        private boolean managed;
        private Long appId;
        private String appName;
        private List<OnlineHealthCheckItemDto> checks = new ArrayList<>();
    }

    @Data
    public static class OnlineHealthCheckItemDto implements Serializable {
        private static final long serialVersionUID = 1L;
        private String name;
        /** Info / Error */
        private String level;
        private String expect;
        private String current;
        private String description;
    }
}
