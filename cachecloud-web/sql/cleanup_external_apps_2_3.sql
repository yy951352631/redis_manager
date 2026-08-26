-- =============================================================================
-- 清理外部纳管测试应用 redis1(app_id=2)、redis2(app_id=3) 及关联数据
-- 数据库：cachecloud-open（按实际库名修改 USE）
--
-- ⚠️ 仅删除平台元数据，不会删除远程 Redis 上的真实数据
-- ⚠️ 两条应用若共用同一 ip:port，instance_info / instance_statistics 通常只有一行
-- 执行前请先跑「第一节」预览，确认无误后再执行「第二节」
-- =============================================================================

USE `cachecloud-open`;

-- -----------------------------------------------------------------------------
-- 第一节：预览（只读）
-- -----------------------------------------------------------------------------
SELECT app_id, name, type, status FROM app_desc WHERE app_id IN (2, 3);
SELECT * FROM external_redis WHERE app_id IN (2, 3);
SELECT id, app_id, ip, port, host_id, status, mem FROM instance_info WHERE app_id IN (2, 3);
SELECT id, app_id, ip, port, used_memory, curr_connections FROM instance_statistics WHERE app_id IN (2, 3);

-- -----------------------------------------------------------------------------
-- 第二节：删除（建议整段在事务中执行，确认后 COMMIT）
-- -----------------------------------------------------------------------------
START TRANSACTION;

SET @app_ids = '2,3';  -- 若改其它应用，只改此处

-- ---------- 按 app_id 删除统计 / 客户端 / 任务等 ----------
DELETE FROM app_minute_command_statistics WHERE app_id IN (2, 3);
DELETE FROM app_hour_command_statistics WHERE app_id IN (2, 3);
DELETE FROM app_minute_statistics WHERE app_id IN (2, 3);
DELETE FROM app_hour_statistics WHERE app_id IN (2, 3);
DELETE FROM app_client_command_minute_statistics WHERE app_id IN (2, 3);
DELETE FROM app_client_exception_minute_statistics WHERE app_id IN (2, 3);
DELETE FROM app_client_exception_minute_stat WHERE app_id IN (2, 3);
DELETE FROM app_client_value_minute_stats WHERE app_id IN (2, 3);
DELETE FROM app_client_costtime_minute_stat WHERE app_id IN (2, 3);
DELETE FROM app_client_costtime_minute_stat_total WHERE app_id IN (2, 3);
DELETE FROM app_client_statistic_gather WHERE app_id IN (2, 3);
DELETE FROM app_client_version_statistic WHERE app_id IN (2, 3);
DELETE FROM app_client_instance WHERE app_id IN (2, 3);
DELETE FROM app_daily WHERE app_id IN (2, 3);
DELETE FROM app_alert_record WHERE app_id IN (2, 3);
DELETE FROM app_audit_log WHERE app_id IN (2, 3);
DELETE FROM app_audit WHERE app_id IN (2, 3);
DELETE FROM app_to_module WHERE app_id IN (2, 3);
DELETE FROM app_to_user WHERE app_id IN (2, 3);
DELETE FROM task_queue WHERE app_id IN (2, 3);
DELETE FROM instance_reshard_process WHERE app_id IN (2, 3);
DELETE FROM instance_big_key WHERE app_id IN (2, 3);
DELETE FROM instance_slow_log WHERE app_id IN (2, 3);
DELETE FROM instance_latency_history WHERE app_id IN (2, 3);
DELETE FROM instance_fault WHERE app_id IN (2, 3);
DELETE FROM config_restart_record WHERE app_id IN (2, 3);
DELETE FROM app_import WHERE app_id IN (2, 3);

-- 数据迁移若引用到这两个应用（一般测试环境为空）
DELETE FROM app_data_migrate_status WHERE source_app_id IN (2, 3) OR target_app_id IN (2, 3);

-- ---------- 实例级（先记下 ip:port，后面清 standard_statistics）----------
DELETE FROM instance_statistics WHERE app_id IN (2, 3);

DELETE FROM instance_minute_stats
WHERE (ip, port) IN (
    SELECT ip, port FROM (
        SELECT DISTINCT ip, port FROM instance_info WHERE app_id IN (2, 3)
    ) t
);

DELETE FROM standard_statistics
WHERE (ip, port) IN (
    SELECT ip, port FROM (
        SELECT DISTINCT ip, port FROM instance_info WHERE app_id IN (2, 3)
    ) t
);

-- 若上面子查询因 MySQL 版本报错，可改为手工指定：
-- DELETE FROM standard_statistics WHERE ip = '139.196.144.53' AND port = 6379;
-- DELETE FROM instance_minute_stats WHERE ip = '139.196.144.53' AND port = 6379;

DELETE FROM instance_info WHERE app_id IN (2, 3);

-- ---------- 纳管登记与应用主表 ----------
DELETE FROM external_redis WHERE app_id IN (2, 3);
DELETE FROM app_desc WHERE app_id IN (2, 3);

-- 预览删除结果（应为 0 行）
SELECT app_id, name FROM app_desc WHERE app_id IN (2, 3);
SELECT * FROM external_redis WHERE app_id IN (2, 3);
SELECT * FROM instance_info WHERE app_id IN (2, 3);

-- 确认无误后执行：
COMMIT;
-- 若要撤销：ROLLBACK;
