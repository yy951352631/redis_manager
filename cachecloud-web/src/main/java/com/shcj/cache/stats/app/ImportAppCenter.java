package com.shcj.cache.stats.app;

import com.shcj.cache.constant.ImportAppResult;
import com.shcj.cache.entity.AppDesc;

/**
 * 导入应用
 *
 * @author leifu
 * @Date 2016-4-16
 * @Time 下午3:42:49
 */
public interface ImportAppCenter {

    /**
     * 检查应用和实例
     *
     * @param appInstanceInfo
     * @return
     */
    ImportAppResult check(int type, String appInstanceInfo, String password);

    /**
     * 导入应用和相关实例
     *
     * @param appDesc
     * @param appInstanceInfo
     * @return
     */
    boolean importAppAndInstance(AppDesc appDesc, String appInstanceInfo);
}
