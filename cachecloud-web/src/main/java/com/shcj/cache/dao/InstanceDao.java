package com.shcj.cache.dao;

import com.shcj.cache.entity.InstanceInfo;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * 基于instance的dao操作
 * <p/>
 * User: lingguo
 * Date: 14-6-3
 * Time: 下午3:58
 */
public interface InstanceDao {
    /**
     * 通过type查询实例列表
     *
     * @param type
     * @return
     */
    public List<InstanceInfo> getInstListByType(@Param("type") int type);

    /**
     * 查询appId下的所有instance
     *
     * @param appId
     * @return
     */
    public List<InstanceInfo> getInstListByAppId(@Param("appId") long appId);

    int deleteByAppId(@Param("appId") long appId);

    /**
     * 查询appId下的有效的instance
     *
     * @param appId
     * @return
     */
    public List<InstanceInfo> getEffectiveInstListByAppId(@Param("appId") long appId);

    /**
     * 通过host和port查询一个实例信息
     *
     * @param ip
     * @param port
     * @return
     */
    public InstanceInfo getInstByIpAndPort(@Param("ip") String ip, @Param("port") int port);

    /**
     * 通过host和port查询一个实例信息
     *
     * @param ip
     * @param port
     * @return
     */
    public InstanceInfo getAllInstByIpAndPort(@Param("ip") String ip, @Param("port") int port);

    /**
     * 通过所有实例列表(包括:0:节点异常,1:正常启用)
     */
    public List<InstanceInfo> getAllInsts();

    /**
     * 检测当前机器心跳停止的实例
     */
    public List<InstanceInfo> checkHeartStopInstance(@Param("ip") String ip);

    /**
     * 获取机器所有心跳停止的实例
     */
    public List<InstanceInfo> getAllHeartStopInstance();

    /**
     * 通过host和port查询一个实例信息
     *
     * @param ip
     * @param port
     * @return
     */
    public int getCountByIpAndPort(@Param("ip") String ip, @Param("port") int port);

    /**
     * 获取IP上的最大Redis端口
     *
     * @param ip
     * @return
     */
    public Integer getMaxRedisPortByIp(@Param("ip") String ip);

    /**
     * 获取IP上的最大Sentinel端口
     *
     * @param ip
     * @return
     */
    public Integer getMaxSentinelPortByIp(@Param("ip") String ip);

    /**
     * 保存一个实例
     *
     * @param instanceInfo
     */
    public void saveInstance(InstanceInfo instanceInfo);

    /**
     * 根据ip和type查询实例数量
     *
     * @param ip
     * @param type
     * @return
     */
    public int getInstanceTypeCount(@Param("ip") String ip, @Param("type") int type);

    public List<InstanceInfo> getInstancesByType(@Param("app_id") long app_id, @Param("type") int type);

    public InstanceInfo getInstanceInfoById(@Param("id") long id);

    public int getMemoryByHost(String host);

    public int getInstanceCountByHost(@Param("host") String host);

    public int update(InstanceInfo instanceInfo);

    void reuseInstance(@Param("id") int id, @Param("appId") long appId, @Param("hostId") long hostId,
                       @Param("ip") String ip, @Param("port") int port, @Param("status") int status,
                       @Param("mem") int mem, @Param("conn") int conn, @Param("cmd") String cmd,
                       @Param("type") int type);

    int updateParentId(@Param("instanceId") int instanceId, @Param("parentId") int parentId);

    /**
     * 获取一台机器的所有实例
     *
     * @param ip
     * @return
     */
    public List<InstanceInfo> getInstListByIp(@Param("ip") String ip);

    /**
     * IP 模糊匹配（LIKE %pattern%，如 144.53 可匹配 139.196.144.53）
     */
    public List<InstanceInfo> getInstListByIpLike(@Param("ipPattern") String ipPattern);

    /**
     * IP 模糊 + 端口精确匹配
     */
    public List<InstanceInfo> getInstListByIpLikeAndPort(@Param("ipPattern") String ipPattern, @Param("port") int port);

    /**
     * 机器实例数map
     *
     * @return
     */
    public List<Map<String, Object>> getMachineInstanceCountMap();

    /**
     * <p>
     * Description:获取有效的实例id
     * </p>
     *
     * @author chenshi
     * @version 1.0
     * @date 2017/8/14
     */
    public List<Map<String, Object>> getTotalEffectiveInst();

    List<Long> getAppIdListByIp(List<String> ipList);

    /**
     * 更新实例状态
     *
     * @param appId
     * @param ip
     * @param port
     * @param status
     */
    public void updateStatus(@Param("appId") long appId, @Param("ip") String ip, @Param("port") int port, @Param("status") int status);
}
