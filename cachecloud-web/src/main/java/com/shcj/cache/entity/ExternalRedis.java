package com.shcj.cache.entity;

import com.shcj.cache.util.ConstUtils;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;
import java.util.List;

/**
 * 外部纳管 Redis 登记
 */
@Data
public class ExternalRedis implements Serializable {

    private static final long serialVersionUID = 1L;

    public static final long EXTERNAL_HOST_ID = 0L;

    private Long id;
    private Long appId;
    private String name;
    private String intro;
    private int type;
    private String password;
    private String sentinelPassword;
    private String instanceInfo;
    private long userId;
    private int status;
    private Date createTime;
    private Date updateTime;

    private String typeDesc;
    private List<InstanceInfo> instanceList;

    public String getTypeDesc() {
        if (type == ConstUtils.CACHE_TYPE_REDIS_CLUSTER) {
            return "redis-cluster";
        } else if (type == ConstUtils.CACHE_REDIS_SENTINEL) {
            return "redis-sentinel";
        } else if (type == ConstUtils.CACHE_REDIS_STANDALONE) {
            return "redis-standalone";
        }
        return "";
    }
}
