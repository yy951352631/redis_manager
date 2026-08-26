-- =============================================================================
-- 清理 cachecloud-open 中外部纳管测试数据（app_id>=2 的纳管应用 + 139.196.144.53 实例统计）
-- ⚠️ 不删除远程 Redis 数据；不删除 app_id=1 等平台默认应用（若存在）
-- ⚠️ 执行前请确认：USE 的库名与 application-test.yml 一致
-- =============================================================================

USE `cachecloud-open`;

START TRANSACTION;

-- 待删应用：external_redis 登记 + 常见测试 app_id 2,3
-- 若还有其它 app_id，可先查：SELECT app_id FROM external_redis;

SET @del_apps = '2,3';

-- ---------- 按 app_id 删除统计 / 客户端 / 任务等 ----------
DELETE FROM app_minute_command_statistics WHERE app_id IN (2, 3);
DELETE FROM app_hour_command_statistics WHERE app_id IN (2, 3);
DELETE FROM app_minute_statistics WHERE app_id IN (2, 3);
DELETE FROM app_hour_statistics WHERE app_id IN (2, 3);
DELETE FROM app_client_command_minute_statistics WHERE app_id IN (2, 3);
DELETE FROM app_client_exception_minute_statistics WHERE app_id IN (2, 3);
DELETE FROM app_client_value_minute_stats WHERE app_id IN (2, 3);
DELETE FROM app_client_statistic_gather WHERE app_id IN (2, 3);
DELETE FROM app_client_version_statistic WHERE app_id IN (2, 3);
DELETE FROM app_daily WHERE app_id IN (2, 3);
DELETE FROM app_alert_record WHERE app_id IN (2, 3);
DELETE FROM app_audit_log WHERE app_id IN (2, 3);
DELETE FROM app_audit WHERE app_id IN (2, 3);
DELETE FROM app_to_module WHERE app_id IN (2, 3);
DELETE FROM app_to_user WHERE app_id IN (2, 3);
DELETE FROM instance_reshard_process WHERE app_id IN (2, 3);
DELETE FROM instance_big_key WHERE app_id IN (2, 3);
DELETE FROM instance_slow_log WHERE app_id IN (2, 3);
DELETE FROM instance_latency_history WHERE app_id IN (2, 3);
DELETE FROM instance_fault WHERE app_id IN (2, 3);
DELETE FROM app_import WHERE app_id IN (2, 3);
DELETE FROM app_data_migrate_status WHERE source_app_id IN (2, 3) OR target_app_id IN (2, 3);

DELETE FROM instance_statistics WHERE app_id IN (2, 3);

DELETE FROM instance_minute_stats
WHERE ip = '139.196.144.53';

DELETE FROM standard_statistics
WHERE ip = '139.196.144.53';

DELETE FROM instance_info WHERE app_id IN (2, 3);

DELETE FROM external_redis WHERE app_id IN (2, 3);
DELETE FROM app_desc WHERE app_id IN (2, 3);

COMMIT;

-- 验证
SELECT app_id, name, type FROM app_desc ORDER BY app_id;
SELECT app_id, name FROM external_redis;
SELECT id, app_id, ip, port FROM instance_info WHERE ip = '139.196.144.53';
SELECT COUNT(*) AS inst_stat_cnt FROM instance_statistics WHERE ip = '139.196.144.53';
