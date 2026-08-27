package com.shcj.cache.redis.util;

import redis.clients.jedis.DebugParams;
import redis.clients.jedis.Pipeline;
import redis.clients.jedis.Protocol;
import redis.clients.jedis.Response;
import redis.clients.jedis.util.SafeEncoder;

/**
 * @Author: zengyizhao
 * @DateTime: 2021/11/25 17:45
 * @Description:
 */
public class PipelineUtil {

    public static Response<Object> latencyHistory(Pipeline pipeline, String event){
        return pipeline.sendCommand(Command.LATENCY, Keyword.HISTORY.raw, SafeEncoder.encode(event));
    }

    public static Response<Object> clusterCountKeysInSlot(Pipeline pipeline, int slot){
        byte[][] args = new byte[2][];
        args[0] = SafeEncoder.encode(Protocol.CLUSTER_COUNTKEYINSLOT);
        args[1] = SafeEncoder.encode(String.valueOf(slot));
        return pipeline.sendCommand(Command.CLUSTER, args);
    }

    public static Response<Object> debug(Pipeline pipeline, DebugParams params){
        return pipeline.sendCommand(Command.DEBUG, params.getCommand());
    }

    public static Response<Object> memoryUsage(Pipeline pipeline, String key){
        return pipeline.sendCommand(Command.MEMORY, Keyword.USAGE.raw, SafeEncoder.encode(key));
    }

}
