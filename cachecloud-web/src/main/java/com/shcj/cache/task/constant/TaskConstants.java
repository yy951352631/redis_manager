package com.shcj.cache.task.constant;

import com.shcj.cache.task.tasks.MachineSyncTask;
import com.shcj.cache.task.tasks.RedisClusterAppDeployTask;
import com.shcj.cache.task.tasks.RedisSentinelAppDeployTask;
import com.shcj.cache.task.tasks.RedisStandaloneAppDeployTask;
import com.shcj.cache.task.tasks.daily.MachineExamTask;
import com.shcj.cache.task.tasks.daily.TopologyExamTask;
import com.shcj.cache.task.tasks.install.RedisSentinelInstallTask;
import com.shcj.cache.task.tasks.install.RedisServerInstallTask;
import com.shcj.cache.task.tasks.resource.PackCompileTask;

/**
 * 任务相关常量
 *
 * @author fulei
 */
public class TaskConstants {

    /**
     * init方法
     */
    public final static String INIT_METHOD_KEY = "init";

    /**
     * 任务id
     */
    public final static String TASK_ID_KEY = "taskId";

    /**
     * 任务流id
     */
    public final static String TASK_STEP_FLOW_ID = "taskStepFlowId";

    /**
     * 各种类型实例列表
     */
    //单个实例
    public final static String REDIS_SERVER_NODE_KEY = "redisServerNode";
    public final static String REDIS_SERVER_NODES_KEY = "redisServerNodes";
    public final static String REDIS_SENTINEL_NODES_KEY = "redisSentinelNodes";
    public final static String NUT_CRACKER_NODES_KEY = "nutCrackerNodes";
    public final static String REDIS_PORT_NODES_KEY = "redisPortNodes";
    public final static String REDIS_MIGRATE_TOOL_NODES_KEY = "redisMigrateToolNodes";
    public final static String PIKA_NODES_KEY = "pikaNodes";
    public final static String PIKA_NODE_KEY = "pikaNode";
    public final static String MEMCACHE_NODES_KEY = "memcacheNodes";


    /**
     * for codis
     */
    public final static String CODIS_SERVER_NODES_KEY = "codisServerNodes";
    public final static String CODIS_PROXY_NODES_KEY = "codisProxyNodes";
    public final static String CODIS_DASHBOARD_NODES_KEY = "codisDashboardNodes";

    /**
     * 主节点列表
     */
    public final static String MASTER_REDIS_SERVER_NODES = "masterRedisServerNodes";

    /**
     * 实例信息
     */
    public final static String APPID_KEY = "appId";
    public final static String AUDIT_ID_KEY = "auditId";
    public final static String HOST_KEY = "host";
    public final static String PORT_KEY = "port";
    public final static String VERSION_KEY = "db_version";
    public final static String MODULE_KEY = "moduleVersions";
    public final static String IS_CLUSTER_KEY = "is_cluster";

    public final static String MASTER_HOST_KEY = "master_host";
    public final static String MASTER_PORT_KEY = "master_port";
    public final static String SLAVE_MACHINE_KEY = "slave_machine";
    public final static String SLAVE_MACHINE_LIST_KEY = "slave_machine_list";
    public final static String MACHINE_IP_LIST_KEY = "machineIpList";
    public final static String USE_TYPE_KEY = "useType";
    public final static String EXAM_TYPE_KEY = "examType";
    public final static String USER_INFO_KEY = "user_info_key";

    /**
     * redis-port相关
     */
    public final static String HTTP_PORT_KEY = "http_port";
    public final static String SOURCE_HOST_KEY = "source_host";
    public final static String SOURCE_PORT_KEY = "source_port";
    public final static String SOURCE_PASSWORD_KEY = "source_password";
    public final static String TARGET_HOST_KEY = "target_host";
    public final static String TARGET_PORT_KEY = "target_port";
    public final static String TARGET_PASSWORD_KEY = "target_password";

    /**
     * 机器相关
     */
    public final static String CONTAINER_IP = "container_ip";

    /**
     * 资源信息
     */
    public final static String RESOURCE_ID = "resource_id";
    public final static String REPOSITORY_ID = "repository_id";

    /**
     * flush config
     */
    public final static String APP_IS_NEW_KEY = "appIsNew";

    /**
     * redis machine
     */
    public final static String REDIS_MASTER_MACHINE_LIST_KEY = "redisMasterMachineList";
    public final static String REDIS_SLAVE_MACHINE_LIST_KEY = "redisSlaveMachineList";
    public final static String REDIS_SENTINEL_MACHINE_LIST_KEY = "redisSentinelMachineList";
    public final static String NUT_CRACKER_MACHINE_LIST_KEY = "nutCrackerMachineList";
    public final static String MASTER_PER_MACHINE_KEY = "masterPerMachine";
    public final static String SENTINEL_PER_MACHINE_KEY = "sentinelPerMachine";
    public final static String NUT_CRACKER_PER_MACHINE_KEY = "nutCrackerPerMachine";

    /**
     * pika
     */
    public final static String PIKA_MACHINE_LIST_KEY = "pikaMachineList";

    /**
     * for codis
     */
    public final static String CODIS_SERVER_MACHINE_LIST_KEY = "codisServerMachineList";
    public final static String CODIS_PRORY_MACHINE_LIST_KEY = "codisProxyMachineList";
    public final static String CODIS_DASHBOARD_MACHINE_LIST_KEY = "codisDashboardMachineList";
    public final static String CODIS_PROXY_PER_MACHINE_KEY = "codisProxyPerMachine";


    /**
     * redis sentinel quorum
     */
    public final static String REDIS_SENTINEL_QUORUM_KEY = "quorum";

    /**
     * redis server maxmemory
     */
    public final static String REDIS_SERVER_MAX_MEMORY_KEY = "maxMemory";

    /**
     * flush zk config taskId
     */
    public final static String FLUSH_ZK_CONFIG_TASKID_KEY = "flushZkConfigTaskId";


    /**
     * offline slave taskId
     */
    public final static String OFFLINE_SLAVE_TASKID_KEY = "offlineSlaveTaskId";

    /**
     * 是否需要刷新zk
     */
    public static final String IS_NEED_FLUSH_ZK_CONFIG_KEY = "isNeedFlushZkConfig";


    /**
     * stopRmtTaskId
     */
    public final static String STOP_RMT_TASKID_KEY = "stopRmtTaskId";

    /**
     * offlineSourceAppTaskId
     */
    public final static String OFFLINE_SOURCE_APP_TASKID_KEY = "offlineSourceAppTaskId";

    /**
     * 扩容
     */
    public final static String IS_SCALE_OUT_KEY = "isScaleOut";//是否是扩容
    public final static String IS_ONLY_MIGRATE_KEY = "isOnlyMigrate";//是否仅是迁移
    public final static String SOURCE_APP_ID_KEY = "sourceAppId";
    public final static String TARGET_APP_ID_KEY = "targetAppId";


    /**
     * redis server安装超时时间
     */
    public final static int REDIS_SERVER_INSTALL_TIMEOUT = 600;


    /**
     * pika安装超时时间
     */
    public final static int PIKA_INSTALL_TIMEOUT = 600;

    /**
     * codis server安装超时时间
     */
    public final static int CODIS_SERVER_INSTALL_TIMEOUT = 300;

    /**
     * redis server下线超时时间
     */
    public final static int REDIS_SERVER_OFFLINE_TIMEOUT = 300;

    /**
     * memcache下线超时时间
     */
    public final static int MEMCACHE_OFFLINE_TIMEOUT = 300;

    /**
     * pika下线超时时间
     */
    public final static int PIKA_OFFLINE_TIMEOUT = 300;

    /**
     * redis sentinel安装超时时间
     */
    public final static int REDIS_SENTINEL_INSTALL_TIMEOUT = 600;

    /**
     * redis sentinel下线超时时间
     */
    public final static int REDIS_SENTINEL_OFFLINE_TIMEOUT = 300;

    /**
     * nutcracker安装超时时间
     */
    public final static int NUT_CRACKER_INSTALL_TIMEOUT = 600;

    /**
     * codis proxy安装超时时间
     */
    public final static int CODIS_PROXY_INSTALL_TIMEOUT = 300;

    /**
     * codis dashboard安装超时时间
     */
    public final static int CODIS_DASHBOARD_INSTALL_TIMEOUT = 300;

    /**
     * nutcracker下线装超时时间
     */
    public final static int NUT_CRACKER_OFFLINE_TIMEOUT = 300;

    /**
     * redis port安装超时时间
     */
    public final static int REDIS_PORT_INSTALL_TIMEOUT = 300;

    /**
     * 刷zk配置超时
     */
    public final static int FLUSH_ZK_CONFIG_TIMEOUT = 300;

    /**
     * 下线slave超时
     */
    public final static int OFFLINE_SLAVE_TIMEOUT = 600;

    /**
     * rmt remove超时
     */
    public final static int RMT_REMOVE_TIMEOUT = 300;

    /**
     * rmt start超时
     */
    public final static int RMT_INSTALL_TIMEOUT = 300;


    /**
     * 应用下线超时
     */
    public final static int APP_OFFLINE_TIMEOUT = 600;

    /**
     * rmt同步超时时间
     */
    public final static int RMT_SYNC_TIMEOUT = 3600 * 4;

    /**
     * 等待proxy重启完成时间
     */
    public final static int NUTCRACKER_ALL_RESTART_TIMEOUT = 600;


    /**
     * redis server key type分析超时时间
     */
    public final static int REDIS_SERVER_KEY_TYPE_ANALYSIS_TIMEOUT = 1800;

    /**
     * redis server key ttl分析超时时间
     */
    public final static int REDIS_SERVER_KEY_TTL_ANALYSIS_TIMEOUT = 1800;

    /**
     * redis server key value size分析超时时间
     */
    public final static int REDIS_SERVER_KEY_VALUE_SIZE_ANALYSIS_TIMEOUT = 1800;


    /**
     * redis server 综合键值分析（一次 SCAN）超时时间
     */
    public final static int REDIS_SERVER_COMBINED_KEY_ANALYSIS_TIMEOUT = 1800;

    public final static String BIG_KEY_STRING_BYTES_KEY = "bigKeyStringBytes";
    public final static String BIG_KEY_COLLECTION_ELEMENTS_KEY = "bigKeyCollectionElements";

    /**
     * single rmt max used memory
     */
    public final static int SINGLE_RMT_USED_MEMORY_GB = 400;


    /**
     * max rmt count
     */
    public final static int MAX_RMT_COUNT = 3;


    /**
     * redis server big key分析超时时间
     */
    public final static int REDIS_SERVER_DIAGNOSTIC_TIMEOUT = 1800;

    /**
     * 最大内存限制(MB)
     */
    public final static int MAX_MEMORY_LIMIT = 1024 * 20;

    /**
     * 一次分配每个机器最大rediserver实例数
     */
    public final static int MAX_MASTER_PER_MACHINE = 50;

    /**
     * 一份分配每个机器最大nutcracker数
     */
    public final static int MAX_NUT_CRACK_PER_MACHINE = 50;

    /**
     * 一份分配每个机器最大codis proxy数
     */
    public final static int MAX_CODIS_PROXY_PER_MACHINE = 15;

    /**
     * AppKeyAnalysisTask 重试时仍需重新执行的步骤
     */
    public final static java.util.Set<String> APP_KEY_ANALYSIS_FORCE_RERUN_STEPS = java.util.Collections.unmodifiableSet(
            new java.util.HashSet<String>(java.util.Arrays.asList(
                    "createRedisServerCombinedKeyAnalysisTask",
                    "waitRedisServerCombinedKeyAnalysisFinish",
                    "showKeyAnalysisResult",
                    "updateAudit")));

    /**
     * RedisServerKeyCombinedAnalysisTask 重试时仍需重新执行的步骤
     */
    public final static java.util.Set<String> COMBINED_KEY_ANALYSIS_FORCE_RERUN_STEPS = java.util.Collections
            .unmodifiableSet(new java.util.HashSet<String>(java.util.Arrays.asList("combinedKeyAnalysis")));

}
