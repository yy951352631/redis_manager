-- =============================================================================
-- 从 cache-cloud-master（v1.0.2 基线）升级到本平台所需的增量
--
-- 适用：库已按 master 的 create_database + v1.0.2（及可能的 add_20230516）建好
-- 不要：对已有库执行 init.sql / v1.0.2.sql（会 DROP TABLE）
--
-- 用法（先选对库名，与 application*.yml 一致）：
--   mysql -uroot -p redis_manager < upgrade.sql
--   # 或
--   mysql -uroot -p cachecloud-open < upgrade.sql
--
-- 可重复执行：已存在的列/表/索引会跳过。
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1) has_sentinel_pwd（master 的 add_20230516.sql）
-- -----------------------------------------------------------------------------
SET @exist := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'app_desc'
      AND COLUMN_NAME = 'has_sentinel_pwd'
);
SET @sql := IF(@exist = 0,
    'ALTER TABLE app_desc ADD COLUMN `has_sentinel_pwd` VARCHAR(45) NULL COMMENT ''yes/no'' AFTER `master_name`',
    'SELECT ''skip: has_sentinel_pwd already exists'' AS info'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- -----------------------------------------------------------------------------
-- 2) cluster_no（平台新增）
-- -----------------------------------------------------------------------------
SET @exist := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'app_desc'
      AND COLUMN_NAME = 'cluster_no'
);
SET @sql := IF(@exist = 0,
    'ALTER TABLE app_desc ADD COLUMN cluster_no INT UNSIGNED NULL COMMENT ''集群编号(对外展示)'' AFTER app_id',
    'SELECT ''skip: cluster_no already exists'' AS info'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 仅回填仍为 NULL 的行（从当前 MAX 之后继续，空表从 100001 起）
SET @n := IFNULL((SELECT MAX(c) FROM (SELECT cluster_no AS c FROM app_desc) t), 100000);
UPDATE app_desc
SET cluster_no = (@n := @n + 1)
WHERE cluster_no IS NULL
ORDER BY app_id ASC;

ALTER TABLE app_desc
    MODIFY COLUMN cluster_no INT UNSIGNED NOT NULL COMMENT '集群编号(对外展示)';
SET @exist := (
    SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'app_desc'
      AND INDEX_NAME = 'uidx_app_cluster_no'
);
SET @sql := IF(@exist = 0,
    'CREATE UNIQUE INDEX uidx_app_cluster_no ON app_desc (cluster_no)',
    'SELECT ''skip: uidx_app_cluster_no already exists'' AS info'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- -----------------------------------------------------------------------------
-- 3) external_redis（平台新增）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `external_redis`
(
    `id`            bigint(20)   NOT NULL AUTO_INCREMENT COMMENT '主键',
    `app_id`        bigint(20)   NOT NULL COMMENT '关联 app_desc.app_id',
    `name`          varchar(36)  NOT NULL COMMENT '应用名称，参照 app_desc.name',
    `intro`         varchar(255) NOT NULL DEFAULT '' COMMENT '应用描述，参照 app_desc.intro',
    `type`          int(10)      NOT NULL COMMENT '集群类型：2 cluster 5 sentinel 6 standalone',
    `password`      varchar(255)          DEFAULT '' COMMENT 'Redis 密码（明文登记，接入时写入 app_desc.custom_password）',
    `instance_info` text COMMENT '节点详情原文，格式同应用导入',
    `user_id`       bigint(20)   NOT NULL COMMENT '创建人',
    `status`        tinyint(4)   NOT NULL DEFAULT '1' COMMENT '1 正常 0 停用',
    `create_time`   datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`   datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_app_id` (`app_id`),
    KEY `idx_user_id` (`user_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8 COMMENT ='外部纳管Redis登记';

-- -----------------------------------------------------------------------------
-- 4) app_client_costtime_minute_stat_total（补齐历史缺失表）
-- -----------------------------------------------------------------------------

-- Client command latency summary
CREATE TABLE IF NOT EXISTS app_client_costtime_minute_stat_total (
    id                   BIGINT      NOT NULL AUTO_INCREMENT,
    app_id               BIGINT      NOT NULL,
    collect_time         BIGINT      NOT NULL,
    create_time          DATETIME    NOT NULL,
    command              VARCHAR(64) NOT NULL,
    mean                 DOUBLE      NOT NULL DEFAULT 0,
    median               INT         NOT NULL DEFAULT 0,
    ninety_percent_max   INT         NOT NULL DEFAULT 0,
    ninety_nine_percent_max INT      NOT NULL DEFAULT 0,
    hundred_max          INT         NOT NULL DEFAULT 0,
    total_cost           DOUBLE      NOT NULL DEFAULT 0,
    total_count          BIGINT      NOT NULL DEFAULT 0,
    max_instance_host    VARCHAR(64) DEFAULT '',
    max_instance_port    INT         NOT NULL DEFAULT 0,
    max_instance_id      BIGINT      NOT NULL DEFAULT 0,
    max_client_ip        VARCHAR(64) DEFAULT '',
    accumulation         INT         NOT NULL DEFAULT 1,
    PRIMARY KEY (id),
    UNIQUE KEY uk_app_command_collect (app_id, command, collect_time),
    KEY idx_collect_time (collect_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- -----------------------------------------------------------------------------
-- 5) app_key_analysis_stats（平台新增）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS app_key_analysis_stats (
    audit_id     BIGINT UNSIGNED NOT NULL PRIMARY KEY,
    app_id       BIGINT UNSIGNED NOT NULL,
    stats_json   MEDIUMTEXT      NOT NULL,
    create_time  TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time  TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_app_id (app_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- -----------------------------------------------------------------------------
-- 6) instance_slow_log.client_ip（保存 Redis SLOWLOG 的执行客户端地址）
-- -----------------------------------------------------------------------------
SET @exist := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'instance_slow_log'
      AND COLUMN_NAME = 'client_ip'
);
SET @sql := IF(@exist = 0,
    'ALTER TABLE instance_slow_log ADD COLUMN client_ip VARCHAR(128) DEFAULT NULL COMMENT ''执行客户端IP'' AFTER command',
    'SELECT ''skip: instance_slow_log.client_ip already exists'' AS info'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- -----------------------------------------------------------------------------
-- 7) 外部纳管 Redis 版本，解除对 system_resource/version_id 的运行时依赖
--    注：原本这里还有一段 system_config 配置补丁。SystemConfigRefreshJob 及
--        SystemConfig 实体已移除，相关取值改由 ConstUtils 中的常量提供，
--        system_config 表不再被任何代码读取，故整段删除。
-- -----------------------------------------------------------------------------
ALTER TABLE app_desc ADD COLUMN IF NOT EXISTS redis_version VARCHAR(64) DEFAULT NULL COMMENT '从Redis实例探测到的版本';

-- -----------------------------------------------------------------------------
-- 8) 平台变更操作审计表（前后端分离后由 OperationAuditInterceptor 写入）
--    应用启动时 DatabaseSchemaInitializer 也会执行同样的 CREATE TABLE IF NOT EXISTS，
--    这里保留一份便于 DBA 提前建表。
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `operation_audit`
(
    `id`          bigint(20)   NOT NULL AUTO_INCREMENT,
    `user_name`   varchar(64)  NOT NULL DEFAULT '' COMMENT '操作人',
    `module`      varchar(64)  NOT NULL DEFAULT '' COMMENT '业务域',
    `http_method` varchar(8)   NOT NULL DEFAULT '' COMMENT 'HTTP 方法',
    `request_uri` varchar(512) NOT NULL DEFAULT '' COMMENT '请求路径',
    `handler`     varchar(255)          DEFAULT NULL COMMENT 'Controller#方法',
    `app_id`      bigint(20)            DEFAULT NULL COMMENT '涉及的应用id',
    `instance_id` bigint(20)            DEFAULT NULL COMMENT '涉及的实例id',
    `params`      text COMMENT '请求参数(已脱敏)',
    `client_ip`   varchar(64)  NOT NULL DEFAULT '' COMMENT '客户端IP',
    `status_code` int(11)      NOT NULL DEFAULT '0' COMMENT 'HTTP响应码',
    `success`     tinyint(4)   NOT NULL DEFAULT '1' COMMENT '是否成功',
    `error_msg`   varchar(1024)         DEFAULT NULL COMMENT '失败原因',
    `cost_ms`     bigint(20)   NOT NULL DEFAULT '0' COMMENT '耗时(ms)',
    `create_time` datetime     NOT NULL COMMENT '操作时间',
    PRIMARY KEY (`id`),
    KEY           `idx_create_time` (`create_time`),
    KEY           `idx_user_name` (`user_name`),
    KEY           `idx_module` (`module`),
    KEY           `idx_app_id` (`app_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='平台变更操作审计';

-- -----------------------------------------------------------------------------
-- 9) instance_statistics 增加实例运行时长列
--    集群列表的「运行时长」取各在线实例中的最大值，此前该值仅存在于采集时的 info
--    明细中、列表聚合的 SQL 并不查询，导致永远显示 0。
-- -----------------------------------------------------------------------------
SET @exist := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'instance_statistics'
      AND COLUMN_NAME = 'uptime_in_seconds'
);
SET @sql := IF(@exist = 0,
    'ALTER TABLE instance_statistics ADD COLUMN uptime_in_seconds BIGINT NOT NULL DEFAULT 0 COMMENT ''实例已运行秒数''',
    'SELECT ''skip: instance_statistics.uptime_in_seconds already exists'' AS info'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- -----------------------------------------------------------------------------
-- 10) 多维度风险评估相关表
--     应用启动时 DatabaseSchemaInitializer 会执行 classpath:sql/risk-assess-schema.sql
--     建同样的表，这里保留一份便于 DBA 提前建表与评审。
--     完整 DDL 见 cachecloud-web/src/main/resources/sql/risk-assess-schema.sql
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `instance_risk_metric_minute` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `collect_time` bigint(20) NOT NULL COMMENT 'yyyyMMddHHmm',
  `app_id` bigint(20) NOT NULL,
  `instance_id` bigint(20) NOT NULL,
  `ip` varchar(64) NOT NULL DEFAULT '',
  `port` int(11) NOT NULL DEFAULT '0',
  `role` tinyint(4) NOT NULL DEFAULT '0' COMMENT '1 master 2 slave',
  `used_memory` bigint(20) NOT NULL DEFAULT '0',
  `max_memory` bigint(20) NOT NULL DEFAULT '0',
  `total_system_memory` bigint(20) NOT NULL DEFAULT '0' COMMENT '机器总内存，maxmemory 未设置时作使用率分母',
  `mem_frag_ratio` double NOT NULL DEFAULT '0',
  `keys_count` bigint(20) NOT NULL DEFAULT '0',
  `expires_count` bigint(20) NOT NULL DEFAULT '0',
  `avg_ttl` bigint(20) NOT NULL DEFAULT '0',
  `connected_clients` int(11) NOT NULL DEFAULT '0',
  `rejected_connections` bigint(20) NOT NULL DEFAULT '0' COMMENT '累计值',
  `keyspace_hits` bigint(20) NOT NULL DEFAULT '0',
  `keyspace_misses` bigint(20) NOT NULL DEFAULT '0',
  `expired_keys` bigint(20) NOT NULL DEFAULT '0',
  `evicted_keys` bigint(20) NOT NULL DEFAULT '0',
  `latest_fork_usec` bigint(20) NOT NULL DEFAULT '0',
  `rdb_last_bgsave_time_sec` int(11) NOT NULL DEFAULT '-1',
  `aof_delayed_fsync` bigint(20) NOT NULL DEFAULT '0',
  `instantaneous_ops` bigint(20) NOT NULL DEFAULT '0',
  `net_input_bytes` bigint(20) NOT NULL DEFAULT '0',
  `net_output_bytes` bigint(20) NOT NULL DEFAULT '0',
  `used_cpu_sys` double NOT NULL DEFAULT '0',
  `used_cpu_user` double NOT NULL DEFAULT '0',
  `cluster_my_epoch` bigint(20) NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_inst_time` (`instance_id`,`collect_time`),
  KEY `idx_app_time` (`app_id`,`collect_time`),
  KEY `idx_time` (`collect_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='风险评估-实例分钟指标';

CREATE TABLE IF NOT EXISTS `instance_command_latency_minute` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `collect_time` bigint(20) NOT NULL COMMENT 'yyyyMMddHHmm，5分钟采样',
  `app_id` bigint(20) NOT NULL,
  `instance_id` bigint(20) NOT NULL,
  `command` varchar(64) NOT NULL,
  `calls` bigint(20) NOT NULL DEFAULT '0' COMMENT '累计调用次数',
  `usec` bigint(20) NOT NULL DEFAULT '0' COMMENT '累计耗时(微秒)',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_inst_cmd_time` (`instance_id`,`command`,`collect_time`),
  KEY `idx_app_time` (`app_id`,`collect_time`),
  KEY `idx_time` (`collect_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='风险评估-命令耗时分钟样本(留24h)';

CREATE TABLE IF NOT EXISTS `instance_command_latency_hour` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `collect_time` bigint(20) NOT NULL COMMENT 'yyyyMMddHH',
  `app_id` bigint(20) NOT NULL,
  `instance_id` bigint(20) NOT NULL,
  `command` varchar(64) NOT NULL,
  `calls` bigint(20) NOT NULL DEFAULT '0' COMMENT '该小时调用次数',
  `usec` bigint(20) NOT NULL DEFAULT '0' COMMENT '该小时总耗时(微秒)',
  `max_avg_usec` double NOT NULL DEFAULT '0' COMMENT '该小时内单采样点最大平均耗时',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_inst_cmd_time` (`instance_id`,`command`,`collect_time`),
  KEY `idx_app_time` (`app_id`,`collect_time`),
  KEY `idx_time` (`collect_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='风险评估-命令耗时小时归档';

CREATE TABLE IF NOT EXISTS `risk_assess_rule` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `dimension` varchar(64) NOT NULL,
  `sub_item` varchar(64) DEFAULT NULL,
  `level` varchar(16) NOT NULL COMMENT 'ATTENTION/RISK/SEVERE',
  `operator` varchar(8) NOT NULL DEFAULT 'GT',
  `threshold` double DEFAULT NULL,
  `threshold_text` varchar(64) DEFAULT NULL,
  `min_absolute` double DEFAULT NULL COMMENT '低于此绝对值直接判正常',
  `enabled` tinyint(4) NOT NULL DEFAULT '1',
  `description` varchar(255) DEFAULT NULL,
  `suggestion` varchar(512) DEFAULT NULL,
  `customized` tinyint(4) NOT NULL DEFAULT '0' COMMENT '是否被人工改过',
  `update_time` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_dim_sub_level` (`dimension`,`sub_item`,`level`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='风险评估-阈值规则';

CREATE TABLE IF NOT EXISTS `risk_assess_report` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `app_id` bigint(20) NOT NULL,
  `app_name` varchar(128) NOT NULL DEFAULT '',
  `window_hours` int(11) NOT NULL DEFAULT '168',
  `window_start` datetime DEFAULT NULL,
  `window_end` datetime DEFAULT NULL,
  `level` varchar(24) NOT NULL DEFAULT 'NORMAL',
  `score` int(11) NOT NULL DEFAULT '100',
  `evaluated_dimensions` int(11) NOT NULL DEFAULT '0',
  `total_dimensions` int(11) NOT NULL DEFAULT '0',
  `instance_count` int(11) NOT NULL DEFAULT '0',
  `collected_instance_count` int(11) NOT NULL DEFAULT '0',
  `operator` varchar(64) DEFAULT NULL,
  `cost_ms` bigint(20) NOT NULL DEFAULT '0',
  `create_time` datetime NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_app_time` (`app_id`,`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='风险评估-报告';

CREATE TABLE IF NOT EXISTS `risk_assess_dimension` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `report_id` bigint(20) NOT NULL,
  `app_id` bigint(20) NOT NULL,
  `dimension` varchar(64) NOT NULL,
  `dimension_name` varchar(64) NOT NULL DEFAULT '',
  `level` varchar(24) NOT NULL DEFAULT 'NORMAL',
  `actual_value` double DEFAULT NULL,
  `threshold` double DEFAULT NULL,
  `instance_id` bigint(20) DEFAULT NULL,
  `instance_host_port` varchar(64) DEFAULT NULL,
  `sample_count` int(11) DEFAULT NULL,
  `sample_window` varchar(64) DEFAULT NULL,
  `summary` varchar(512) DEFAULT NULL,
  `suggestion` varchar(512) DEFAULT NULL,
  `evidence` text,
  PRIMARY KEY (`id`),
  KEY `idx_report` (`report_id`),
  KEY `idx_app_dim` (`app_id`,`dimension`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='风险评估-维度明细';

-- 若 instance_risk_metric_minute 在加入 total_system_memory 列之前已创建，补加该列
SET @exist := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'instance_risk_metric_minute'
      AND COLUMN_NAME = 'total_system_memory'
);
SET @sql := IF(@exist = 0,
    'ALTER TABLE instance_risk_metric_minute ADD COLUMN total_system_memory BIGINT NOT NULL DEFAULT 0 COMMENT ''机器总内存'' AFTER max_memory',
    'SELECT ''skip: instance_risk_metric_minute.total_system_memory already exists'' AS info'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- -----------------------------------------------------------------------------
-- 11) 下线冗余定时任务后的 QRTZ 残留清理
--     被移除的任务：
--       cleanupDayAppClientStatGroup    客户端上报数据清理（上报入口已下线，所清理的表不再有新数据）
--       gatherAppClientStatisticsGroup  客户端上报统计聚合（同上）
--       reviseAppClientStatisGatherGroup 客户端上报统计补偿（同上）
--       maintainBrevitySchedulerGroup   短频调度资源维护（消费它的 dispatcherTasks 已无调用方）
--     另有 errorLogMinuteStatJob 挂在 RAM JobStore 上，无 DB 残留，无需清理。
-- -----------------------------------------------------------------------------
-- 清理已下线的调度任务在 QRTZ 表中的残留。
-- Quartz 用 JDBC JobStore 持久化，从 spring-quartz.xml 移除 bean 不会删除已有行；
-- 若不清理，重启时 Quartz 会因加载不到 jobClass 而报错。
DELETE FROM qrtz_cron_triggers WHERE trigger_group IN
  ('cleanupDayAppClientStatGroup','gatherAppClientStatisticsGroup','reviseAppClientStatisGatherGroup','maintainBrevitySchedulerGroup');
DELETE FROM qrtz_simple_triggers WHERE trigger_group IN
  ('cleanupDayAppClientStatGroup','gatherAppClientStatisticsGroup','reviseAppClientStatisGatherGroup','maintainBrevitySchedulerGroup');
DELETE FROM qrtz_simprop_triggers WHERE trigger_group IN
  ('cleanupDayAppClientStatGroup','gatherAppClientStatisticsGroup','reviseAppClientStatisGatherGroup','maintainBrevitySchedulerGroup');
DELETE FROM qrtz_blob_triggers WHERE trigger_group IN
  ('cleanupDayAppClientStatGroup','gatherAppClientStatisticsGroup','reviseAppClientStatisGatherGroup','maintainBrevitySchedulerGroup');
DELETE FROM qrtz_fired_triggers WHERE trigger_group IN
  ('cleanupDayAppClientStatGroup','gatherAppClientStatisticsGroup','reviseAppClientStatisGatherGroup','maintainBrevitySchedulerGroup');
DELETE FROM qrtz_paused_trigger_grps WHERE trigger_group IN
  ('cleanupDayAppClientStatGroup','gatherAppClientStatisticsGroup','reviseAppClientStatisGatherGroup','maintainBrevitySchedulerGroup');
DELETE FROM qrtz_triggers WHERE trigger_group IN
  ('cleanupDayAppClientStatGroup','gatherAppClientStatisticsGroup','reviseAppClientStatisGatherGroup','maintainBrevitySchedulerGroup');
DELETE FROM qrtz_job_details WHERE job_group IN
  ('cleanupDayAppClientStatGroup','gatherAppClientStatisticsGroup','reviseAppClientStatisGatherGroup','maintainBrevitySchedulerGroup');

-- -----------------------------------------------------------------------------
-- 12) 触发器改用中文名后，清理旧英文名的 QRTZ 残留
--     Spring 启动只会新增/覆盖 XML 中声明的触发器，不会删除已不存在的旧行；
--     若不清理，新旧触发器并存，同一任务每周期会被触发两次。
--     qrtz_* 表为 utf8 字符集，可正常存储中文触发器名。
-- -----------------------------------------------------------------------------
DELETE FROM qrtz_cron_triggers WHERE trigger_name IN
 ('hostInspectorTrigger','appInspectorTrigger','taskExecuteTrigger','instanceAlertValueTrigger',
  'externalRedisStatsCollectTrigger','instanceStateTrigger','cleanupMinuteDimensionalityTrigger',
  'cleanupDayDimensionalityTrigger','instanceTopologySyncTrigger','riskCommandLatencyArchiveTrigger',
  'appDailyTrigger','expAppsDailyTrigger');
DELETE FROM qrtz_simple_triggers WHERE trigger_name IN
 ('hostInspectorTrigger','appInspectorTrigger','taskExecuteTrigger','instanceAlertValueTrigger',
  'externalRedisStatsCollectTrigger','instanceStateTrigger','cleanupMinuteDimensionalityTrigger',
  'cleanupDayDimensionalityTrigger','instanceTopologySyncTrigger','riskCommandLatencyArchiveTrigger',
  'appDailyTrigger','expAppsDailyTrigger');
DELETE FROM qrtz_simprop_triggers WHERE trigger_name IN
 ('hostInspectorTrigger','appInspectorTrigger','taskExecuteTrigger','instanceAlertValueTrigger',
  'externalRedisStatsCollectTrigger','instanceStateTrigger','cleanupMinuteDimensionalityTrigger',
  'cleanupDayDimensionalityTrigger','instanceTopologySyncTrigger','riskCommandLatencyArchiveTrigger',
  'appDailyTrigger','expAppsDailyTrigger');
DELETE FROM qrtz_blob_triggers WHERE trigger_name IN
 ('hostInspectorTrigger','appInspectorTrigger','taskExecuteTrigger','instanceAlertValueTrigger',
  'externalRedisStatsCollectTrigger','instanceStateTrigger','cleanupMinuteDimensionalityTrigger',
  'cleanupDayDimensionalityTrigger','instanceTopologySyncTrigger','riskCommandLatencyArchiveTrigger',
  'appDailyTrigger','expAppsDailyTrigger');
DELETE FROM qrtz_fired_triggers WHERE trigger_name IN
 ('hostInspectorTrigger','appInspectorTrigger','taskExecuteTrigger','instanceAlertValueTrigger',
  'externalRedisStatsCollectTrigger','instanceStateTrigger','cleanupMinuteDimensionalityTrigger',
  'cleanupDayDimensionalityTrigger','instanceTopologySyncTrigger','riskCommandLatencyArchiveTrigger',
  'appDailyTrigger','expAppsDailyTrigger');
DELETE FROM qrtz_triggers WHERE trigger_name IN
 ('hostInspectorTrigger','appInspectorTrigger','taskExecuteTrigger','instanceAlertValueTrigger',
  'externalRedisStatsCollectTrigger','instanceStateTrigger','cleanupMinuteDimensionalityTrigger',
  'cleanupDayDimensionalityTrigger','instanceTopologySyncTrigger','riskCommandLatencyArchiveTrigger',
  'appDailyTrigger','expAppsDailyTrigger');

-- -----------------------------------------------------------------------------
-- 13) 登录密码升级与关键父子关系约束
-- -----------------------------------------------------------------------------
ALTER TABLE app_user MODIFY password VARCHAR(255) DEFAULT NULL
  COMMENT 'BCrypt密码哈希；历史MD5在登录成功后自动升级';

-- 加外键前仅清理确实已经失去父记录的关系行；风险报告自身是历史快照，不随应用删除。
DELETE child FROM app_to_user child
LEFT JOIN app_desc parent ON child.app_id = parent.app_id
WHERE parent.app_id IS NULL;
DELETE child FROM external_redis child
LEFT JOIN app_desc parent ON child.app_id = parent.app_id
WHERE parent.app_id IS NULL;
DELETE child FROM risk_assess_dimension child
LEFT JOIN risk_assess_report parent ON child.report_id = parent.id
WHERE parent.id IS NULL;

ALTER TABLE risk_assess_report
  MODIFY app_id BIGINT NOT NULL COMMENT '评估时应用ID快照，应用删除后仍保留历史报告',
  MODIFY app_name VARCHAR(128) NOT NULL DEFAULT '' COMMENT '评估时应用名称快照';
ALTER TABLE risk_assess_dimension
  MODIFY app_id BIGINT NOT NULL COMMENT '评估时应用ID快照';

SET @exist := (SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'app_to_user'
    AND CONSTRAINT_NAME = 'fk_app_to_user_app' AND CONSTRAINT_TYPE = 'FOREIGN KEY');
SET @sql := IF(@exist = 0,
  'ALTER TABLE app_to_user ADD CONSTRAINT fk_app_to_user_app FOREIGN KEY (app_id) REFERENCES app_desc(app_id) ON DELETE CASCADE',
  'SELECT ''skip: fk_app_to_user_app already exists'' AS info');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exist := (SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'external_redis'
    AND CONSTRAINT_NAME = 'fk_external_redis_app' AND CONSTRAINT_TYPE = 'FOREIGN KEY');
SET @sql := IF(@exist = 0,
  'ALTER TABLE external_redis ADD CONSTRAINT fk_external_redis_app FOREIGN KEY (app_id) REFERENCES app_desc(app_id) ON DELETE CASCADE',
  'SELECT ''skip: fk_external_redis_app already exists'' AS info');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exist := (SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'risk_assess_dimension'
    AND CONSTRAINT_NAME = 'fk_risk_dimension_report' AND CONSTRAINT_TYPE = 'FOREIGN KEY');
SET @sql := IF(@exist = 0,
  'ALTER TABLE risk_assess_dimension ADD CONSTRAINT fk_risk_dimension_report FOREIGN KEY (report_id) REFERENCES risk_assess_report(id) ON DELETE CASCADE',
  'SELECT ''skip: fk_risk_dimension_report already exists'' AS info');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
