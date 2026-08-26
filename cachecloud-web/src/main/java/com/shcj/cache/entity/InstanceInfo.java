package com.shcj.cache.entity;

import com.shcj.cache.constant.InstanceStatusEnum;
import com.shcj.cache.task.constant.InstanceInfoEnum;
import com.shcj.cache.util.ConstUtils;
import com.shcj.cache.web.enums.BooleanEnum;
import lombok.Data;
import redis.clients.jedis.Module;

import java.io.Serializable;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

/**
 * 实例信息
 * User: lingguo
 */
@Data
public class InstanceInfo implements Serializable {
    private static final long serialVersionUID = -903896025243493024L;
    /**
     * 实例id
     */
    private Integer id;
    /**
     * 应用id
     */
    private long appId;
    /**
     * host id
     */
    private long hostId;
    /**
     * ip
     */
    private String ip;
    /**
     * 端口
     */
    private int port;
    /**
     * 是否启用 0:节点异常,1:正常启用,2:节点下线
     */
    private int status;
    /**
     * 开启的内存
     */
    private int mem;
    /**
     * 连接数
     */
    private int conn;
    /**
     * 启动命令 或者 redis-sentinel的masterName
     */
    private String cmd;
    private int type;
    private String typeDesc;
    private int masterInstanceId;
    private String masterHost;
    private int masterPort;
    private String roleDesc;
    private int groupId;
    private Date updateTime;

    private List<Module> modules;

    public String getTypeDesc() {
        if (type <= 0) {
            typeDesc = "";
        } else if (type == ConstUtils.CACHE_TYPE_REDIS_CLUSTER) {
            typeDesc = "redis-cluster";
        } else if (type == ConstUtils.CACHE_REDIS_SENTINEL) {
            typeDesc = "redis-sentinel";
        } else if (type == ConstUtils.CACHE_REDIS_STANDALONE) {
            typeDesc = "redis-standalone";
        }
        return typeDesc;
    }

    public String getStatusDesc() {
        InstanceStatusEnum instanceStatusEnum = InstanceStatusEnum.getByStatus(status);
        if (instanceStatusEnum != null) {
            return instanceStatusEnum.getInfo();
        }
        return "";
    }

    /**
     * 判断当前节点是否下线
     *
     * @return
     */
    /**
     * 是否应持续采集监控数据。
     *
     * <p>存活探测以「上一分钟是否采集到 INFO」为准，因此心跳停止的节点也必须继续尝试采集，
     * 否则一旦被判定为异常就再也拿不到 INFO，节点将永远无法恢复为运行中。
     * 只有平台侧主动下线（已下线/永久下线）的节点才停止采集。</p>
     */
    public boolean isCollectable() {
        return !isOffline() && status != InstanceStatusEnum.ALLOCATE_STATUS.getStatus();
    }

    public boolean isOffline() {
        return status == InstanceStatusEnum.OFFLINE_STATUS.getStatus() || status == InstanceStatusEnum.FORGET_STATUS.getStatus();
    }

    public boolean isError() {
        return status == InstanceStatusEnum.ERROR_STATUS.getStatus();
    }

    public boolean isStop() {
        return isError() || status == InstanceStatusEnum.OFFLINE_STATUS.getStatus();
    }

    /**
     * 判断当前节点是否在线
     */
    public boolean isOnline() {
        return status == InstanceStatusEnum.GOOD_STATUS.getStatus();
    }

    public String getRoleDesc() {
        if (type == ConstUtils.CACHE_REDIS_SENTINEL) {
            return "sentinel";
        } else {
            return roleDesc;
        }
    }

    public void setRoleDesc(BooleanEnum isMaster) {
        if (isMaster == BooleanEnum.OTHER) {
            roleDesc = "未知";
        } else if (isMaster == BooleanEnum.TRUE) {
            roleDesc = "master";
        } else if (isMaster == BooleanEnum.FALSE) {
            roleDesc = "slave";
        }
    }

    public String getHostPort() {
        return ip + ":" + port;
    }

    public boolean isMemcached() {
        return InstanceInfoEnum.InstanceTypeEnum.MEMCACHE.getType() == type;
    }

    public boolean isNutCracker() {
        return InstanceInfoEnum.InstanceTypeEnum.NUTCRACKER.getType() == type;
    }

    public boolean isPika() {
        return InstanceInfoEnum.InstanceTypeEnum.PIKA.getType() == type;
    }

    /**
     * 是否是redis数据节点
     *
     * @return
     */
    public boolean isRedisData() {
        if (type == InstanceInfoEnum.InstanceTypeEnum.REDIS_SERVER.getType()
                || type == InstanceInfoEnum.InstanceTypeEnum.REDIS_CLUSTER.getType()) {
            return true;
        }
        return false;
    }

    public Date getUpdateTime() {
        if (updateTime != null) {
            return (Date) updateTime.clone();
        }
        return null;
    }

    public String getUpdateTimeDesc() {
        if (updateTime != null) {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            return sdf.format((Date) updateTime.clone());
        } else {
            return "";
        }
    }

    public void setUpdateTime(Date updateTime) {
        this.updateTime = (Date) updateTime.clone();
    }
}