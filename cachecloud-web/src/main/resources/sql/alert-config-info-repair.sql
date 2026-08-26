-- 修复 instance_alert_configs.config_info 的中文乱码。
--
-- 初始化 SQL 以非 UTF-8 字符集导入，导致这一列的中文全部损坏（形如 aof????(???MB)），
-- 而它正是「报警配置」页展示的「配置项」列。原文已不可逆，这里按
-- com.shcj.cache.redis.enums.RedisAlertConfigEnum 的 info 重新写回。
--
-- 只更新内置报警项，自定义项不受影响。幂等，可重复执行。

UPDATE instance_alert_configs SET config_info = 'aof当前尺寸(单位：MB)'            WHERE alert_config = 'aof_current_size';
UPDATE instance_alert_configs SET config_info = '分钟aof阻塞个数'                   WHERE alert_config = 'aof_delayed_fsync';
UPDATE instance_alert_configs SET config_info = '输入缓冲区最大buffer大小(单位：MB)' WHERE alert_config = 'client_biggest_input_buf';
UPDATE instance_alert_configs SET config_info = '输出缓冲区最大队列长度'             WHERE alert_config = 'client_longest_output_list';
UPDATE instance_alert_configs SET config_info = '实时ops'                          WHERE alert_config = 'instantaneous_ops_per_sec';
UPDATE instance_alert_configs SET config_info = '上次fork所用时间(单位：微秒)'       WHERE alert_config = 'latest_fork_usec';
UPDATE instance_alert_configs SET config_info = '内存碎片率(检测大于500MB)'         WHERE alert_config = 'mem_fragmentation_ratio';
UPDATE instance_alert_configs SET config_info = '上一次bgsave状态'                  WHERE alert_config = 'rdb_last_bgsave_status';
UPDATE instance_alert_configs SET config_info = '分钟拒绝连接数'                    WHERE alert_config = 'rejected_connections';
UPDATE instance_alert_configs SET config_info = '分钟部分复制失败次数'               WHERE alert_config = 'sync_partial_err';
UPDATE instance_alert_configs SET config_info = '分钟部分复制成功次数'               WHERE alert_config = 'sync_partial_ok';
UPDATE instance_alert_configs SET config_info = '分钟全量复制执行次数'               WHERE alert_config = 'sync_full';
UPDATE instance_alert_configs SET config_info = '分钟网络输入流量(单位：MB)'         WHERE alert_config = 'total_net_input_bytes';
UPDATE instance_alert_configs SET config_info = '分钟网络输出流量(单位：MB)'         WHERE alert_config = 'total_net_output_bytes';
UPDATE instance_alert_configs SET config_info = '主从节点偏移量差(单位：字节)'       WHERE alert_config = 'master_slave_offset_diff';
UPDATE instance_alert_configs SET config_info = '集群状态'                          WHERE alert_config = 'cluster_state';
UPDATE instance_alert_configs SET config_info = '集群成功分配槽个数'                 WHERE alert_config = 'cluster_slots_ok';
UPDATE instance_alert_configs SET config_info = '系统cpu消耗(单位：秒)'              WHERE alert_config = 'used_cpu_sys';
UPDATE instance_alert_configs SET config_info = '用户cpu消耗(单位：秒)'              WHERE alert_config = 'used_cpu_user';
UPDATE instance_alert_configs SET config_info = '系统子进程cpu消耗(单位：秒)'        WHERE alert_config = 'used_cpu_sys_children';
UPDATE instance_alert_configs SET config_info = '用户子进程cpu消耗(单位：秒)'        WHERE alert_config = 'used_cpu_user_children';
