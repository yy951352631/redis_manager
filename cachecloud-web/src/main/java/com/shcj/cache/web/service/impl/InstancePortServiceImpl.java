package com.shcj.cache.web.service.impl;

import com.shcj.cache.dao.InstanceDao;
import com.shcj.cache.dao.MachineRoomDao;
import com.shcj.cache.entity.InstanceInfo;
import com.shcj.cache.exception.BizException;
import com.shcj.cache.exception.SSHException;
import com.shcj.cache.redis.RedisCenter;
import com.shcj.cache.ssh.SSHService;
import com.shcj.cache.ssh.SSHTemplate;
import com.shcj.cache.task.BaseTask;
import com.shcj.cache.task.constant.InstanceRoleEnum;
import com.shcj.cache.task.entity.NutCrackerNode;
import com.shcj.cache.task.entity.RedisSentinelNode;
import com.shcj.cache.task.entity.RedisServerNode;
import com.shcj.cache.task.util.AppWechatUtil;
import com.shcj.cache.util.ConstUtils;
import com.shcj.cache.web.enums.AppTypeEnum;
import com.shcj.cache.web.service.InstancePortService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * @author fulei
 * @date 2018年7月4日
 * @time 下午3:29:10
 */
@Service
public class InstancePortServiceImpl implements InstancePortService {

    private Logger logger = LoggerFactory.getLogger(InstancePortServiceImpl.class);

	/*@Value("${appEnvName}")
	private String appEnvName;*/

    @Autowired
    private AppWechatUtil appWechatUtil;

    /**
     * 机房dao
     */
    @Autowired
    private MachineRoomDao machineRoomDao;

    /**
     * 实例dao
     */
    @Autowired
    private InstanceDao instanceDao;

    @Autowired
    private SSHService sshService;

    /**
     * redis-port实例dao
     */
//	private RedisPortInstanceDao redisPortInstanceDao;

    /**
     * redis-migrate-tool实例dao
     */
//	private RedisMigrateToolInstanceDao redisMigrateToolInstanceDao;

    /**
     * redis相关
     */
    @Autowired
    @Lazy
    private RedisCenter redisCenter;


    /**
     * 1. 主从不同机器
     * 2. 端口从起始端口开始自增1
     */
    @Override
    public List<RedisServerNode> generateRedisServerNodeList(long appId, List<String> redisMasterMachineList,
                                                             List<String> redisSlaveMachineList, int maxMemory, AppTypeEnum appTypeEnum) {
        if (appTypeEnum == AppTypeEnum.REDIS_STANDALONE) {
            return genRedisServerNodeListStandalone(appId, redisMasterMachineList, maxMemory);
        } else if (appTypeEnum == AppTypeEnum.REDIS_SENTINEL) {
            return genRedisServerNodeListSentinel(appId, redisMasterMachineList, redisSlaveMachineList, maxMemory);
        } else if (appTypeEnum == AppTypeEnum.REDIS_CLUSTER) {
            return genRedisServerNodeListCluster(appId, redisMasterMachineList, redisSlaveMachineList, maxMemory);
        }
        throw new BizException("不支持的应用类型 appId={}, appType={}", appId, appTypeEnum);
    }

    private List<RedisServerNode> genRedisServerNodeListStandalone(long appId, List<String> redisMasterMachineList, int maxMemory) {
        // 最终结果
        List<RedisServerNode> redisServerNodeList = new ArrayList<RedisServerNode>();
        // 起始节点
        int basePort = ConstUtils.REDIS_SERVER_BASE_PORT;
        String masterHost = redisMasterMachineList.get(0);
        Integer maxMasterPort = instanceDao.getMaxRedisPortByIp(masterHost);
        int masterPort = maxMasterPort == null ? basePort : maxMasterPort + 1;
        while (checkHostPortExist(masterHost, masterPort)) {
            masterPort++;
        }
        redisServerNodeList.add(new RedisServerNode(masterHost, masterPort, InstanceRoleEnum.MASTER.getRole(),
                maxMemory, "", 0));
        return redisServerNodeList;
    }

    private List<RedisServerNode> genRedisServerNodeListSentinel(long appId, List<String> redisMasterMachineList, List<String> redisSlaveMachineList, int maxMemory) {
        // 最终结果
        List<RedisServerNode> redisServerNodeList = new ArrayList<RedisServerNode>();
        // 起始节点
        int basePort = ConstUtils.REDIS_SERVER_BASE_PORT;

        // master node
        String masterHost = redisMasterMachineList.get(0);
        Integer maxMasterPort = instanceDao.getMaxRedisPortByIp(masterHost);
        int masterPort = maxMasterPort == null ? basePort : maxMasterPort + 1;
        while (checkHostPortExist(masterHost, masterPort)) {
            masterPort++;
        }
        redisServerNodeList.add(new RedisServerNode(masterHost, masterPort, InstanceRoleEnum.MASTER.getRole(),
                maxMemory, "", 0));

        for (String slaveIp : redisSlaveMachineList) {
            // slave node
            String slaveHost = slaveIp;
            Integer maxSlavePort = null;
            if (masterHost.equals(slaveHost)) {
                maxSlavePort = masterPort;
            } else {
                maxSlavePort = instanceDao.getMaxRedisPortByIp(slaveHost);
            }
            int slavePort = maxSlavePort == null ? basePort : maxSlavePort + 1;
            while (checkHostPortExist(slaveHost, slavePort)) {
                slavePort++;
            }
            redisServerNodeList.add(new RedisServerNode(slaveHost, slavePort, InstanceRoleEnum.SLAVE.getRole(),
                    maxMemory, masterHost, masterPort));
        }

        return redisServerNodeList;
    }

    private List<RedisServerNode> genRedisServerNodeListCluster(long appId, List<String> redisMasterMachineList, List<String> redisSlaveMachineList, int maxMemory) {

        // 最终结果
        List<RedisServerNode> redisServerNodeList = new ArrayList<RedisServerNode>();
        // 起始节点
        int basePort = ConstUtils.REDIS_SERVER_BASE_PORT;

        Map<String, Integer> maxPortMap = new HashMap<>(6);

        for (int i = 0; i < redisMasterMachineList.size(); i++) {
            // master node
            String masterHost = redisMasterMachineList.get(i);
            Integer maxMasterPort = null;
            if (maxPortMap.get(masterHost) != null) {
                maxMasterPort = maxPortMap.get(masterHost);
            } else {
                maxMasterPort = instanceDao.getMaxRedisPortByIp(masterHost);
            }
            int masterPort = maxMasterPort == null ? basePort : maxMasterPort + 1;
            while (checkHostPortExist(masterHost, masterPort)) {
                masterPort++;
            }
            redisServerNodeList.add(new RedisServerNode(masterHost, masterPort, InstanceRoleEnum.MASTER.getRole(),
                    maxMemory, "", 0));
            maxPortMap.put(masterHost, masterPort);

            // slave node
            String slaveHost = redisSlaveMachineList.get(i);
            Integer maxSlavePort = null;
            if (maxPortMap.get(slaveHost) != null) {
                maxSlavePort = maxPortMap.get(slaveHost);
            } else {
                maxSlavePort = instanceDao.getMaxRedisPortByIp(slaveHost);
            }
            int slavePort = maxSlavePort == null ? basePort : maxSlavePort + 1;
            while (checkHostPortExist(slaveHost, slavePort)) {
                slavePort++;
            }
            redisServerNodeList.add(new RedisServerNode(slaveHost, slavePort, InstanceRoleEnum.SLAVE.getRole(),
                    maxMemory, masterHost, masterPort));
            maxPortMap.put(slaveHost, slavePort);
        }

        return redisServerNodeList;
    }

    @Override
    public List<RedisSentinelNode> generateRedisSentinelNodeList(long appId, List<String> redisSentinelMachineList) {
        // 最终结果
        List<RedisSentinelNode> redisSentinelNodeList = new ArrayList<RedisSentinelNode>();
        int basePort = ConstUtils.REDIS_SENTINEL_BASE_PORT;

        for (String ip : redisSentinelMachineList) {
            Integer maxPort = instanceDao.getMaxSentinelPortByIp(ip);
            int sentinelPort = maxPort == null ? basePort : maxPort + 1;
            //如果端口存在就自增，一直到可用端口
            while (checkHostPortExist(ip, sentinelPort)) {
                sentinelPort++;
            }
            redisSentinelNodeList.add(new RedisSentinelNode(ip, sentinelPort));
        }
        return redisSentinelNodeList;
    }

    /**
     * 每台端口从baseport开始自增1
     */
    @Override
    public List<NutCrackerNode> generateNutCrackerNodeList(long appId, List<String> nutCrackerMachineList,
                                                           int nutCrackerPerMachine) {
//		// 最终结果
//		List<NutCrackerNode> nutCrackerNodeList = new ArrayList<NutCrackerNode>();
//		// 起始节点
//		int port =(int) (ConstUtils.NUT_CRACKER_BASE_PORT + (appId % 10) * 10);
//		for (String ip : nutCrackerMachineList) {
//			int initPort = port;
//			for (int j = 0; j < nutCrackerPerMachine; j++) {
//				//如果端口存在就自增
//				while (checkNutCrackerHostPortExist(ip, initPort)) {
//					initPort++;
//				}
//				nutCrackerNodeList.add(new NutCrackerNode(ip, initPort));
//				initPort++;
//			}
//		}
//		return nutCrackerNodeList;
        // @todo fulei
        return null;
    }
	
	/*@Override
	public List<CodisProxyNode> generateCodisProxyList(long appId, List<String> codisProxyMachineList,
			int codisProxyPerMachine) {
		// 最终结果
		List<CodisProxyNode> codisProxyNodeList = new ArrayList<CodisProxyNode>();
		// 起始节点
		int port;
		if (appEnvName.equals(AppEnvNameEnum.afun.getName())) {
			port = ConstUtils.CODIS_PROXY_BASE_PORT;
		} else {
			port = (int) (ConstUtils.CODIS_PROXY_BASE_PORT + (appId % 10) * 10);
		}
		for (String ip : codisProxyMachineList) {
			int initPort = port;
			for (int j = 0; j < codisProxyPerMachine; j++) {
				//如果端口存在就自增
				while (checkCodisProxyHostPortExist(ip, initPort)) {
					initPort++;
				}
				codisProxyNodeList.add(new CodisProxyNode(ip, initPort));
				initPort++;
			}
		}
		return codisProxyNodeList;
	}*/

	/*@Override
	public List<CodisDashboardNode> generateCodisDashboardList(long appId, List<String> codisDashboardMachineList) {
		// 最终结果
		List<CodisDashboardNode> codisDashboardNodeList = new ArrayList<CodisDashboardNode>();
		int port = ConstUtils.CODIS_DASHBOARD_BASE_PORT;
		for (String ip : codisDashboardMachineList) {
			// 如果端口存在就自增
			while (checkDashboardHostPortExist(ip, port)) {
				port++;
			}
			codisDashboardNodeList.add(new CodisDashboardNode(ip, port));
			port++;
		}
		return codisDashboardNodeList;
	}*/

    @Override
    public boolean checkHostPortExist(String ip, int port) {
        InstanceInfo instanceInfo = instanceDao.getAllInstByIpAndPort(ip, port);
        if (instanceInfo != null) {
            throw new BizException("instanceInfo != null, ip={}, port={}", ip, port);
        }
        //只检测一次
        String cmd = "ss -anop | awk '{print $5}'| grep :" + port + " | wc -l";
        try {
            String execute = sshService.execute(ip, cmd);
            return "0".equals(execute) ? false : true;
        } catch (SSHException e) {
            throw new BizException("ssh exception ip={}, cmd={}", ip, cmd);
        }
    }

    /**
     * 暂时和nut cracker一样
     *
     * @return
     */
	/*private boolean checkCodisProxyHostPortExist(String ip, int port) {
		List<InstanceInfo> instanceInfoList = instanceDao.getInstanceByIpAndStatPort(ip, port);
		if (CollectionUtils.isNotEmpty(instanceInfoList)) {
			return true;
		} else {
			return checkHostPortExist(ip, port);
		}
	}*/
    private boolean checkNutCrackerHostPortExist(String ip, int port) {
//		List<InstanceInfo> instanceInfoList = instanceDao.getInstanceByIpAndStatPort(ip, port);
//		if (CollectionUtils.isNotEmpty(instanceInfoList)) {
//			return true;
//		} else {
//			return checkHostPortExist(ip, port);
//		}
        // @todo fulei
        return true;
    }

    public boolean checkDashboardHostPortExist(String ip, int port) {
        InstanceInfo instanceInfo = instanceDao.getAllInstByIpAndPort(ip, port);
        if (instanceInfo != null) {
            return true;
        }
        return false;
    }

    private boolean checkRedisMigrateToolPortExist(String ip, int port) {
        // @todo fulei
        return true;
//		RedisMigrateToolInstance redisMigrateToolInstance = redisMigrateToolInstanceDao.getByHostAndPort(ip, port);
//		if (redisMigrateToolInstance != null) {
//			return true;
//		}
//		//只检测一次
//		boolean isRedisRun = redisCenter.isRun(ip, port, 1);
//		if (isRedisRun) {
//			appWechatUtil.noticeWildInstance(ip, port);
//			logger.warn(BaseTask.marker, "{}:{} process is not in instance_info table", ip, port);
//			return true;
//		}
//		return false;
    }
	
	
	/*@Override
	public List<RedisPortNode> generateRedisPortNodeList(long sourceAppId, long targetAppId,
			List<InstanceInfo> slaveInstanceInfoList, List<InstanceInfo> proxyInstanceInfoList) {
		
		List<RedisPortNode> redisPortNodeList = new ArrayList<RedisPortNode>();
		
		for (int i = 0; i < slaveInstanceInfoList.size(); i++) {
			//proxy索引
			int index = i % proxyInstanceInfoList.size();
			
			InstanceInfo proxyInstanceInfo = proxyInstanceInfoList.get(index);
			InstanceInfo slaveInstanceInfo  = slaveInstanceInfoList.get(i);
			
			String redisPortHost = slaveInstanceInfo.getIp();
			int redisPortPort = slaveInstanceInfoList.get(i).getPort() + ConstUtils.REDIS_PORT_PORT_INCREASE;
			String sourceHost = slaveInstanceInfo.getIp();
			int sourcePort = slaveInstanceInfo.getPort();
			String targetHost = proxyInstanceInfo.getIp();
			int targetPort = proxyInstanceInfo.getPort();
		
			while (checkRedisPortExist(redisPortHost, redisPortPort, sourceHost, sourcePort, targetHost, targetPort)) {
				redisPortPort++;
			}
			
			RedisPortNode redisPortNode = new RedisPortNode();
			redisPortNode.setIp(redisPortHost);
			redisPortNode.setPort(redisPortPort);
			redisPortNode.setSourceInstanceId(slaveInstanceInfo.getId());
			redisPortNode.setSourceIp(sourceHost);
			redisPortNode.setSourcePort(sourcePort);
			redisPortNode.setTargetInstanceId(proxyInstanceInfo.getId());
			redisPortNode.setTargetIp(targetHost);
			redisPortNode.setTargetPort(targetPort);
			
			redisPortNodeList.add(redisPortNode);
		}
		
		return redisPortNodeList;
	}*/

    @Override
    public RedisServerNode generateRedisServerNode(long appId, String host, int maxMemory, InstanceRoleEnum instanceRoleEnum) {
        synchronized (host.intern()) {
            try {
                //防止端口重复 @TODO也可以用本地缓存做端口限制
                TimeUnit.SECONDS.sleep(3);
                int port = (int) (ConstUtils.REDIS_SERVER_BASE_PORT + (appId % 10) * 10);
                if (InstanceRoleEnum.SLAVE.equals(instanceRoleEnum)) {
                    port += ConstUtils.SLAVE_PORT_INCREASE;
                }
                while (checkHostPortExist(host, port)) {
                    logger.info(BaseTask.marker, "appId {} host {} port is {}", appId, host, port);
                    port++;
                }
                logger.info(BaseTask.marker, "final appId {} host {} port is {}", appId, host, port);
                return new RedisServerNode(host, port, maxMemory);
            } catch (Exception e) {
                logger.error(BaseTask.marker, e.getMessage(), e);
                return null;
            }
        }
    }
	
	/*@Override
	public PikaNode generatePikaNode(long appId, String host, int maxMemory, InstanceRoleEnum instanceRoleEnum) {
		synchronized (host.intern()) {
			try {
				//防止端口重复 @TODO也可以用本地缓存做端口限制
				TimeUnit.SECONDS.sleep(3);
				int port = (int) (ConstUtils.PIKA_BASE_PORT + (appId % 10) * 10);
				if (InstanceRoleEnum.SLAVE.equals(instanceRoleEnum)) {
					port += ConstUtils.PIKA_SLAVE_PORT_INCREASE;
				}
				while (checkHostPortExist(host, port)) {
					logger.info(BaseTask.marker, "appId {} host {} port is {}", appId, host, port);
					port++;
				}
				logger.info(BaseTask.marker, "final appId {} host {} port is {}", appId, host, port);
				return new PikaNode(host, port);
			} catch (Exception e) {
				logger.error(BaseTask.marker, e.getMessage(), e);
				return null;
			}
		}
	}*/
	
	
	/*@Override
	public List<RedisMigrateToolNode> generateRedisMigrateToolNodeList(int rmtCount, String machineLogicName) {
		//最终结果
		List<RedisMigrateToolNode> redisMigrateToolNodeList = new ArrayList<RedisMigrateToolNode>();
		
		//所有rmt机器
		List<MachineInfo> redisMigrateToolMachineList = machineInfoDao.getMachineInfoByKsp(KspNodeEnum.MIGRATE_TOOL.getType());
		if (appEnvName.equals(AppEnvNameEnum.afun.getName())) {
			redisMigrateToolMachineList = machineInfoDao.getMachineInfoByKsp("afun-redis-migrate-tool");
			if (CollectionUtils.isNotEmpty(redisMigrateToolMachineList)) {
				String host = redisMigrateToolMachineList.get(0).getIp();
				int port = ConstUtils.REDIS_MIGRATE_TOOL_PORT;
				
				//检查表和心跳
				if (!checkRedisMigrateToolPortExist(host, port)) {
					redisMigrateToolNodeList.add(new RedisMigrateToolNode(host, port));
				}
			}
		} else {
			redisMigrateToolMachineList = machineInfoDao.getMachineInfoByKsp(KspNodeEnum.MIGRATE_TOOL.getType());
			for (MachineInfo redisMigrateToolMachine : redisMigrateToolMachineList) {
				MachineRoom machineRoom = machineRoomDao.getByName(redisMigrateToolMachine.getMachineRoomName());
				if (!machineLogicName.equals(machineRoom.getLogicName())) {
					continue;
				}
				if (redisMigrateToolNodeList.size() >= rmtCount) {
					break;
				}
				
				String host = redisMigrateToolMachine.getIp();
				int port = ConstUtils.REDIS_MIGRATE_TOOL_PORT;
				
				//检查表和心跳
				if (!checkRedisMigrateToolPortExist(host, port)) {
					redisMigrateToolNodeList.add(new RedisMigrateToolNode(host, port));
				}
			}
		}
		return redisMigrateToolNodeList;
	}*/

}