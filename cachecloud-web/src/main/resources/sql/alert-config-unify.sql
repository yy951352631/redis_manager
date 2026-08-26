-- 监控收口到「报警配置」。
--
-- 迁移三类此前独立于 instance_alert_configs 的监控：
--   1. 节点存活探测        原 InstanceRunInspector（10 秒 socket PING，无任何配置项）
--   2. 节点内存使用率      原 AppMemInspector，阈值在 app_desc.mem_alert_value
--   3. 节点客户端连接数    原 AppClientConnInspector，阈值在 app_desc.client_conn_alert_value
--      （原逻辑还有一道 ConstUtils.APP_CLIENT_CONN_THRESHOLD=2000 的硬上限，
--        这里按 LEAST(...) 迁出「实际生效值」，代码里的隐藏上限随之取消）
--      注：这两项只按节点判定，集群整体口径已去掉——集群超限必然伴随某个节点超限
--   4. 集群平均命中率      原 AppHitPrecentInspector，阈值在 app_desc.hit_precent_alert_value
--      （该列 0 表示不监控，因此只为 >0 的集群建行；没有集群启用时不建全局行）
--
-- 依赖唯一键 uniq_index(type, instance_id, alert_config, compare_type) 保证幂等。
-- type: 1=全局 3=按集群；compare_type: 1=小于 3=大于 4=不等于；check_cycle: 1=1分钟

-- ---------- 全局默认 ----------
INSERT IGNORE INTO instance_alert_configs
(alert_config, alert_value, config_info, type, instance_id, status, compare_type, check_cycle, update_time, last_check_time, important_level)
VALUES
('instance_alive',      'ok',   '节点存活探测(Redis看INFO采集/哨兵PING)',  1, 0, 1, 4, 1, NOW(), NOW(), 2),
('node_mem_used_ratio',  '90',   '节点内存使用率(单位：%)',      1, 0, 1, 3, 1, NOW(), NOW(), 1),
('node_client_conn',     '2000', '节点客户端连接数', 1, 0, 1, 3, 1, NOW(), NOW(), 1);

-- ---------- 按集群覆盖：仅迁移与全局默认不同的值 ----------
INSERT IGNORE INTO instance_alert_configs
(alert_config, alert_value, config_info, type, instance_id, status, compare_type, check_cycle, update_time, last_check_time, important_level)
SELECT 'node_mem_used_ratio', CAST(d.mem_alert_value AS CHAR), '节点内存使用率(单位：%)',
       3, d.app_id, 1, 3, 1, NOW(), NOW(), 1
FROM app_desc d
WHERE d.mem_alert_value IS NOT NULL AND d.mem_alert_value > 0 AND d.mem_alert_value <> 90;

INSERT IGNORE INTO instance_alert_configs
(alert_config, alert_value, config_info, type, instance_id, status, compare_type, check_cycle, update_time, last_check_time, important_level)
SELECT 'node_client_conn', CAST(LEAST(d.client_conn_alert_value, 2000) AS CHAR), '节点客户端连接数',
       3, d.app_id, 1, 3, 1, NOW(), NOW(), 1
FROM app_desc d
WHERE d.client_conn_alert_value IS NOT NULL AND d.client_conn_alert_value > 0
  AND LEAST(d.client_conn_alert_value, 2000) <> 2000;

INSERT IGNORE INTO instance_alert_configs
(alert_config, alert_value, config_info, type, instance_id, status, compare_type, check_cycle, update_time, last_check_time, important_level)
SELECT 'app_hit_percent', CAST(d.hit_precent_alert_value AS CHAR), '集群平均命中率(单位：%)',
       3, d.app_id, 1, 1, 1, NOW(), NOW(), 1
FROM app_desc d
WHERE d.hit_precent_alert_value IS NOT NULL AND d.hit_precent_alert_value > 0;

-- ---------- 阈值已迁出，删除 app_desc 上的三个报警列 ----------
ALTER TABLE app_desc
    DROP COLUMN mem_alert_value,
    DROP COLUMN client_conn_alert_value,
    DROP COLUMN hit_precent_alert_value;

-- ---------- 清理已下线的巡检作业 ----------
-- Quartz 使用 JDBC JobStore 持久化，从 spring-quartz.xml 移除 bean 不会删除库里的旧记录；
-- 残留的 inspectorJob 会在调度器启动时抛 ClassNotFoundException 并把触发器置为 ERROR。
DELETE FROM QRTZ_CRON_TRIGGERS    WHERE TRIGGER_GROUP = 'inspector';
DELETE FROM QRTZ_SIMPLE_TRIGGERS  WHERE TRIGGER_GROUP = 'inspector';
DELETE FROM QRTZ_SIMPROP_TRIGGERS WHERE TRIGGER_GROUP = 'inspector';
DELETE FROM QRTZ_BLOB_TRIGGERS    WHERE TRIGGER_GROUP = 'inspector';
DELETE FROM QRTZ_FIRED_TRIGGERS   WHERE TRIGGER_GROUP = 'inspector';
DELETE FROM QRTZ_TRIGGERS         WHERE TRIGGER_GROUP = 'inspector';
DELETE FROM QRTZ_JOB_DETAILS      WHERE JOB_GROUP = 'inspector';
