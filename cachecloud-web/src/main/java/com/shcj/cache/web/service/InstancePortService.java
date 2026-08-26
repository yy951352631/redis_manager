package com.shcj.cache.web.service;

import com.shcj.cache.task.constant.InstanceRoleEnum;
import com.shcj.cache.task.entity.NutCrackerNode;
import com.shcj.cache.task.entity.RedisSentinelNode;
import com.shcj.cache.task.entity.RedisServerNode;
import com.shcj.cache.web.enums.AppTypeEnum;

import java.util.List;

/**
 * @author fulei
 * @date 2018年7月4日
 * @time 下午3:20:57
 */
public interface InstancePortService {

    /**
     * 检查机器端口是否被占用
     *
     * @param ip
     * @param port
     * @return
     */
    public boolean checkHostPortExist(String ip, int port);

    /**
     * 从当前机器生成一个可用实例
     *
     * @param appId
     * @param host
     * @param maxMemory
     * @return
     */
    public RedisServerNode generateRedisServerNode(long appId, String host, int maxMemory, InstanceRoleEnum instanceRoleEnum);

    /**
     * 从当前机器生成一个可用实例
     * @param appId
     * @param host
     * @param maxMemory
     * @return
     */
//	public PikaNode generatePikaNode(long appId, String host, int maxMemory, InstanceRoleEnum instanceRoleEnum);

    /**
     * 生成redis server 节点列表
     *
     * @param appId
     * @param redisServerMachineList
     * @param masterPerMachine
     * @param maxMemory
     * @return
     */
    public List<RedisServerNode> generateRedisServerNodeList(long appId, List<String> redisServerMachineList,
                                                             List<String> redisSlaveMachineList, int maxMemory, AppTypeEnum appTypeEnum);

    /**
     * 生成redis sentinel 节点列表
     *
     * @param appId
     * @param redisSentinelMachineList
     * @param sentinelPerMachine
     * @return
     */
    public List<RedisSentinelNode> generateRedisSentinelNodeList(long appId, List<String> redisSentinelMachineList);

    /**
     * 生成nut cracker 节点列表
     *
     * @param appId
     * @param nutCrackerMachineList
     * @param nutCrackerPerMachine
     * @return
     */
    public List<NutCrackerNode> generateNutCrackerNodeList(long appId, List<String> nutCrackerMachineList, int nutCrackerPerMachine);

    /**
     * 获取redis-port 节点列表
     * @param sourceAppId
     * @param targetAppId
     * @param slaveInstanceInfoList
     * @param proxyInstanceInfoList
     * @return
     */
//	public List<RedisPortNode> generateRedisPortNodeList(long sourceAppId, long targetAppId,List<InstanceInfo> slaveInstanceInfoList, List<InstanceInfo> proxyInstanceInfoList);

    /**
     * 获取redis-migrate-tool节点列表
     * @return
     */
//	public List<RedisMigrateToolNode> generateRedisMigrateToolNodeList(int rmtCount, String logicName);

    /**
     * 获取proxy节点列表
     * @param appId
     * @param codisProxyMachineList
     * @param codisProxyPerMachine
     * @return
     */
//	public List<CodisProxyNode> generateCodisProxyList(long appId, List<String> codisProxyMachineList,int codisProxyPerMachine);

    /**
     * 获取dashboard节点列表
     * @param appId
     * @param codisDashboardMachineList
     * @return
     */
//	public List<CodisDashboardNode> generateCodisDashboardList(long appId, List<String> codisDashboardMachineList);

    /**
     * @param appId
     * @param pikaMachineList
     * @param masterPerMachine
     * @return
     */
//	public List<PikaNode> generatePikaNodeList(long appId, List<String> pikaMachineList, int masterPerMachine);

}