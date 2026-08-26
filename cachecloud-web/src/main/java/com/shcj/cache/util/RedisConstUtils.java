package com.shcj.cache.util;

/**
 * Created by chenshi
 */
public class RedisConstUtils {

    /**
     * Redis版本安装脚本路径
     */
    public static final String REDIS_SHELL_DIR = ConstUtils.REDIS_BASE_DIR + "/sh/";

    /**
     * Redis安装包后缀
     */
    public static final String REDIS_INSTALL_PACKAGE_SUFFIX = ".tar.gz";

    /**
     * Redis make包后缀
     */
    public static final String REDIS_INSTALL_MAKE_PACKAGE_SUFFIX = "-make.tar.gz";

    /**
     * Redis版本名称前缀
     */
    public static final String REDIS_VERSION_PREFIX = "redis-";

    /**
     * Redis单版本安装日志
     */
    public static final String REDIS_INSTALL_LOG = REDIS_SHELL_DIR + "install.log.%s";

    /**
     * 版本名以 {@code redis-6.2.13} 形式存储（该前缀同时用于拼接安装目录，不能改存储格式），
     * 面向用户展示时去掉前缀。
     *
     * @param versionName 存储中的版本名
     * @return 去掉 redis- 前缀后的版本号，入参为空时返回空串
     */
    public static String displayVersion(String versionName) {
        if (versionName == null) {
            return "";
        }
        String trimmed = versionName.trim();
        if (trimmed.regionMatches(true, 0, REDIS_VERSION_PREFIX, 0, REDIS_VERSION_PREFIX.length())) {
            return trimmed.substring(REDIS_VERSION_PREFIX.length());
        }
        return trimmed;
    }

}
