-- =============================================================================
-- 插入一条「捏造」的外部纳管 Redis（仅平台元数据，不会真的起 Redis 进程）
-- 库名默认：cachecloud-open（按 application-test.yml 修改）
--
-- 使用前请确认：
--   1) 10.10.10.10:6380 未被其它应用占用（instance_info 全平台 ip+port 唯一）
--   2) 应用名 redis-fake-demo 不存在
--   3) version_id 与 system_resource 中 Redis 安装包 id 一致（见下方查询）
--   4) user_id / officer 改成你登录后台的用户 id（默认 1）
-- =============================================================================

USE `cachecloud-open`;

-- 查看可用的 Redis 版本资源（饼图用 version_id 关联此表 id）
-- SELECT id, name, type, status FROM system_resource WHERE type = 3 AND status = 1;

SET @redis_version_id = (
    SELECT id FROM system_resource WHERE name LIKE 'redis-%' AND status = 1 ORDER BY id LIMIT 1
);
-- 若上面为 NULL，可手工指定，例如：SET @redis_version_id = 29;

SET @user_id = 1;
SET @app_name = 'redis-fake-demo';
SET @app_intro = 'SQL 插入的测试应用（无真实 Redis）';
SET @redis_password = 'Fake@6380';
SET @instance_line = '10.10.10.10:6380:2048';
SET @fake_ip = '10.10.10.10';
SET @fake_port = 6380;

-- 占用检查（有结果则不要执行插入，改 IP/端口或应用名）
SELECT 'conflict app name' AS chk, app_id, name FROM app_desc WHERE name = @app_name
UNION ALL
SELECT 'conflict ip:port', app_id, CONCAT(ip, ':', port) FROM instance_info WHERE ip = @fake_ip AND port = @fake_port;

START TRANSACTION;

INSERT INTO app_desc (
    name, user_id, status, intro, create_time, passed_time, type,
    officer, ver_id, is_test, is_access_monitor, important_level,
    custom_password, version_id
) VALUES (
    @app_name, @user_id, 2, @app_intro, NOW(), NOW(), 6,
    CAST(@user_id AS CHAR), 1, 1, 0, 2,
    @redis_password, IFNULL(@redis_version_id, 29)
);

SET @app_id = LAST_INSERT_ID();

INSERT INTO external_redis (
    app_id, name, intro, type, password, instance_info, user_id, status, create_time, update_time
) VALUES (
    @app_id, @app_name, @app_intro, 6, @redis_password, @instance_line, @user_id, 1, NOW(), NOW()
);

INSERT INTO app_to_user (user_id, app_id) VALUES (@user_id, @app_id);

INSERT INTO instance_info (
    parent_id, app_id, host_id, ip, port, status, mem, conn, cmd, type, update_time
) VALUES (
    0, @app_id, 0, @fake_ip, @fake_port, 1, 2048, 0, '', 6, NOW()
);

-- 采集调度（与平台 maintainTasks 一致，type=2 表示 Redis 实例采集）
INSERT INTO brevity_schedule_resources (type, version, host, port, create_time)
VALUES (2, UNIX_TIMESTAMP(), @fake_ip, @fake_port, NOW());

COMMIT;

SELECT @app_id AS new_app_id, @app_name AS app_name, @fake_ip AS ip, @fake_port AS port,
       IFNULL(@redis_version_id, 29) AS version_id_used;

-- =============================================================================
-- 验证
-- =============================================================================
-- SELECT * FROM app_desc WHERE app_id = @app_id;
-- SELECT * FROM external_redis WHERE app_id = @app_id;
-- SELECT * FROM instance_info WHERE app_id = @app_id;
--
-- 页面：实例管理列表 / 全局统计「应用 Redis 版本分布」
-- 应用详情：/admin/app/index?appId=<new_app_id>
-- 注意：无真实 Redis 时「立即采集」会失败，仅元数据展示正常
--
-- =============================================================================
-- 回滚删除（按 app_id 改）
-- =============================================================================
-- SET @app_id = 10086;
-- DELETE FROM brevity_schedule_resources WHERE host = '10.10.10.10' AND port = 6380;
-- DELETE FROM instance_info WHERE app_id = @app_id;
-- DELETE FROM app_to_user WHERE app_id = @app_id;
-- DELETE FROM external_redis WHERE app_id = @app_id;
-- DELETE FROM app_desc WHERE app_id = @app_id;
