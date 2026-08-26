package com.shcj.cache.protocol;

import com.shcj.cache.redis.enums.DirEnum;
import com.shcj.cache.util.ConstUtils;

/**
 * 机器相关的一些常量
 *
 * @author: lingguo
 * @time: 2014/8/26 16:18
 */
public class MachineProtocol {

    /**
     * 统一的目录结构
     */
    public static final String CONF_DIR = ConstUtils.REDIS_BASE_DIR + "/conf/";
    public static final String DATA_DIR = ConstUtils.REDIS_BASE_DIR + "/data/";
    public static final String LOG_DIR = ConstUtils.REDIS_BASE_DIR + "/logs/";


    /**
     * k8s容器目录结构
     */
    public static final String K8S_CONF_DIR = ConstUtils.REDIS_BASE_DIR + "/conf/%s/";
    public static final String K8S_DATA_DIR = ConstUtils.REDIS_BASE_DIR + "/data/%s/";
    public static final String K8S_LOG_DIR = ConstUtils.REDIS_BASE_DIR + "/logs/%s/";

    /**
     * 配置文件的临时目录；
     */
    public static final String TMP_DIR = ConstUtils.REDIS_BASE_DIR + "/tmp/conf/";

    /**
     * 编码
     */
    public static final String ENCODING_UTF8 = "UTF-8";

    /**
     * 配置目录
     *
     * @param instanceBasePath
     * @return
     */
    public static String getConfPath(String instanceBasePath) {
        return instanceBasePath + "/conf";
    }

    /**
     * 支持k8s挂载宿主机文件
     *
     * @param host
     * @return
     */
    public static String getK8sConfDir(String host) {
        return String.format(K8S_CONF_DIR, host);
    }

    public static String getK8sDataDir(String host) {
        return String.format(K8S_DATA_DIR, host);
    }

    public static String getK8sLogDir(String host) {
        return String.format(K8S_LOG_DIR, host);
    }

    /**
     * 获取k8s相关目录
     *
     * @param host
     * @param dirType
     * @return
     */
    public static String getK8sDir(String host, int dirType) {
        if (dirType == DirEnum.CONF_DIR.getValue()) {
            return String.format(K8S_CONF_DIR, host);
        } else if (dirType == DirEnum.DATA_DIR.getValue()) {
            return String.format(K8S_DATA_DIR, host);
        } else if (dirType == DirEnum.LOG_DIR.getValue()) {
            return String.format(K8S_LOG_DIR, host);
        }
        return null;
    }

    /**
     * 获取普通容器目录
     *
     * @param dirType
     * @return
     */
    public static String getDir(int dirType) {
        if (dirType == DirEnum.CONF_DIR.getValue()) {
            return CONF_DIR;
        } else if (dirType == DirEnum.DATA_DIR.getValue()) {
            return DATA_DIR;
        } else if (dirType == DirEnum.LOG_DIR.getValue()) {
            return LOG_DIR;
        }
        return null;
    }

}
