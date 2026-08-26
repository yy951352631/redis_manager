package com.shcj.cache.task.constant;

/**
 * Created by rucao on 2019/1/22
 */
public class TopoloyExamContants {
    public final static String APPID="appId";
    public final static String TYPE="type";
    public final static String STATUS="status";
    public final static String DESC="desc";

    public final static String REDIS_STANDALONE="redis-standalone";
    public final static String REDIS_CLUSTER="redis-cluster";
    public final static String REDIS_SENTINEL="redis-sentinel";

    public final static String INSTANCE_FORMAT="{0}:{1}:{2} 宿主机:{3}<br/>";
    public final static String CLUSTER_INSTANCE_FORMAT="{0}:{1}:{2} 宿主机:{3}<br/>";
    public final static String NETSEGMENT_FORMAT="存在至少两个网段，网段1：{0}，网段2：{1}";

    public final static String MASTER_SLAVE_DESC="主从节点分布同一台物理机";
    public final static String SLAVE_NOT_EXIST="主节点没有从节点";
    public final static String NODESNUM_DESC="集群节点分布在少于3台物理机";
    public final static String CLUSTER_FAILOVER_DESC="集群中一台物理机宕机不满足故障转移条件";
    public final static String NETSEGMENT_DESC="集群节点不在同网段";

    public final static String SLOT_LOSS_DESC = "集群存在丢失槽位";
    public final static String SLOT_INCOMPLETE_DESC = "槽位未完整覆盖";
    public final static String SLOT_IMBALANCE_DESC = "槽位分配严重不均";
    public final static String SLOT_FETCH_FAIL_DESC = "无法获取槽位信息";
    public final static String SLOT_LOSS_FORMAT = "节点 {0} 丢失槽位：{1}";
    public final static String SLOT_INCOMPLETE_FORMAT = "已覆盖 {0}/16384，缺失 {1} 个槽位";
    public final static String SLOT_IMBALANCE_FORMAT = "Master 槽位最少 {0}、最多 {1}，比值 {2}（阈值 1.5）";

    public final static String PARENT_ID_DRIFT_DESC = "主从 parent_id 与 Redis 不一致";
    public final static String PARENT_ID_DRIFT_FORMAT = "仍有 {0} 个实例 parent_id 未能与 Redis 角色对齐，请检查连通性或手动 Failover 后同步";

}
