package com.shcj.cache.stats.app;

import com.shcj.cache.constant.ImportAppResult;
import com.shcj.cache.entity.AppUser;
import com.shcj.cache.entity.ExternalRedis;

import com.shcj.cache.web.vo.InstanceIpSearchVO;
import com.shcj.cache.web.vo.ExternalNodeVO;

import java.util.List;
import java.util.Map;

/**
 * 外部 Redis 纳管（不依赖 machine_info / SSH）
 */
public interface ExternalRedisCenter {

    List<ExternalRedis> listWithInstances();

    /**
     * 外部纳管节点扁平列表（每个 ip:port 一行）。
     *
     * <p>列表只读取最近一次持久化状态和采集指标，不在 HTTP 请求线程中连接 Redis。</p>
     */
    List<ExternalNodeVO> listExternalNodes(String ipQuery);

    /**
     * 从外部纳管实例实时读取 Redis 版本名，返回格式如 redis-6.2.10。
     */
    String detectRedisVersionName(ExternalRedis externalRedis);

    String detectRedisVersionName(int type, String appInstanceInfo, String password);

    ImportAppResult check(int type, String appInstanceInfo, String password);

    ImportAppResult check(int type, String appInstanceInfo, String password, String sentinelPassword);

    ImportAppResult checkName(String name);

    ImportAppResult register(AppUser currentUser, String name, String intro, int type,
                             int isTest, String officer, String password, String appInstanceInfo, int versionId);

    ImportAppResult register(AppUser currentUser, String name, String intro, int type,
                             int isTest, String officer, String password, String sentinelPassword,
                             String appInstanceInfo, int versionId);

    /**
     * 立即触发一次监控采集（redis-cli），用于纳管后补采或排障。
     */
    ImportAppResult collectNow(long appId);

    /**
     * Collect statistics for all online externally managed Redis data nodes.
     */
    void collectStatistics();

    /**
     * 诊断纳管应用能否通过 redis-cli 采集并落库（排障用）。
     */
    Map<String, Object> diagnose(long appId);

    /**
     * 从 external_redis.instance_info 补写 instance_info（仅历史纳管缺实例时使用；新纳管会在 register 内同步写入）。
     */
    ImportAppResult repairInstances(long appId);

    /**
     * 为已纳管集群追加节点。
     *
     * @param appId            目标集群
     * @param appInstanceInfo  新增节点，每行 ip:port，sentinel 为 ip:port:masterName
     * @return 成功时 message 说明新增了几个节点
     */
    ImportAppResult addInstances(long appId, String appInstanceInfo);

    /**
     * 从纳管登记清单里摘掉某个节点。
     *
     * <p>节点下线后必须同步登记表，否则「补写节点」会按旧清单把它重新写回来。</p>
     *
     * @return 是否真的移除了一行
     */
    boolean removeInstanceLine(long appId, String ip, int port);

    /**
     * 把节点从平台移除（仅平台侧）。
     *
     * <p>不向 Redis 下发任何命令：不 CLUSTER FORGET、不关进程、不改主从关系。
     * 真实集群拓扑保持原样，平台只是不再纳管/采集这个节点。</p>
     */
    ImportAppResult removeInstance(long appId, int instanceId);

    /**
     * 按实例 IP 或 ip:port 在 instance_info 中反查应用（集群任意节点 IP 均可命中同一 appId）。
     */
    List<InstanceIpSearchVO> searchByInstanceIp(String ipQuery);
}
