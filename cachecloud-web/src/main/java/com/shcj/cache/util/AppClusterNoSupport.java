package com.shcj.cache.util;

import com.shcj.cache.entity.AppDesc;

/**
 * 集群对外编号（cluster_no）常量与展示辅助。
 */
public final class AppClusterNoSupport {

    public static final int MIN_CLUSTER_NO = 100001;

    private AppClusterNoSupport() {
    }

    public static boolean isClusterNo(long value) {
        return value >= MIN_CLUSTER_NO;
    }

    public static int displayClusterNo(AppDesc appDesc) {
        if (appDesc == null || appDesc.getClusterNo() == null || appDesc.getClusterNo() <= 0) {
            return 0;
        }
        return appDesc.getClusterNo();
    }
}
