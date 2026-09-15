package com.shcj.cache.machine;

import com.google.common.util.concurrent.AtomicLongMap;
import com.shcj.cache.constant.EmptyObjectConstant;
import com.shcj.cache.constant.SymbolConstant;
import com.shcj.cache.exception.SSHException;
import com.shcj.cache.ssh.SSHUtil;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.math.NumberUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 生成一个redis可用端口
 *
 * @author: lingguo
 * @time: 2014/8/25 20:57
 */
public class PortGenerator {
    private static Logger logger = LoggerFactory.getLogger(PortGenerator.class);
    /**
     * redis port常量
     */
    private static final Integer REDIS_START_PORT = 6379;
    private static AtomicLongMap<String> redisPortHolder = AtomicLongMap.create();

    /**
     * 返回一个redis的可用端口：
     * - 1. 通过shell查询redis当前已用的最大port；
     * - 2. 为什么同步：防止多线程访问时获取到同样的端口；
     * - 3. 为什么还用原子计数：连续两次调用时，如果进程还没启动，则拿到的仍然是相同的端口；
     *
     * @param ip
     * @return
     */
    public static synchronized Integer getRedisPort(final String ip) {
        if (redisPortHolder.get(ip) == 0L) {
            redisPortHolder.put(ip, REDIS_START_PORT);
        }
        String maxPortStr = "";
        try {
            int sshPort = SSHUtil.getSshPort(ip);
            maxPortStr = getMaxPortStr(ip, sshPort);
        } catch (SSHException e) {
            logger.error("cannot get max port of redis by ssh, ip: {}", ip, e);
        }
        logger.warn("{} maxPort is {}", ip, maxPortStr);
        if (StringUtils.isBlank(maxPortStr) || !StringUtils.isNumeric(maxPortStr)) {
            logger.warn("{} the max port of redis is invalid, maxPortStr: {}", ip, maxPortStr);
            return (int) redisPortHolder.getAndIncrement(ip);
        }

        int availablePort = Integer.parseInt(maxPortStr) + 1;
        // 兼容连续调用的情况
        if (availablePort < redisPortHolder.get(ip)) {
            availablePort = (int) redisPortHolder.getAndIncrement(ip);
        } else {    // 正常情况，以及兼容系统重启和当前端口不可用的情形
            redisPortHolder.put(ip, availablePort + 1L);
        }

        logger.warn("first {} maxPort is {}", ip, availablePort);
        try {
            while (SSHUtil.isPortUsed(ip, availablePort)) {
                availablePort++;
            }
        } catch (SSHException e) {
            logger.error("check port error, ip: {}, port: {}", ip, availablePort, e);
        }
        logger.warn("final {} maxPort is {}", ip, availablePort);
        redisPortHolder.put(ip, availablePort + 1L);
        return availablePort;
    }

    /**
     * 直接解析ps -ef | grep redis | grep -v 'grep'
     *
     * @param ip
     * @param sshPort
     * @return
     * @throws SSHException
     */
    public static String getMaxPortStr(String ip, int sshPort) throws SSHException {
        String redisPidCmd = "ps -ef | grep redis | grep -v 'grep'";
        String redisProcessStr = SSHUtil.execute(ip, sshPort, redisPidCmd);
        if (StringUtils.isBlank(redisProcessStr)) {
            logger.warn("{} excute {}, result is empty", ip, redisPidCmd);
            return EmptyObjectConstant.EMPTY_STRING;
        }
        int maxPort = 0;
        String[] lines = redisProcessStr.split(SymbolConstant.ENTER);
        for (String line : lines) {
            if (StringUtils.isBlank(line)) {
                continue;
            }
            int redisServerIndex = line.indexOf("redis-server");
            int redisSentinelIndex = line.indexOf("redis-sentinel");
            logger.info("---> line={} redisServerIndex={} redisSentinelIndex={}", line, redisServerIndex, redisSentinelIndex);
            //fix "redis     76911      1  0 4月26 ?       01:08:07 /home/redis/redis-6.2.12/src/redis-server *:6406  /home/redis/conf/redis-sentinel-6406.conf"
            //既有 "redis-server" 又有 "redis-sentinel" 的场景下，只判断 redis-server
            boolean serverFlag = true;
            if (redisServerIndex >= 0) {
                line = line.substring(redisServerIndex);
                serverFlag = false;
            }
            if (serverFlag && redisSentinelIndex >= 0) {
                line = line.substring(redisSentinelIndex);
            }
            if (redisServerIndex < 0 && redisSentinelIndex < 0) {
                continue;
            }
            String[] items = line.split(SymbolConstant.SPACE);
            if (items.length >= 2) {
                String hostPort = items[1];
                if (StringUtils.isBlank(hostPort)) {
                    continue;
                }
                String[] hostPortArr = hostPort.split(SymbolConstant.COLON);
                if (hostPortArr.length != 2) {
                    continue;
                }
                String portStr = hostPortArr[1];
                if (!NumberUtils.isDigits(portStr)) {
                    continue;
                }
                int port = NumberUtils.toInt(portStr);
                if (port > maxPort) {
                    maxPort = port;
                }
            }
        }
        return maxPort == 0 ? EmptyObjectConstant.EMPTY_STRING : String.valueOf(maxPort);
    }

}
