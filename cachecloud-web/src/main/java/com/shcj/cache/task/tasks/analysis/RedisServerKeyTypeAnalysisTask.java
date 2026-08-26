package com.shcj.cache.task.tasks.analysis;

import com.google.common.util.concurrent.AtomicLongMap;
import com.shcj.cache.exception.BizException;
import com.shcj.cache.task.BaseTask;
import com.shcj.cache.task.constant.TaskConstants;
import com.shcj.cache.task.constant.TaskStepFlowEnum.TaskFlowStatusEnum;
import com.shcj.cache.util.ConstUtils;
import com.shcj.cache.util.ExceptionUtil;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang.StringUtils;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.Pipeline;
import redis.clients.jedis.ScanParams;
import redis.clients.jedis.ScanResult;
import redis.clients.jedis.exceptions.JedisRedirectionException;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.springframework.beans.factory.config.ConfigurableBeanFactory.SCOPE_PROTOTYPE;

/**
 * key类型分析
 *
 * @author fulei
 */
@Component("RedisServerKeyTypeAnalysisTask")
@Scope(SCOPE_PROTOTYPE)
public class RedisServerKeyTypeAnalysisTask extends BaseTask {

    private String host;

    private int port;

    private long appId;

    private long auditId;

    /**
     * 扫描slave
     */
    private final static int SCAN_COUNT = 100;

    @Override
    public List<String> getTaskSteps() {
        List<String> taskStepList = new ArrayList<String>();
        taskStepList.add(TaskConstants.INIT_METHOD_KEY);
        // 检查实例是否运行
        taskStepList.add("checkIsRun");
        // key类型分析
        taskStepList.add("keyTypeAnalysis");
        return taskStepList;
    }

    /**
     * 初始化参数
     *
     * @return
     */
    @Override
    public TaskFlowStatusEnum init() {
        super.init();

        appId = MapUtils.getLongValue(paramMap, TaskConstants.APPID_KEY);
        if (appId < 0) {
            throw new BizException("task {} appId {} is wrong", taskId, appId);
        }

        auditId = MapUtils.getLongValue(paramMap, TaskConstants.AUDIT_ID_KEY);
        if (auditId < 0) {
            throw new BizException("task {} auditId {} is wrong", taskId, auditId);
        }

        host = MapUtils.getString(paramMap, TaskConstants.HOST_KEY);
        if (StringUtils.isBlank(host)) {
            throw new BizException("task {} host is empty", taskId);
        }

        port = MapUtils.getIntValue(paramMap, TaskConstants.PORT_KEY);
        if (port < 0) {
            throw new BizException("task {} port {} is wrong", taskId, port);
        }

        return TaskFlowStatusEnum.SUCCESS;
    }

    public TaskFlowStatusEnum checkIsRun() {
        if (!redisCenter.isRun(appId, host, port)) {
            throw new BizException("{} {}:{} is not run", appId, host, port);
        }
        return TaskFlowStatusEnum.SUCCESS;
    }

    public TaskFlowStatusEnum keyTypeAnalysis() {
        long startTime = System.currentTimeMillis();

        Jedis jedis = null;
        try {
            jedis = redisCenter.getJedis(appId, host, port);
            try {
                jedis.readonly();
            } catch (Exception ex) {
                logger.info("jedis set readonly fail. appId={} host={} port={}", appId, host, port, ex);
                logger.info(marker, "jedis set readonly fail. appId={} host={} port={}", appId, host, port);
            }

            long dbSize = jedis.dbSize();
            if (dbSize == 0) {
                logger.info(marker, "{} {}:{} dbsize is {}", appId, host, port, dbSize);
                return TaskFlowStatusEnum.SUCCESS;
            }
            logger.info(marker, "{} {}:{} total key is {} ", appId, host, port, dbSize);

            ScanParams scanParams = new ScanParams().count(SCAN_COUNT);
            byte[] cursor = "0".getBytes(Charset.forName("UTF-8"));

            AtomicLongMap<String> typeCountMap = AtomicLongMap.create();
            long count = 0;
            int totalSplit = 10;
            int curSplit = 1;
            while (true) {
                try {
                    ScanResult<byte[]> scanResult = jedis.scan(cursor, scanParams);
                    cursor = scanResult.getCursorAsBytes();
                    List<byte[]> keyList = scanResult.getResult();

                    Pipeline pipeline = jedis.pipelined();
                    keyList.stream().forEach(key -> pipeline.type(key));

                    List<Object> typeObjectList;
                    try {
                        typeObjectList = pipeline.syncAndReturnAll();
                    } catch (JedisRedirectionException e) {
                        continue; // ignore
                    }

                    typeObjectList.stream()
                            .filter(type -> !"none".equalsIgnoreCase(String.valueOf(type)) && (type instanceof String))
                            .forEach(type -> typeCountMap.incrementAndGet(String.valueOf(type)));

                    count += keyList.size();
                    if (count > dbSize / totalSplit * curSplit) {
                        logger.info(marker, "{} {}:{} has already anlysis {}% {} key ", appId, host, port,
                                curSplit * 10, count);
                        curSplit++;
                    }
                } catch (Exception e) {
                    logger.error(marker, e.getMessage(), e);
                } finally {
                    //防止无限循环
                    if (Arrays.equals("0".getBytes(StandardCharsets.UTF_8), cursor)) {
                        break;
                    }
                }
            }
            logger.info(marker, "{} {}:{} analysis key type successfully, cost time is {} ms, total key is {}", appId,
                    host, port, (System.currentTimeMillis() - startTime), count);

            String keyTypeResultKey = ConstUtils.getRedisServerTypeKey(appId, auditId);
            Map<String, Long> typeCounts = new HashMap<String, Long>();
            typeCountMap.asMap().forEach((type, typeCount) -> {
                typeCounts.put(type, typeCount);
                logger.info(marker, "{} {} {}:{} type distri {} {}", keyTypeResultKey, appId, host, port, type, typeCount);
            });
            flushAssistZsetCounts(keyTypeResultKey, typeCounts, "键类型");
            requireLocalStatsWhenDbNonEmpty(dbSize, !typeCountMap.isEmpty(), host, port, "键类型");

            return TaskFlowStatusEnum.SUCCESS;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException("keyTypeAnalysis failed host={}  port={}  errMsg={} stack={}", host, port, e.getMessage(), ExceptionUtil.getStackTraceInfo(e));
        } finally {
            if (jedis != null) {
                jedis.close();
            }
        }
    }

}
