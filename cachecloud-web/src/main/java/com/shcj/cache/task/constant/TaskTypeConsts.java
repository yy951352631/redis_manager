package com.shcj.cache.task.constant;

import com.shcj.cache.task.BaseTask;
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
 * 任务类型
 *
 * @author zoushunqing 2023/2/24 14:34
 * @see BaseTask
 * @since Dev_1.0.1
 */
public class TaskTypeConsts {

    /**
     * redis server实例安装class name
     */
    public final static String REDIS_SERVER_INSTANCE_INSTALL_CLASS = RedisServerInstallTask.class.getSimpleName();

    /**
     * redis sentinel实例安装class name
     */
    public final static String REDIS_SENTINEL_INSTANCE_INSTALL_CLASS = RedisSentinelInstallTask.class.getSimpleName();

    /**
     * redis sentinel应用安装class name
     */
    public final static String REDIS_SENTINEL_APP_DEPLOY_CLASS = RedisSentinelAppDeployTask.class.getSimpleName();

    /**
     * redis cluster应用安装class name
     */
    public final static String REDIS_CLUSTER_APP_DEPLOY_CLASS = RedisClusterAppDeployTask.class.getSimpleName();

    /**
     * redis standalone应用安装class name
     */
    public final static String REDIS_STANDALONE_APP_DEPLOY_CLASS = RedisStandaloneAppDeployTask.class.getSimpleName();

    /**
     * 应用拓扑故障检查class name
     */
    public final static String TOPOLOGY_EXAM_CLASS = TopologyExamTask.class.getSimpleName();

    /**
     * 机器使用情况检查 class name
     */
    public final static String MACHINE_EXAM_CLASS = MachineExamTask.class.getSimpleName();
    /**
     * 机器同步数据任务 className
     */
    public final static String MACHINE_SYNC_CLASS = MachineSyncTask.class.getSimpleName();

    public final static String PACK_COMPILE_TASK = PackCompileTask.class.getSimpleName();

}
