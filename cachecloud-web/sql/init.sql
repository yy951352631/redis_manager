-- =============================================================================
-- CacheCloud 全新部署一键初始化（合并 create_database + v1.0.2 + 必跑增量 + 配置补丁）
--
-- 用法：
--   mysql -uroot -p < init.sql
--
-- 库名默认 redis_manager，请与 application*.yml 中 datasource 一致；若用其它库名请改下方两处。
-- 已有库升级请勿执行本文件，改用 README 中的增量脚本。
--
-- 本文件已包含：
--   create_database.sql
--   v1.0.2.sql（app_desc 已含 cluster_no / has_sentinel_pwd）
--   external_redis.sql
--   app_key_analysis_stats.sql
--   20230614.sql / 20230720.sql 配置补丁
-- =============================================================================

CREATE DATABASE IF NOT EXISTS `redis_manager` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;
USE `redis_manager`;

-- -----------------------------------------------------------------------------
-- 护栏：本文件含大量 DROP TABLE，只能用于空库全新部署。
-- 若目标库里已经有 app_desc 数据，下面这段会故意触发一个「表不存在」的错误，
-- mysql 客户端默认遇错即停，从而在任何 DROP 之前中止执行。
-- 已有库请改用 upgrade.sql。（加 --force 可跳过，但请确认你真的想清库。）
-- -----------------------------------------------------------------------------
SET @cc_tbl := (SELECT COUNT(*) FROM information_schema.tables
                WHERE table_schema = DATABASE() AND table_name = 'app_desc');
SET @cc_sql := IF(@cc_tbl = 0, 'SET @cc_rows := 0', 'SET @cc_rows := (SELECT COUNT(*) FROM app_desc)');
PREPARE cc_stmt FROM @cc_sql;
EXECUTE cc_stmt;
DEALLOCATE PREPARE cc_stmt;

SET @cc_guard := IF(@cc_rows > 0,
    'SELECT * FROM `ABORT__target_database_is_not_empty__run_upgrade_sql_instead`',
    'SELECT CONCAT(''guard passed: '', DATABASE(), '' 为空库，继续初始化'') AS info');
PREPARE cc_stmt FROM @cc_guard;
EXECUTE cc_stmt;
DEALLOCATE PREPARE cc_stmt;

-- MySQL dump 10.15  Distrib 10.0.16-MariaDB, for Linux (x86_64)
--
-- Host: localhost    Database: cachecloud_open
-- ------------------------------------------------------
-- Server version	10.0.16-MariaDB-log

SET NAMES utf8;
SET
FOREIGN_KEY_CHECKS = 0;

--
-- Table structure for table `app_audit`
--

DROP TABLE IF EXISTS `app_audit`;
CREATE TABLE `app_audit`
(
    `id`            bigint(20) unsigned NOT NULL AUTO_INCREMENT,
    `app_id`        bigint(20) NOT NULL COMMENT '应用id',
    `user_id`       bigint(20) NOT NULL COMMENT '申请人的id',
    `user_name`     varchar(64)  NOT NULL COMMENT '用户名',
    `type`          tinyint(4) NOT NULL COMMENT '申请类型:0:申请应用,1:应用扩容,2:修改配置',
    `param1`        varchar(600)          DEFAULT NULL COMMENT '预留参数1',
    `param2`        varchar(600)          DEFAULT NULL COMMENT '预留参数2',
    `param3`        varchar(600)          DEFAULT NULL COMMENT '预留参数3',
    `info`          varchar(360) NOT NULL COMMENT '申请描述',
    `status`        tinyint(4) NOT NULL DEFAULT '0' COMMENT '0:等待审批; 1:审批通过; -1:驳回',
    `create_time`   timestamp    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `modify_time`   timestamp    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `refuse_reason` varchar(360)          DEFAULT NULL COMMENT '驳回理由',
    `task_id`       bigint(20) NOT NULL DEFAULT '0' COMMENT '任务id',
    `operate_id`    bigint(20) DEFAULT NULL COMMENT '工单处理人',
    PRIMARY KEY (`id`),
    KEY             `idx_appid` (`app_id`),
    KEY             `idx_create_time` (`create_time`),
    KEY             `idx_status_create_time` (`status`,`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COMMENT='应用审核表' /* `compression`='tokudb_zlib' */;

--
-- Table structure for table `app_audit_log`
--

DROP TABLE IF EXISTS `app_audit_log`;
CREATE TABLE `app_audit_log`
(
    `id`           bigint(20) NOT NULL AUTO_INCREMENT,
    `app_id`       bigint(20) NOT NULL COMMENT '应用id',
    `user_id`      bigint(20) NOT NULL COMMENT '审批操作人id',
    `info`         longtext NOT NULL COMMENT 'app审批的详细信息',
    `type`         tinyint(4) NOT NULL,
    `create_time`  datetime NOT NULL,
    `app_audit_id` bigint(20) NOT NULL COMMENT '审批id',
    PRIMARY KEY (`id`),
    KEY            `idx_audit_appid` (`app_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COMMENT='app审核日志表' /* `compression`='tokudb_zlib' */;

--
-- Table structure for table `app_client_latency_command`
--

DROP TABLE IF EXISTS `app_client_latency_command`;
CREATE TABLE `app_client_latency_command`
(
    `id`          bigint(20) unsigned NOT NULL AUTO_INCREMENT,
    `command`     varchar(255) NOT NULL COMMENT '命令明文',
    `size`        bigint(20) DEFAULT NULL COMMENT '参数长度',
    `args`        varchar(255)          DEFAULT NULL COMMENT '裁剪后参数明文',
    `create_time` timestamp    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `invoke_time` bigint(20) DEFAULT NULL COMMENT '命令调用时间戳',
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COMMENT='客户端异常命令调用详情';

--
-- Table structure for table `app_client_statistic_gather`
--

DROP TABLE IF EXISTS `app_client_statistic_gather`;
CREATE TABLE `app_client_statistic_gather`
(
    `id`                   bigint(20) unsigned NOT NULL AUTO_INCREMENT,
    `update_time`          timestamp   NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `create_time`          timestamp   NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `gather_time`          varchar(20) NOT NULL COMMENT '统计时间，格式yyyy-mm-dd',
    `app_id`               bigint(20) NOT NULL COMMENT '应用id',
    `cmd_count`            bigint(20) DEFAULT '0' COMMENT '命令调用次数',
    `conn_exp_count`       bigint(20) DEFAULT '0' COMMENT '连接异常次数',
    `avg_cmd_cost`         double               DEFAULT '0' COMMENT '命令调用平均耗时，单位毫秒',
    `avg_cmd_exp_cost`     double               DEFAULT '0' COMMENT '命令超时平均耗时，单位毫秒',
    `avg_conn_exp_cost`    double               DEFAULT '0' COMMENT '连接异常平均耗时，单位毫秒',
    `cmd_exp_count`        bigint(20) DEFAULT '0' COMMENT '命令超时次数',
    `instance_count`       int(11) DEFAULT NULL COMMENT '应用实例数',
    `avg_mem_frag_ratio`   double               DEFAULT NULL COMMENT '平均碎片率',
    `mem_used_ratio`       double               DEFAULT NULL COMMENT '内存使用率',
    `exception_count`      bigint(20) DEFAULT '0' COMMENT '异常数（旧，待下线）',
    `slow_log_count`       bigint(20) DEFAULT '0' COMMENT '慢查询次数',
    `latency_count`        bigint(20) DEFAULT '0' COMMENT '延迟事件次数',
    `object_size`          bigint(20) DEFAULT '0' COMMENT '存储对象数',
    `used_memory`          bigint(20) DEFAULT '0' COMMENT '内存占用 byte',
    `used_memory_rss`      bigint(20) DEFAULT '0' COMMENT '物理内存占用 byte',
    `max_cpu_sys`          bigint(20) DEFAULT '0' COMMENT '进程系统态消耗(单位:秒)',
    `max_cpu_user`         bigint(20) DEFAULT '0' COMMENT '进程用户态消耗(单位:秒)',
    `connected_clients`    bigint(20) DEFAULT '0' COMMENT '应用客户端连接数',
    `topology_exam_result` tinyint(4) DEFAULT NULL COMMENT '拓扑诊断结果，0：正常，1：异常',
    PRIMARY KEY (`id`),
    UNIQUE KEY `idx_appid_gathertime` (`app_id`,`gather_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COMMENT='客户端上报数据全天统计';

--
-- Table structure for table `app_client_value_minute_stats`
--

DROP TABLE IF EXISTS `app_client_value_minute_stats`;
CREATE TABLE `app_client_value_minute_stats`
(
    `id`              bigint(20) NOT NULL AUTO_INCREMENT COMMENT 'id',
    `app_id`          bigint(20) NOT NULL COMMENT '应用appid',
    `collect_time`    bigint(20) NOT NULL COMMENT '数据收集时间yyyyMMddHHmm00',
    `update_time`     datetime    NOT NULL COMMENT '更新时间',
    `command`         varchar(20) NOT NULL COMMENT '执行命令',
    `distribute_type` tinyint(4) NOT NULL COMMENT '值分布',
    `count`           int(11) NOT NULL COMMENT '命令执行次数',
    PRIMARY KEY (`id`),
    UNIQUE KEY `app_collect_command_dis` (`app_id`,`collect_time`,`command`,`distribute_type`),
    KEY               `idx_collect_time` (`collect_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COMMENT='客户端每分钟值分布上报数据统计';

--
-- Table structure for table `app_daily`
--

DROP TABLE IF EXISTS `app_daily`;
CREATE TABLE `app_daily`
(
    `id`                         bigint(20) NOT NULL AUTO_INCREMENT COMMENT '自增id',
    `app_id`                     bigint(20) NOT NULL COMMENT '应用id',
    `date`                       date                          NOT NULL COMMENT '日期',
    `create_time`                datetime                      NOT NULL,
    `slow_log_count`             bigint(20) NOT NULL COMMENT '慢查询个数',
    `client_exception_count`     bigint(20) NOT NULL COMMENT '客户端异常个数',
    `max_minute_client_count`    bigint(20) NOT NULL COMMENT '每分钟最大客户端连接数',
    `avg_minute_client_count`    bigint(20) NOT NULL COMMENT '每分钟平均客户端连接数',
    `max_minute_command_count`   bigint(20) NOT NULL COMMENT '每分钟最大命令数',
    `avg_minute_command_count`   bigint(20) NOT NULL COMMENT '每分钟平均命令数',
    `avg_hit_ratio`              double                        NOT NULL COMMENT '平均命中率',
    `min_minute_hit_ratio`       double                        NOT NULL COMMENT '每分钟最小命中率',
    `max_minute_hit_ratio`       double                        NOT NULL COMMENT '每分钟最大命中率',
    `avg_used_memory`            bigint(20) NOT NULL COMMENT '最大内存使用量',
    `max_used_memory`            bigint(20) NOT NULL COMMENT '平均内存使用量',
    `expired_keys_count`         bigint(20) NOT NULL COMMENT '过期键个数',
    `evicted_keys_count`         bigint(20) NOT NULL COMMENT '剔除键个数',
    `avg_minute_net_input_byte`  double                        NOT NULL COMMENT '每分钟平均网络input量',
    `max_minute_net_input_byte`  double                        NOT NULL COMMENT '每分钟最大网络input量',
    `avg_minute_net_output_byte` double                        NOT NULL COMMENT '每分钟平均网络output量',
    `max_minute_net_output_byte` double                        NOT NULL COMMENT '每分钟最大网络output量',
    `avg_object_size`            bigint(20) NOT NULL COMMENT '键个数平均值',
    `max_object_size`            bigint(20) NOT NULL COMMENT '键个数最大值',
    `big_key_times`              bigint(20) NOT NULL COMMENT 'bigkey次数',
    `big_key_info`               varchar(512) COLLATE utf8_bin NOT NULL COMMENT 'bigkey详情',
    `client_cmd_count`           bigint(20) NOT NULL COMMENT '累计命令调用次数',
    `client_avg_cmd_cost`        double                        NOT NULL COMMENT '平均命令调用耗时',
    `client_conn_exp_count`      bigint(20) NOT NULL COMMENT '累计连接异常事件次数',
    `client_avg_conn_exp_cost`   double                        NOT NULL COMMENT '平均连接异常事件耗时',
    `client_cmd_exp_count`       bigint(20) NOT NULL COMMENT '累计命令超时事件次数',
    `client_avg_cmd_exp_cost`    double                        NOT NULL COMMENT '平均命令超时事件耗时',
    PRIMARY KEY (`id`),
    KEY                          `idx_appid_date` (`app_id`,`date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE=utf8_bin COMMENT='app日报';

--
-- Table structure for table `app_data_migrate_status`
--

DROP TABLE IF EXISTS `app_data_migrate_status`;
CREATE TABLE `app_data_migrate_status`
(
    `id`                   bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT '自增id',
    `migrate_machine_ip`   varchar(255)  NOT NULL COMMENT '迁移工具所在机器ip',
    `migrate_machine_port` int(11) NOT NULL COMMENT '迁移工具所占port',
    `source_migrate_type`  tinyint(4) NOT NULL COMMENT '源迁移类型,0:single,1:redis cluster,2:rdb file,3:twemproxy',
    `source_servers`       varchar(2048) NOT NULL COMMENT '源实例列表',
    `target_migrate_type`  tinyint(4) NOT NULL COMMENT '目标迁移类型,0:single,1:redis cluster,2:rdb file,3:twemproxy',
    `target_servers`       varchar(2048) NOT NULL COMMENT '目标实例列表',
    `source_app_id`        bigint(20) NOT NULL DEFAULT '0' COMMENT '源应用id',
    `target_app_id`        bigint(20) NOT NULL DEFAULT '0' COMMENT '目标应用id',
    `user_id`              bigint(20) NOT NULL COMMENT '操作人',
    `status`               tinyint(4) NOT NULL COMMENT '迁移执行状态,0:开始,1:结束,2:异常',
    `start_time`           datetime      NOT NULL COMMENT '迁移开始执行时间',
    `end_time`             datetime    DEFAULT NULL COMMENT '迁移结束执行时间',
    `log_path`             varchar(255)  NOT NULL COMMENT '日志文件路径',
    `config_path`          varchar(255)  NOT NULL COMMENT '配置文件路径',
    `migrate_id`           varchar(50) DEFAULT NULL COMMENT 'migrate id',
    `migrate_tool`         tinyint(4) DEFAULT NULL COMMENT 'migrate_tool, 0:redis-shake,1:redis-migrate-tool',
    `redis_source_version` varchar(20) DEFAULT NULL,
    `redis_target_version` varchar(20) DEFAULT NULL,
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COMMENT='应用迁移记录详情';

--
-- Table structure for table `app_desc`
--

DROP TABLE IF EXISTS `app_desc`;
CREATE TABLE `app_desc`
(
    `app_id`                  bigint(20) NOT NULL AUTO_INCREMENT COMMENT '应用id',
    `cluster_no`              int(10) unsigned NOT NULL COMMENT '集群编号(对外展示)',
    `name`                    varchar(36)  NOT NULL COMMENT '应用名',
    `user_id`                 bigint(20) NOT NULL COMMENT '申请人id',
    `status`                  tinyint(4) NOT NULL COMMENT '应用状态, 0未分配，1申请未审批，2审批并发布 3:应用下线',
    `intro`                   varchar(255) NOT NULL COMMENT '应用描述',
    `create_time`             datetime     NOT NULL COMMENT '创建时间',
    `passed_time`             datetime     NOT NULL COMMENT '审批通过时间',
    `type`                    int(10) NOT NULL DEFAULT '0' COMMENT 'cache类型，1. memcached, 2. redis-cluster, 3. memcacheq, 4. 非cache-cloud ,5. redis-sentinel ,6.redis-standalone ',
    `officer`                 varchar(32)  NOT NULL COMMENT '负责人，中文',
    `ver_id`                  int(11) NOT NULL COMMENT '版本',
    `is_test`                 tinyint(4) DEFAULT '0' COMMENT '是否测试：1是0否',
    `need_persistence`        tinyint(4) DEFAULT '1' COMMENT '是否需要持久化: 1是0否',
    `need_hot_back_up`        tinyint(4) DEFAULT '1' COMMENT '是否需要热备: 1是0否',
    `has_back_store`          tinyint(4) DEFAULT '1' COMMENT '是否有后端数据源: 1是0否',
    `forecase_qps`            int(11) DEFAULT NULL COMMENT '预估qps',
    `forecast_obj_num`        int(11) DEFAULT NULL COMMENT '预估条目数',
    `client_machine_room`     varchar(36)  DEFAULT NULL COMMENT 'redis部署的机房',
    `app_key`                 varchar(255) DEFAULT NULL COMMENT '应用秘钥',
    `important_level`         tinyint(4) NOT NULL DEFAULT '2' COMMENT '应用级别，1:最重要，2:一般重要，3:一般',
    `password`                varchar(255) DEFAULT '' COMMENT 'redis密码',
    `custom_password`         varchar(255) DEFAULT NULL COMMENT '自定义密码',
    `is_access_monitor`       int(11) DEFAULT '1' COMMENT '集群级告警总闸 1:启用监控告警(默认) 0:关闭后该集群不产生任何告警',
    `app_fsync_value`         int(11) DEFAULT '1' COMMENT '应用刷盘策略 1:主从节点appdendfsync=everysec 2:主从节点 appdendfsync=no',
    `version_id`              int(11) NOT NULL DEFAULT '1' COMMENT 'Redis版本表主键id',
    `redis_version`           varchar(64) DEFAULT NULL COMMENT '从Redis实例探测到的版本',
    `master_name`             varchar(200) NULL COMMENT '针对sentinel类型的master名称',
    `has_sentinel_pwd`        varchar(45)           DEFAULT NULL COMMENT 'yes/no',
    PRIMARY KEY (`app_id`),
    UNIQUE KEY `uidx_app_cluster_no` (`cluster_no`),
    KEY                       `uidx_app_name` (`name`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COMMENT='app应用描述' /* `compression`='tokudb_zlib' */;

--
-- Table structure for table `app_hour_command_statistics`
--

DROP TABLE IF EXISTS `app_hour_command_statistics`;
CREATE TABLE `app_hour_command_statistics`
(
    `id`            bigint(20) NOT NULL AUTO_INCREMENT COMMENT 'id',
    `app_id`        bigint(20) NOT NULL COMMENT '应用id',
    `collect_time`  bigint(20) NOT NULL COMMENT '统计时间:格式yyyyMMddHH',
    `command_name`  varchar(60) NOT NULL COMMENT '命令名称',
    `command_count` bigint(20) NOT NULL COMMENT '命令执行次数',
    `create_time`   timestamp   NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `modify_time`   timestamp   NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '修改时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `app_id` (`app_id`,`command_name`,`collect_time`),
    KEY             `idx_create_time` (`create_time`),
    KEY             `idx_modify_time` (`modify_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COMMENT='应用的每小时命令统计' /* `compression`='tokudb_zlib' */;

--
-- Table structure for table `app_hour_statistics`
--

DROP TABLE IF EXISTS `app_hour_statistics`;
CREATE TABLE `app_hour_statistics`
(
    `id`                bigint(20) NOT NULL AUTO_INCREMENT COMMENT 'id',
    `app_id`            bigint(20) NOT NULL COMMENT '应用id',
    `collect_time`      bigint(20) NOT NULL COMMENT '收集时间:格式yyyyMMddHH',
    `hits`              bigint(20) NOT NULL COMMENT '每小时命中数量和',
    `misses`            bigint(20) NOT NULL COMMENT '每小时未命中数量和',
    `command_count`     bigint(20) DEFAULT '0' COMMENT '命令总数',
    `used_memory`       bigint(20) NOT NULL COMMENT '每小时内存占用最大值',
    `used_memory_rss`   bigint(20) NOT NULL DEFAULT '0' COMMENT '物理内存占用',
    `expired_keys`      bigint(20) NOT NULL COMMENT '每小时过期key数量和',
    `evicted_keys`      bigint(20) NOT NULL COMMENT '每小时驱逐key数量和',
    `net_input_byte`    bigint(20) DEFAULT '0' COMMENT '网络输入字节',
    `net_output_byte`   bigint(20) DEFAULT '0' COMMENT '网络输出字节',
    `connected_clients` int(10) NOT NULL COMMENT '每小时客户端连接数最大值',
    `object_size`       bigint(20) NOT NULL COMMENT '每小时存储对象数最大值',
    `cpu_sys`           bigint(20) DEFAULT '0' COMMENT '进程系统态消耗',
    `cpu_user`          bigint(20) DEFAULT '0' COMMENT '进程用户态消耗',
    `cpu_sys_children`  bigint(20) DEFAULT '0' COMMENT '子进程系统态消耗',
    `cpu_user_children` bigint(20) DEFAULT '0' COMMENT '子进程用户态消耗',
    `accumulation`      int(10) NOT NULL DEFAULT '0' COMMENT '每小时参与累加实例数最小值',
    `create_time`       timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `modify_time`       timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '每小时修改时间最大值',
    PRIMARY KEY (`id`),
    UNIQUE KEY `app_id` (`app_id`,`collect_time`),
    KEY                 `idx_create_time` (`create_time`) USING BTREE,
    KEY                 `idx_modify_time` (`modify_time`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COMMENT='应用统计数据每小时统计' /* `compression`='tokudb_zlib' */;

--
-- Table structure for table `app_minute_command_statistics`
--

DROP TABLE IF EXISTS `app_minute_command_statistics`;
CREATE TABLE `app_minute_command_statistics`
(
    `id`            bigint(20) NOT NULL AUTO_INCREMENT COMMENT 'id',
    `app_id`        bigint(20) NOT NULL COMMENT '应用id',
    `collect_time`  bigint(20) NOT NULL COMMENT '统计时间:格式yyyyMMddHHmm',
    `command_name`  varchar(60) NOT NULL COMMENT '命令名称',
    `command_count` bigint(20) NOT NULL COMMENT '命令执行次数',
    `create_time`   timestamp   NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `modify_time`   timestamp   NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '修改时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `app_id` (`app_id`,`collect_time`,`command_name`),
    KEY             `idx_create_time` (`create_time`),
    KEY             `idx_modify_time` (`modify_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COMMENT='应用的每分钟命令统计' /* `compression`='tokudb_zlib' */;

--
-- Table structure for table `app_minute_statistics`
--

DROP TABLE IF EXISTS `app_minute_statistics`;
CREATE TABLE `app_minute_statistics`
(
    `id`                bigint(20) NOT NULL AUTO_INCREMENT COMMENT 'id',
    `app_id`            bigint(20) NOT NULL COMMENT '应用id',
    `collect_time`      bigint(20) NOT NULL COMMENT '收集时间:格式yyyyMMddHHmm',
    `hits`              bigint(20) NOT NULL COMMENT '命中数量',
    `misses`            bigint(20) NOT NULL COMMENT '未命中数量',
    `command_count`     bigint(20) DEFAULT '0' COMMENT '命令总数',
    `used_memory`       bigint(20) NOT NULL COMMENT '内存占用',
    `used_memory_rss`   bigint(20) NOT NULL DEFAULT '0' COMMENT '物理内存占用',
    `expired_keys`      bigint(20) NOT NULL COMMENT '过期key数量',
    `evicted_keys`      bigint(20) NOT NULL COMMENT '驱逐key数量',
    `net_input_byte`    bigint(20) DEFAULT '0' COMMENT '网络输入字节',
    `net_output_byte`   bigint(20) DEFAULT '0' COMMENT '网络输出字节',
    `connected_clients` int(10) NOT NULL COMMENT '客户端连接数',
    `object_size`       bigint(20) NOT NULL COMMENT '每分钟存储对象数最大值',
    `cpu_sys`           bigint(20) DEFAULT '0' COMMENT '进程系统态消耗',
    `cpu_user`          bigint(20) DEFAULT '0' COMMENT '进程用户态消耗',
    `cpu_sys_children`  bigint(20) DEFAULT '0' COMMENT '子进程系统态消耗',
    `cpu_user_children` bigint(20) DEFAULT '0' COMMENT '子进程用户态消耗',
    `accumulation`      int(10) NOT NULL DEFAULT '0' COMMENT '参与累加实例数',
    `create_time`       timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `modify_time`       timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '修改时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `app_id` (`app_id`,`collect_time`),
    KEY                 `idx_create_time` (`create_time`) USING BTREE,
    KEY                 `idx_modify_time` (`modify_time`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8 ROW_FORMAT=COMPACT;

--
-- Table structure for table `app_to_user`
--

DROP TABLE IF EXISTS `app_to_user`;
CREATE TABLE `app_to_user`
(
    `id`      bigint(20) NOT NULL AUTO_INCREMENT,
    `user_id` bigint(20) NOT NULL COMMENT '用户id',
    `app_id`  bigint(20) NOT NULL COMMENT '应用id',
    PRIMARY KEY (`id`),
    KEY       `app_id` (`app_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE=utf8_bin /* `compression`='tokudb_zlib' */;

--
-- Table structure for table `app_user`
--

DROP TABLE IF EXISTS `app_user`;
CREATE TABLE `app_user`
(
    `id`            bigint(20) unsigned NOT NULL AUTO_INCREMENT,
    `name`          varchar(64)  NOT NULL COMMENT '用户名',
    `ch_name`       varchar(255) NOT NULL COMMENT '中文名',
    `email`         varchar(64)  NOT NULL COMMENT '邮箱',
    `mobile`        varchar(16)  NOT NULL COMMENT '手机',
    `type`          int(4) NOT NULL DEFAULT '2' COMMENT '0管理员，1预留，2普通用户，-1无效',
    `weChat`        varchar(32)  DEFAULT NULL COMMENT '微信号',
    `isAlert`       tinyint(4) NOT NULL DEFAULT '1' COMMENT '用户是否接收报警 0:不接收 1:接收',
    `password`      varchar(64)  DEFAULT NULL COMMENT '密码',
    `register_time` datetime     DEFAULT CURRENT_TIMESTAMP COMMENT '注册时间',
    `purpose`       varchar(255) DEFAULT NULL COMMENT '使用目的',
    `company`       varchar(255) DEFAULT NULL COMMENT '公司名称',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uidx_user_name` (`name`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COMMENT='用户表' /* `compression`='tokudb_zlib' */;

-- ----------------------------
--  Records of `app_user`
-- ----------------------------
BEGIN;
-- 默认管理员：admin / admin%TGB7ygv（password 为 MD5）
INSERT INTO `app_user`
VALUES ('1', 'admin', 'admin', 'admin@xxx.com', '13500000000', '0', null, '1', 'fae3997daa86ba75a7d81c1b7d8228fd', current_timestamp(), NULL, NULL);
COMMIT;

--
-- Table structure for table `brevity_schedule_resources`
--

DROP TABLE IF EXISTS `brevity_schedule_resources`;
CREATE TABLE `brevity_schedule_resources`
(
    `id`          bigint(20) unsigned NOT NULL AUTO_INCREMENT,
    `type`        tinyint(4) NOT NULL COMMENT '类型,见:BrevityScheduleType',
    `version`     bigint(20) NOT NULL DEFAULT '0' COMMENT '时间版本',
    `host`        varchar(16) NOT NULL COMMENT '资源ip',
    `port`        int(11) NOT NULL DEFAULT '0' COMMENT '端口',
    `create_time` datetime    NOT NULL COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY           `idx_type_host_port` (`type`,`host`,`port`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COMMENT='短频任务表';

--
-- Table structure for table `diagnostic_task_record`
--

DROP TABLE IF EXISTS `diagnostic_task_record`;
CREATE TABLE `diagnostic_task_record`
(
    `id`                   bigint(20) unsigned NOT NULL AUTO_INCREMENT,
    `app_id`               bigint(20) DEFAULT NULL COMMENT '应用id',
    `type`                 int(11) DEFAULT NULL COMMENT '诊断类型：0scan 1bigkey 2idle key 3hotkey 4del key 5slot analysis 6topology exam',
    `task_id`              bigint(20) DEFAULT NULL COMMENT '任务流id',
    `audit_id`             bigint(20) DEFAULT NULL COMMENT '审批id',
    `status`               int(11) DEFAULT NULL COMMENT '诊断状态：0开始 1结束 2异常',
    `cost`                 bigint(20) DEFAULT NULL COMMENT '耗时，毫秒',
    `create_time`          timestamp NULL DEFAULT CURRENT_TIMESTAMP,
    `modify_time`          timestamp NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `redis_key`            varchar(100) DEFAULT NULL COMMENT '结果的key',
    `node`                 varchar(100) DEFAULT NULL COMMENT '实例，host:port',
    `parent_task_id`       bigint(20) DEFAULT NULL COMMENT '父任务id',
    `diagnostic_condition` varchar(100) DEFAULT NULL COMMENT '诊断条件',
    `param1`               varchar(100) DEFAULT NULL COMMENT '备用参数1',
    `param2`               varchar(100) DEFAULT NULL COMMENT '备用参数2',
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COMMENT='应用诊断记录';

--
-- Table structure for table `instance_alert_configs`
--

DROP TABLE IF EXISTS `instance_alert_configs`;
CREATE TABLE `instance_alert_configs`
(
    `id`              int(11) NOT NULL AUTO_INCREMENT COMMENT '自增id',
    `alert_config`    varchar(255) NOT NULL COMMENT '报警配置',
    `alert_value`     varchar(512) NOT NULL COMMENT '报警阀值',
    `config_info`     varchar(255) NOT NULL COMMENT '配置说明',
    `type`            tinyint(4) NOT NULL COMMENT '1:全局报警,2:实例报警',
    `instance_id`     bigint(20) NOT NULL DEFAULT '0' COMMENT '0:全局配置，其他代表实例id',
    `status`          tinyint(4) NOT NULL COMMENT '1:可用,0:不可用',
    `compare_type`    tinyint(4) NOT NULL COMMENT '比较类型：1小于,2等于,3大于,4不等于',
    `check_cycle`     tinyint(4) NOT NULL COMMENT '1:一分钟,2:五分钟,3:半小时4:一个小时,5:一天',
    `update_time`     datetime     NOT NULL COMMENT '报警配置更新时间',
    `last_check_time` datetime     NOT NULL COMMENT '上次检查时间',
    `important_level` tinyint(4) unsigned NOT NULL DEFAULT '0' COMMENT '重要程度（0：一般；1：重要；2：紧急）',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uniq_index` (`type`,`instance_id`,`alert_config`,`compare_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COMMENT='实例报警阀值配置';

-- ----------------------------
--  Records of `instance_alert_configs`
-- ----------------------------
BEGIN;
INSERT INTO `instance_alert_configs`
VALUES ('9', 'aof_current_size', '6000', 'aof当前尺寸(单位：MB)', '1', '0', '1', '3', '3', '2017-06-19 09:43:22',
        '2020-09-17 10:52:00', 0),
       ('10', 'aof_delayed_fsync', '3', '分钟aof阻塞个数', '1', '0', '1', '3', '1', '2017-06-19 10:38:19',
        '2020-09-17 11:09:00', 1),
       ('11', 'client_biggest_input_buf', '10', '输入缓冲区最大buffer大小(单位：MB)', '1', '0', '1', '3', '1',
        '2017-06-19 10:47:03', '2020-09-17 11:09:00', 1),
       ('12', 'client_longest_output_list', '50000', '输出缓冲区最大队列长度', '1', '0', '1', '3', '1', '2017-06-19 10:55:45',
        '2020-09-17 11:09:00', 1),
       ('13', 'instantaneous_ops_per_sec', '60000', '实时ops', '1', '0', '1', '3', '1', '2017-06-19 11:02:38',
        '2020-09-17 11:09:00', 1),
       ('14', 'latest_fork_usec', '400000', '上次fork所用时间(单位：微秒)', '1', '0', '1', '3', '5', '2017-06-19 11:21:35',
        '2020-09-16 16:51:00', 1),
       ('15', 'mem_fragmentation_ratio', '1.5', '内存碎片率(检测大于500MB)', '1', '0', '1', '3', '5', '2017-06-19 12:49:16',
        '2020-09-16 16:51:00', 0),
       ('16', 'rdb_last_bgsave_status', 'ok', '上一次bgsave状态', '1', '0', '1', '4', '4', '2017-06-19 14:15:21',
        '2020-09-17 10:19:00', 0),
       ('17', 'total_net_output_bytes', '5000', '分钟网络输出流量(单位：MB)', '1', '0', '1', '3', '1', '2017-06-19 16:39:44',
        '2020-09-17 11:09:00', 0),
       ('19', 'total_net_input_bytes', '1200', '分钟网络输入流量(单位：MB)', '1', '0', '1', '3', '1', '2017-06-19 16:45:44',
        '2020-09-17 11:09:00', 0),
       ('20', 'sync_partial_err', '0', '分钟部分复制失败次数', '1', '0', '1', '3', '1', '2017-06-19 18:34:41',
        '2020-09-17 11:09:00', 1),
       ('21', 'sync_partial_ok', '0', '分钟部分复制成功次数', '1', '0', '1', '3', '1', '2017-06-19 18:35:01',
        '2020-09-17 11:09:00', 1),
       ('22', 'sync_full', '0', '分钟全量复制执行次数', '1', '0', '1', '3', '1', '2017-06-19 18:35:17', '2020-09-17 11:09:00', 1),
       ('23', 'rejected_connections', '0', '分钟拒绝连接数', '1', '0', '1', '3', '1', '2017-06-19 18:35:36',
        '2020-09-17 11:09:00', 2),
       ('54', 'master_slave_offset_diff', '20000000', '主从节点偏移量差(单位：字节)', '1', '0', '1', '3', '2', '2017-06-20 18:58:56',
        '2020-09-17 11:06:00', 0),
       ('56', 'cluster_state', 'ok', '集群状态', '1', '0', '1', '4', '1', '2017-06-21 18:01:52', '2020-09-17 11:09:00', 2),
       ('57', 'cluster_slots_ok', '16384', '集群成功分配槽个数', '1', '0', '1', '4', '1', '2017-06-21 18:02:04',
        '2020-09-17 11:09:00', 2);
COMMIT;

--
-- Table structure for table `instance_big_key`
--

DROP TABLE IF EXISTS `instance_big_key`;
CREATE TABLE `instance_big_key`
(
    `id`          bigint(20) unsigned NOT NULL AUTO_INCREMENT,
    `instance_id` bigint(20) NOT NULL COMMENT '实例的id',
    `app_id`      bigint(20) NOT NULL COMMENT 'app id',
    `audit_id`    bigint(20) NOT NULL COMMENT 'audit id',
    `role`        tinyint(255) NOT NULL COMMENT '主从，1主2从，详见InstanceRoleEnum',
    `ip`          varchar(32)  NOT NULL COMMENT 'ip',
    `port`        int(11) NOT NULL COMMENT 'port',
    `big_key`     varchar(255) NOT NULL COMMENT '键',
    `type`        varchar(16)  NOT NULL COMMENT '类型:string,hash,list,set,zset',
    `length`      int(11) NOT NULL COMMENT '长度',
    `create_time` datetime     NOT NULL COMMENT '记录创建时间',
    PRIMARY KEY (`id`),
    KEY           `idx_app_audit` (`app_id`,`audit_id`),
    KEY           `idx_app_create_time` (`app_id`,`create_time`),
    KEY           `idx_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COMMENT='实例bigkey列表';

--
-- Table structure for table `instance_config`
--

DROP TABLE IF EXISTS `instance_config`;
CREATE TABLE `instance_config`
(
    `id`           int(11) NOT NULL AUTO_INCREMENT,
    `config_key`   varchar(128) NOT NULL COMMENT '配置名',
    `config_value` varchar(512) NOT NULL COMMENT '配置值',
    `info`         varchar(512) NOT NULL COMMENT '配置说明',
    `update_time`  datetime     NOT NULL COMMENT '更新时间',
    `type`         mediumint(9) NOT NULL COMMENT '类型：2.cluster节点特殊配置, 5:sentinel节点配置, 6:redis普通节点',
    `status`       tinyint(4) NOT NULL COMMENT '1有效,0无效',
    `version_id`   int(11) NOT NULL COMMENT 'Redis版本表主键id',
    `refresh`      mediumint(9) DEFAULT '0' COMMENT '是否可重置：0不可，1可重置',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uniq_configkey_type_version_id` (`config_key`,`type`,`version_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COMMENT='实例配置模板';

-- ----------------------------
--  Records of `instance_config`
-- ----------------------------
BEGIN;
INSERT INTO `instance_config`
VALUES (1, 'cluster-enabled', 'yes', '是否开启集群模式', '2016-07-05 15:08:30', 2, 1, 29, 0),
       (2, 'cluster-node-timeout', '15000', '集群节点超时时间,默认15秒', '2016-07-05 15:08:30', 2, 1, 29, 0),
       (3, 'cluster-slave-validity-factor', '10', '从节点延迟有效性判断因子,默认10秒', '2016-07-05 15:08:30', 2, 1, 29, 0),
       (4, 'cluster-migration-barrier', '1', '主从迁移至少需要的从节点数,默认1个', '2016-07-05 15:08:30', 2, 1, 29, 0),
       (5, 'cluster-config-file', 'nodes-%d.conf', '集群配置文件名称,格式:nodes-{port}.conf', '2016-07-05 15:08:30', 2, 1, 29, 0),
       (6, 'cluster-require-full-coverage', 'no', '节点部分失败期间,其他节点是否继续工作', '2016-07-05 15:08:31', 2, 1, 29, 0),
       (7, 'port', '%d', 'sentinel实例端口', '2016-07-05 15:08:31', 5, 1, 29, 0),
       (8, 'dir', '%s', '工作目录', '2016-07-05 15:08:31', 5, 1, 29, 0),
       (9, 'sentinel monitor', '%s %s %d 1', 'master名称定义和最少参与监控的sentinel数,格式:masterName ip port num',
        '2016-07-05 15:08:31', 5, 1, 29, 0),
       (10, 'sentinel down-after-milliseconds', '%s 20000', 'Sentinel判定服务器断线的毫秒数', '2016-07-05 15:08:31', 5, 1, 29, 0),
       (11, 'sentinel failover-timeout', '%s 90000', '故障迁移超时时间,默认:3分钟', '2016-07-05 15:08:31', 5, 1, 29, 0),
       (12, 'sentinel parallel-syncs', '%s 1', '在执行故障转移时,最多有多少个从服务器同时对新的主服务器进行同步,默认:1', '2016-07-05 15:08:31', 5, 1, 29,
        0),
       (13, 'daemonize', 'no', '是否守护进程', '2016-07-14 14:00:05', 6, 1, 29, 0),
       (14, 'tcp-backlog', '2048', 'TCP连接完成队列', '2016-07-05 15:08:31', 6, 1, 29, 0),
       (15, 'timeout', '0', '客户端闲置多少秒后关闭连接,默认为0,永不关闭', '2016-07-05 15:08:31', 6, 1, 29, 0),
       (16, 'tcp-keepalive', '120', '检测客户端是否健康周期,默认关闭', '2016-12-06 11:40:46', 6, 1, 29, 0),
       (17, 'loglevel', 'notice', '日志级别', '2016-07-05 15:08:31', 6, 1, 29, 0),
       (18, 'databases', '16', '可用的数据库数，默认值为16个,默认数据库为0', '2016-07-05 15:08:31', 6, 1, 29, 0),
       (19, 'dir', '%s', 'redis工作目录', '2016-07-05 15:08:31', 6, 1, 29, 0),
       (20, 'stop-writes-on-bgsave-error', 'no', 'bgsave出错了不停写', '2016-07-05 15:08:31', 6, 1, 29, 0),
       (21, 'repl-timeout', '60', 'master批量数据传输时间或者ping回复时间间隔,默认:60秒', '2016-07-05 15:08:31', 6, 1, 29, 0),
       (22, 'repl-ping-slave-period', '10', '指定slave定期ping master的周期,默认:10秒', '2016-07-05 15:08:31', 6, 1, 29, 0),
       (23, 'repl-disable-tcp-nodelay', 'yes', '是否禁用socket的NO_DELAY,默认关闭，影响主从延迟', '2016-07-05 15:08:31', 6, 1, 29, 0),
       (24, 'repl-backlog-size', '10M', '复制缓存区,默认:1mb,配置为:10Mb', '2016-07-05 15:08:31', 6, 1, 29, 0),
       (25, 'repl-backlog-ttl', '7200', 'master在没有Slave的情况下释放BACKLOG的时间多久:默认:3600,配置为:7200', '2016-07-05 15:08:31', 6,
        1, 29, 0),
       (26, 'slave-serve-stale-data', 'yes',
        '当slave服务器和master服务器失去连接后，或者当数据正在复制传输的时候，如果此参数值设置“yes”，slave服务器可以继续接受客户端的请求', '2016-07-05 15:08:31', 6, 1, 29,
        0),
       (27, 'slave-read-only', 'yes', 'slave服务器节点是否只读,cluster的slave节点默认读写都不可用,需要调用readonly开启可读模式',
        '2016-07-05 15:08:31', 6, 1, 29, 0),
       (28, 'slave-priority', '100', 'slave的优先级,影响sentinel/cluster晋升master操作,0永远不晋升', '2016-07-05 15:08:31', 6, 1, 29,
        0),
       (29, 'lua-time-limit', '5000', 'Lua脚本最长的执行时间，单位为毫秒', '2016-07-05 15:08:31', 6, 1, 29, 0),
       (30, 'slowlog-log-slower-than', '10000', '慢查询被记录的阀值,默认10毫秒', '2016-07-05 15:08:31', 6, 1, 29, 0),
       (31, 'slowlog-max-len', '1000', '最多记录慢查询的条数', '2016-07-05 15:08:31', 6, 1, 29, 0),
       (32, 'hash-max-ziplist-entries', '512', 'hash数据结构优化参数', '2016-07-05 15:08:31', 6, 1, 29, 0),
       (33, 'hash-max-ziplist-value', '64', 'hash数据结构优化参数', '2016-07-05 15:08:31', 6, 1, 29, 0),
       (34, 'list-max-ziplist-entries', '512', 'list数据结构优化参数', '2016-07-05 15:08:31', 6, 1, 29, 0),
       (35, 'list-max-ziplist-value', '64', 'list数据结构优化参数', '2016-07-05 15:08:31', 6, 1, 29, 0),
       (36, 'set-max-intset-entries', '512', 'set数据结构优化参数', '2016-07-05 15:08:31', 6, 1, 29, 0),
       (37, 'zset-max-ziplist-entries', '128', 'zset数据结构优化参数', '2016-07-05 15:08:31', 6, 1, 29, 0),
       (38, 'zset-max-ziplist-value', '64', 'zset数据结构优化参数', '2016-07-05 15:08:31', 6, 1, 29, 0),
       (39, 'activerehashing', 'yes', '是否激活重置哈希,默认:yes', '2016-07-05 15:08:31', 6, 1, 29, 0),
       (40, 'client-output-buffer-limit normal', '0 0 0', '客户端输出缓冲区限制(客户端)', '2016-07-05 15:08:31', 6, 1, 29, 0),
       (41, 'client-output-buffer-limit slave', '512mb 256mb 60', '客户端输出缓冲区限制(复制)', '2016-11-24 10:24:21', 6, 1, 29, 0),
       (42, 'client-output-buffer-limit pubsub', '32mb 8mb 60', '客户端输出缓冲区限制(发布订阅)', '2016-07-05 15:08:31', 6, 1, 29, 0),
       (43, 'hz', '10', '执行后台task数量,默认:10', '2016-07-05 15:08:31', 6, 1, 29, 0),
       (44, 'port', '%d', '端口', '2016-07-05 15:08:31', 6, 1, 29, 0),
       (45, 'maxmemory', '%dmb', '当前实例最大可用内存', '2016-07-05 15:08:31', 6, 1, 29, 0),
       (46, 'maxmemory-policy', 'noeviction', '内存不够时,淘汰策略,默认:volatile-lru', '2016-07-05 15:08:31', 6, 1, 29, 0),
       (47, 'appendonly', 'yes', '开启append only持久化模式', '2016-07-05 15:08:32', 6, 1, 29, 0),
       (48, 'appendfsync', 'everysec', '默认:aof每秒同步一次', '2016-07-05 15:08:32', 6, 1, 29, 0),
       (49, 'appendfilename', 'appendonly-%d.aof', 'aof文件名称,默认:appendonly-{port}.aof', '2016-07-05 15:08:32', 6, 1, 29,
        0),
       (50, 'dbfilename', 'dump-%d.rdb', 'RDB文件默认名称,默认dump-{port}.rdb', '2016-07-05 15:08:32', 6, 1, 29, 0),
       (51, 'aof-rewrite-incremental-fsync', 'yes', 'aof rewrite过程中,是否采取增量文件同步策略,默认:yes', '2016-07-05 15:08:32', 6, 1,
        29, 0),
       (52, 'no-appendfsync-on-rewrite', 'yes', '是否在后台aof文件rewrite期间调用fsync,默认调用,修改为yes,防止可能fsync阻塞,但可能丢失rewrite期间的数据',
        '2016-07-05 15:08:32', 6, 1, 29, 0),
       (53, 'auto-aof-rewrite-min-size', '64m', '触发rewrite的aof文件最小阀值,默认64m', '2016-07-05 15:08:32', 6, 1, 29, 0),
       (54, 'auto-aof-rewrite-percentage', '%d', 'Redis重写aof文件的比例条件,默认从100开始,统一机器下不同实例按4%递减', '2016-07-05 15:08:32', 6,
        1, 29, 0),
       (55, 'maxclients', '10000', '客户端最大连接数', '2016-07-05 15:08:32', 6, 1, 29, 0),
       (590, 'latency-monitor-threshold', '0', '延迟事件阀值，单位ms', '2020-05-26 15:49:47', 6, 1, 29, 0);
COMMIT;


--
-- Table structure for table `instance_fault`
--

DROP TABLE IF EXISTS `instance_fault`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `instance_fault`
(
    `id`          int(11) NOT NULL AUTO_INCREMENT,
    `app_id`      bigint(20) NOT NULL COMMENT '应用id',
    `inst_id`     bigint(20) NOT NULL COMMENT '实例id',
    `ip`          varchar(16) NOT NULL COMMENT 'ip地址',
    `port`        int(11) NOT NULL COMMENT '端口',
    `status`      tinyint(4) NOT NULL DEFAULT '0' COMMENT '状态:0:心跳停止,1:心跳恢复',
    `create_time` timestamp   NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `type`        mediumint(4) NOT NULL COMMENT '类型：1. memcached, 2. redis-cluster, 3. memcacheq, 4. 非cache-cloud 5. redis-sentinel 6.redis-standalone',
    `reason`      mediumtext  NOT NULL COMMENT '故障原因描述',
    PRIMARY KEY (`id`),
    KEY           `idx_ip_port` (`ip`,`port`),
    KEY           `app_id` (`app_id`),
    KEY           `inst_id` (`inst_id`)
) ENGINE=InnoDB AUTO_INCREMENT=8927 DEFAULT CHARSET=utf8 COMMENT='实例故障表' /* `compression`='tokudb_zlib' */;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `instance_host`
--

DROP TABLE IF EXISTS `instance_host`;
CREATE TABLE `instance_host`
(
    `id`       bigint(20) NOT NULL AUTO_INCREMENT,
    `ip`       varchar(16) NOT NULL COMMENT '机器ip',
    `ssh_user` varchar(32) DEFAULT NULL COMMENT 'ssh用户',
    `ssh_pwd`  varchar(32) DEFAULT NULL COMMENT 'ssh密码',
    `warn`     int(5) DEFAULT '1' COMMENT '0不报警，1报警',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uidx_host_ip` (`ip`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COMMENT='机器表' /* `compression`='tokudb_zlib' */;

--
-- Table structure for table `instance_info`
--

DROP TABLE IF EXISTS `instance_info`;
CREATE TABLE `instance_info`
(
    `id`          bigint(20) NOT NULL AUTO_INCREMENT COMMENT 'memcached instance id',
    `parent_id`   bigint(20) NOT NULL DEFAULT '0' COMMENT '对等实例的id',
    `app_id`      bigint(20) NOT NULL COMMENT '应用id，与app_desc关联',
    `host_id`     bigint(20) NOT NULL COMMENT '对应的主机id，与instance_host关联',
    `ip`          varchar(16)  NOT NULL COMMENT '实例的ip',
    `port`        int(11) NOT NULL COMMENT '实例端口',
    `status`      tinyint(4) NOT NULL COMMENT '是否启用:0:节点异常,1:正常启用,2:节点下线',
    `mem`         int(11) NOT NULL COMMENT '内存大小',
    `conn`        int(11) NOT NULL COMMENT '连接数',
    `cmd`         varchar(255) NOT NULL COMMENT '启动实例的命令/redis-sentinel的masterName',
    `type`        mediumint(11) NOT NULL COMMENT '类型：1. memcached, 2. redis-cluster, 3. memcacheq, 4. 非cache-cloud 5. redis-sentinel 6.redis-standalone',
    `update_time` datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uidx_inst_ipport` (`ip`,`port`) USING BTREE,
    KEY           `app_id` (`app_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COMMENT='实例信息' /* `compression`='tokudb_zlib' */;

--
-- Table structure for table `instance_latency_history`
--

DROP TABLE IF EXISTS `instance_latency_history`;
CREATE TABLE `instance_latency_history`
(
    `id`             bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT '自增id',
    `instance_id`    bigint(20) NOT NULL COMMENT '实例的id',
    `app_id`         bigint(20) NOT NULL COMMENT 'app id',
    `ip`             varchar(32)  NOT NULL COMMENT 'ip',
    `port`           int(11) NOT NULL COMMENT 'port',
    `event`          varchar(255) NOT NULL COMMENT '事件名称',
    `execute_date`   timestamp    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '执行时间点',
    `execution_cost` bigint(20) NOT NULL COMMENT '耗时(微妙)',
    `create_time`    timestamp    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `latencyistorykey` (`instance_id`,`event`,`execute_date`),
    KEY              `idx_app_create_time` (`app_id`,`create_time`),
    KEY              `idx_app_executedate` (`app_id`,`execute_date`),
    KEY              `idx_executedate` (`execute_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COMMENT='实例延迟事件信息表';

--
-- Table structure for table `instance_minute_stats`
--

DROP TABLE IF EXISTS `instance_minute_stats`;
CREATE TABLE `instance_minute_stats`
(
    `id`           bigint(20) NOT NULL AUTO_INCREMENT COMMENT 'id',
    `collect_time` bigint(20) NOT NULL COMMENT '收集时间:格式yyyyMMddHHmm',
    `ip`           varchar(16) NOT NULL COMMENT 'ip地址',
    `port`         int(11) NOT NULL COMMENT '端口/hostId',
    `db_type`      varchar(16) NOT NULL COMMENT '收集的数据类型',
    `json`         text        NOT NULL COMMENT '统计json数据',
    `created_time` timestamp   NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uniq_index` (`ip`,`port`,`db_type`,`collect_time`),
    KEY            `idx_collect_time` (`collect_time`),
    KEY            `idx_created_time` (`created_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COMMENT='实例分钟统计表';

--
-- Table structure for table `instance_reshard_process`
--

DROP TABLE IF EXISTS `instance_reshard_process`;
CREATE TABLE `instance_reshard_process`
(
    `id`                 int(11) NOT NULL AUTO_INCREMENT COMMENT '自增id',
    `app_id`             bigint(20) NOT NULL COMMENT '应用id',
    `audit_id`           bigint(20) NOT NULL COMMENT '审核id',
    `source_instance_id` int(11) NOT NULL COMMENT '源实例id',
    `target_instance_id` int(11) NOT NULL COMMENT '目标实例id',
    `start_slot`         int(11) NOT NULL COMMENT '开始slot',
    `end_slot`           int(11) NOT NULL COMMENT '结束slot',
    `migrating_slot`     int(11) NOT NULL COMMENT '正在迁移的slot',
    `is_pipeline`        tinyint(4) NOT NULL COMMENT '是否为pipeline,0:否,1:是',
    `finish_slot_num`    int(11) NOT NULL COMMENT '已经完成迁移的slot数量',
    `status`             tinyint(4) NOT NULL COMMENT '0:运行中 1:完成 2:出错',
    `start_time`         datetime NOT NULL COMMENT '迁移开始时间',
    `end_time`           datetime NOT NULL COMMENT '迁移结束时间',
    `create_time`        datetime NOT NULL COMMENT '创建时间',
    `update_time`        datetime NOT NULL COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY                  `idx_audit` (`audit_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COMMENT='实例Reshard进度';

--
-- Table structure for table `instance_slow_log`
--

DROP TABLE IF EXISTS `instance_slow_log`;
CREATE TABLE `instance_slow_log`
(
    `id`           bigint(20) NOT NULL AUTO_INCREMENT COMMENT '自增id',
    `instance_id`  bigint(20) NOT NULL COMMENT '实例的id',
    `app_id`       bigint(20) NOT NULL COMMENT 'app id',
    `ip`           varchar(32)  NOT NULL COMMENT 'ip',
    `port`         int(11) NOT NULL COMMENT 'port',
    `slow_log_id`  bigint(20) NOT NULL COMMENT '慢查询id',
    `cost_time`    int(11) NOT NULL COMMENT '耗时(微妙)',
    `command`      varchar(255) NOT NULL COMMENT '执行命令',
    `client_ip`    varchar(128) DEFAULT NULL COMMENT '执行客户端IP',
    `execute_time` timestamp    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '执行时间点',
    `create_time`  timestamp    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `slowlogkey` (`instance_id`,`slow_log_id`,`execute_time`),
    KEY            `idx_app_create_time` (`app_id`,`create_time`),
    KEY            `idx_app_executetime` (`app_id`,`execute_time`),
    KEY            `idx_executetime` (`execute_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COMMENT='实例慢查询列表';

--
-- Table structure for table `instance_statistics`
--

DROP TABLE IF EXISTS `instance_statistics`;
CREATE TABLE `instance_statistics`
(
    `id`                      bigint(20) NOT NULL AUTO_INCREMENT COMMENT '自增id',
    `inst_id`                 bigint(20) NOT NULL COMMENT '实例的id',
    `app_id`                  bigint(20) NOT NULL COMMENT 'app id',
    `host_id`                 bigint(20) NOT NULL COMMENT '机器的id',
    `ip`                      varchar(16) COLLATE utf8_bin NOT NULL COMMENT 'ip',
    `port`                    int(255) NOT NULL COMMENT 'port',
    `role`                    tinyint(255) NOT NULL COMMENT '主从，1主2从',
    `max_memory`              bigint(255) NOT NULL COMMENT '预分配内存，单位byte',
    `used_memory`             bigint(255) NOT NULL COMMENT '已使用内存，单位byte',
    `curr_items`              bigint(255) NOT NULL COMMENT '当前item数量',
    `curr_connections`        int(255) NOT NULL COMMENT '当前连接数',
    `misses`                  bigint(255) NOT NULL COMMENT 'miss数',
    `hits`                    bigint(255) NOT NULL COMMENT '命中数',
    `create_time`             timestamp                    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `modify_time`             timestamp                    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `mem_fragmentation_ratio` double                                DEFAULT '0' COMMENT '碎片率',
    `uptime_in_seconds` bigint(20) NOT NULL DEFAULT '0' COMMENT '实例已运行秒数',
  `aof_delayed_fsync`       int(11) DEFAULT '0' COMMENT 'aof阻塞次数',
    PRIMARY KEY (`id`),
    UNIQUE KEY `ip` (`ip`,`port`),
    KEY                       `app_id` (`app_id`),
    KEY                       `machine_id` (`host_id`),
    KEY                       `idx_inst_id` (`inst_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE=utf8_bin COMMENT='实例的最新统计信息' /* `compression`='tokudb_zlib' */;

--
-- Table structure for table `machine_info`
--

DROP TABLE IF EXISTS `machine_info`;
CREATE TABLE `machine_info`
(
    `id`              bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT '机器的id',
    `ssh_user`        varchar(20) COLLATE utf8_bin NOT NULL DEFAULT 'cachecloud' COMMENT 'ssh用户',
    `ssh_passwd`      varchar(2000) COLLATE utf8_bin NOT NULL DEFAULT 'cachecloud' COMMENT 'ssh密码',
    `ip`              varchar(16) COLLATE utf8_bin NOT NULL COMMENT 'ip',
    `room`            varchar(20) COLLATE utf8_bin NOT NULL COMMENT '所属机房',
    `mem`             int(11) unsigned NOT NULL COMMENT '内存大小，单位G',
    `cpu`             mediumint(24) unsigned NOT NULL COMMENT 'cpu数量',
    `virtual`         tinyint(8) unsigned NOT NULL DEFAULT '1' COMMENT '是否虚拟，0表示否，1表示是',
    `real_ip`         varchar(16) COLLATE utf8_bin NOT NULL COMMENT '宿主机ip',
    `service_time`    timestamp                    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上线时间',
    `fault_count`     int(11) unsigned NOT NULL DEFAULT '0' COMMENT '故障次数',
    `modify_time`     timestamp                    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
    `warn`            tinyint(255) unsigned NOT NULL DEFAULT '1' COMMENT '是否启用报警，0不启用，1启用',
    `available`       tinyint(255) NOT NULL COMMENT '表示机器是否可用，1表示可用，0表示不可用；',
    `groupId`         int(11) NOT NULL DEFAULT '0' COMMENT '机器分组，默认为0，表示原生资源，非0表示外部提供的资源(可扩展)',
    `type`            int(11) NOT NULL DEFAULT '0' COMMENT '0原生 1 其他',
    `extra_desc`      varchar(255) COLLATE utf8_bin         DEFAULT NULL COMMENT '对于机器的额外说明(例如机器安装的其他服务(web,mysql,queue等等))',
    `collect`         int(11) DEFAULT '1' COMMENT 'switch of collect server status, 1:open, 0:close',
    `version_install` varchar(512) COLLATE utf8_bin         DEFAULT NULL COMMENT '机器安装redis版本状态',
    `use_type`        tinyint(4) DEFAULT '2' COMMENT '使用类型：Redis专用机器(0)，Redis测试机器(1)，混合部署机器(2)，Redis-Sentinel机器(3)',
    `k8s_type`        tinyint(4) NOT NULL DEFAULT '0' COMMENT '是否k8s容器：0:不是 1:是',
    `rack`            varchar(128) COLLATE utf8_bin         DEFAULT '' COMMENT '机器所在机架信息',
    `is_allocating`   tinyint(4) NOT NULL DEFAULT '0' COMMENT '是否在分配中,1是0否',
    `disk`            int(10) unsigned NOT NULL DEFAULT '0' COMMENT '磁盘空间:G',
    `dis_type`        tinyint(4) DEFAULT 0 NOT NULL COMMENT '操作系统发行版本，0:centos;1:ubuntu',
    PRIMARY KEY (`id`),
    UNIQUE KEY `ip` (`ip`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE=utf8_bin COMMENT='机器信息表' /* `compression`='tokudb_zlib' */;

--
-- Table structure for table `machine_relation`
--

DROP TABLE IF EXISTS `machine_relation`;
CREATE TABLE `machine_relation`
(
    `id`          int(11) unsigned NOT NULL AUTO_INCREMENT COMMENT '主键id',
    `ip`          varchar(64) NOT NULL COMMENT '虚拟机ip',
    `real_ip`     varchar(64) NOT NULL COMMENT '宿主机ip',
    `extra_desc`  varchar(128) DEFAULT NULL COMMENT '实例描述信息',
    `status`      int(255) NOT NULL COMMENT '实例变更状态 0:offline ,1:online',
    `is_sync`     tinyint(4) NOT NULL DEFAULT '0' COMMENT '数据同步状态 0: 未同步数据  -1:同步中 1:数据已同步 -2:同步失败 ',
    `sync_time`   timestamp NULL DEFAULT NULL COMMENT '同步时间',
    `update_time` timestamp NULL DEFAULT NULL COMMENT 'pod最后更新时间',
    `taskid`      bigint(11) DEFAULT NULL COMMENT '任务id',
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

--
-- Table structure for table `machine_room`
--

DROP TABLE IF EXISTS `machine_room`;
CREATE TABLE `machine_room`
(
    `id`         int(11) unsigned NOT NULL AUTO_INCREMENT COMMENT '机房id',
    `name`       varchar(255) NOT NULL COMMENT '机房名称',
    `status`     tinyint(4) NOT NULL DEFAULT '1' COMMENT '0:无效 1:有效',
    `desc`       varchar(255)          DEFAULT NULL COMMENT '机房描述信息',
    `ip_network` varchar(32)  NOT NULL DEFAULT '' COMMENT '机房网段信息',
    `operator`   varchar(255)          DEFAULT NULL COMMENT '运营商',
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

-- ----------------------------
--  Records of `machine_room`
-- ----------------------------
BEGIN;
INSERT INTO `machine_room`
VALUES ('1', '浦口', '1', '浦口机房', '', '浦口'),
       ('2', '鼓楼', '1', '鼓楼机房', '', '鼓楼');
COMMIT;

--
-- Table structure for table `machine_statistics`
--

DROP TABLE IF EXISTS `machine_statistics`;
CREATE TABLE `machine_statistics`
(
    `id`                 bigint(20) NOT NULL AUTO_INCREMENT,
    `host_id`            bigint(20) NOT NULL COMMENT '机器id',
    `ip`                 varchar(16)  NOT NULL COMMENT '机器ip',
    `cpu_usage`          varchar(120) NOT NULL COMMENT 'cpu使用率',
    `load`               varchar(120) NOT NULL COMMENT '机器负载',
    `traffic`            varchar(120) NOT NULL COMMENT 'io网络流量',
    `memory_usage_ratio` varchar(120) NOT NULL COMMENT '内存使用率',
    `memory_free`        varchar(120) NOT NULL COMMENT '内存剩余',
    `memory_total`       varchar(120) NOT NULL COMMENT '总内存量',
    `create_time`        timestamp    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `modify_time`        timestamp    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '修改时间',
    `max_memory`         int(11) DEFAULT '0' COMMENT '机器分配内存,单位MB',
    `instance_count`     int(11) DEFAULT '0' COMMENT '机器实例数量',
    `machine_memory`     int(11) DEFAULT '0' COMMENT '机器入库总内存,单位MB',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uidx_ip` (`ip`),
    KEY                  `host_id` (`host_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COMMENT='机器状态统计信息' /* `compression`='tokudb_zlib' */;

--
-- Table structure for table `qrtz_blob_triggers`
--

DROP TABLE IF EXISTS `qrtz_blob_triggers`;
CREATE TABLE `qrtz_blob_triggers`
(
    `SCHED_NAME`    varchar(120) NOT NULL,
    `TRIGGER_NAME`  varchar(200) NOT NULL,
    `TRIGGER_GROUP` varchar(200) NOT NULL,
    `BLOB_DATA`     blob,
    PRIMARY KEY (`SCHED_NAME`, `TRIGGER_NAME`, `TRIGGER_GROUP`),
    KEY             `SCHED_NAME` (`SCHED_NAME`,`TRIGGER_NAME`,`TRIGGER_GROUP`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COMMENT='Trigger 作为 Blob 类型存储(用于 Quartz 用户用 JDBC 创建他们自己定制的 Trigger 类型' /* `compression`='tokudb_zlib' */;

--
-- Table structure for table `qrtz_calendars`
--

DROP TABLE IF EXISTS `qrtz_calendars`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `qrtz_calendars`
(
    `SCHED_NAME`    varchar(120) NOT NULL COMMENT 'scheduler名称',
    `CALENDAR_NAME` varchar(200) NOT NULL COMMENT 'calendar名称',
    `CALENDAR`      blob         NOT NULL COMMENT 'calendar信息',
    PRIMARY KEY (`SCHED_NAME`, `CALENDAR_NAME`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COMMENT='以 Blob 类型存储 Quartz 的 Calendar 信息' /* `compression`='tokudb_zlib' */;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `qrtz_cron_triggers`
--

DROP TABLE IF EXISTS `qrtz_cron_triggers`;
CREATE TABLE `qrtz_cron_triggers`
(
    `SCHED_NAME`      varchar(120) NOT NULL COMMENT 'scheduler名称',
    `TRIGGER_NAME`    varchar(200) NOT NULL COMMENT 'trigger名',
    `TRIGGER_GROUP`   varchar(200) NOT NULL COMMENT 'trigger组',
    `CRON_EXPRESSION` varchar(120) NOT NULL COMMENT 'cron表达式',
    `TIME_ZONE_ID`    varchar(80) DEFAULT NULL COMMENT '时区',
    PRIMARY KEY (`SCHED_NAME`, `TRIGGER_NAME`, `TRIGGER_GROUP`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COMMENT='存储 Cron Trigger，包括 Cron 表达式和时区信息' /* `compression`='tokudb_zlib' */;

--
-- Table structure for table `qrtz_fired_triggers`
--

DROP TABLE IF EXISTS `qrtz_fired_triggers`;
CREATE TABLE `qrtz_fired_triggers`
(
    `SCHED_NAME`        varchar(120) NOT NULL,
    `ENTRY_ID`          varchar(195) NOT NULL,
    `TRIGGER_NAME`      varchar(200) NOT NULL,
    `TRIGGER_GROUP`     varchar(200) NOT NULL,
    `INSTANCE_NAME`     varchar(200) NOT NULL,
    `FIRED_TIME`        bigint(13) NOT NULL,
    `SCHED_TIME`        bigint(13) NOT NULL,
    `PRIORITY`          int(11) NOT NULL,
    `STATE`             varchar(16)  NOT NULL,
    `JOB_NAME`          varchar(200) DEFAULT NULL,
    `JOB_GROUP`         varchar(200) DEFAULT NULL,
    `IS_NONCONCURRENT`  varchar(1)   DEFAULT NULL COMMENT '是否非并行执行',
    `REQUESTS_RECOVERY` varchar(1)   DEFAULT NULL COMMENT '是否持久化',
    PRIMARY KEY (`SCHED_NAME`, `ENTRY_ID`),
    KEY                 `IDX_QRTZ_FT_TRIG_INST_NAME` (`SCHED_NAME`,`INSTANCE_NAME`),
    KEY                 `IDX_QRTZ_FT_INST_JOB_REQ_RCVRY` (`SCHED_NAME`,`INSTANCE_NAME`,`REQUESTS_RECOVERY`),
    KEY                 `IDX_QRTZ_FT_J_G` (`SCHED_NAME`,`JOB_NAME`,`JOB_GROUP`),
    KEY                 `IDX_QRTZ_FT_JG` (`SCHED_NAME`,`JOB_GROUP`),
    KEY                 `IDX_QRTZ_FT_T_G` (`SCHED_NAME`,`TRIGGER_NAME`,`TRIGGER_GROUP`),
    KEY                 `IDX_QRTZ_FT_TG` (`SCHED_NAME`,`TRIGGER_GROUP`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COMMENT='存储已触发的 Trigger相关的状态信息，以及关联 Job 的执行信息' /* `compression`='tokudb_zlib' */;

--
-- Table structure for table `qrtz_job_details`
--

DROP TABLE IF EXISTS `qrtz_job_details`;
CREATE TABLE `qrtz_job_details`
(
    `SCHED_NAME`        varchar(120) NOT NULL,
    `JOB_NAME`          varchar(200) NOT NULL,
    `JOB_GROUP`         varchar(200) NOT NULL,
    `DESCRIPTION`       varchar(250) DEFAULT NULL,
    `JOB_CLASS_NAME`    varchar(250) NOT NULL,
    `IS_DURABLE`        varchar(1)   NOT NULL COMMENT '是否持久化，0不持久化，1持久化',
    `IS_NONCONCURRENT`  varchar(1)   NOT NULL COMMENT '是否非并发，0非并发，1并发',
    `IS_UPDATE_DATA`    varchar(1)   NOT NULL,
    `REQUESTS_RECOVERY` varchar(1)   NOT NULL COMMENT '是否可恢复，0不恢复，1恢复',
    `JOB_DATA`          blob,
    PRIMARY KEY (`SCHED_NAME`, `JOB_NAME`, `JOB_GROUP`),
    KEY                 `IDX_QRTZ_J_REQ_RECOVERY` (`SCHED_NAME`,`REQUESTS_RECOVERY`),
    KEY                 `IDX_QRTZ_J_GRP` (`SCHED_NAME`,`JOB_GROUP`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COMMENT='存储每一个已配置的 Job 的详细信息' /* `compression`='tokudb_zlib' */;

--
-- Table structure for table `qrtz_locks`
--

DROP TABLE IF EXISTS `qrtz_locks`;
CREATE TABLE `qrtz_locks`
(
    `SCHED_NAME` varchar(120) NOT NULL,
    `LOCK_NAME`  varchar(40)  NOT NULL,
    PRIMARY KEY (`SCHED_NAME`, `LOCK_NAME`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COMMENT='存储程序的悲观锁的信息(假如使用了悲观锁)' /* `compression`='tokudb_zlib' */;

--
-- Table structure for table `qrtz_paused_trigger_grps`
--

DROP TABLE IF EXISTS `qrtz_paused_trigger_grps`;
CREATE TABLE `qrtz_paused_trigger_grps`
(
    `SCHED_NAME`    varchar(120) NOT NULL,
    `TRIGGER_GROUP` varchar(200) NOT NULL,
    PRIMARY KEY (`SCHED_NAME`, `TRIGGER_GROUP`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COMMENT='存储已暂停的 Trigger 组的信息' /* `compression`='tokudb_zlib' */;

--
-- Table structure for table `qrtz_scheduler_state`
--

DROP TABLE IF EXISTS `qrtz_scheduler_state`;
CREATE TABLE `qrtz_scheduler_state`
(
    `SCHED_NAME`        varchar(120) NOT NULL,
    `INSTANCE_NAME`     varchar(200) NOT NULL COMMENT '执行quartz实例的主机名',
    `LAST_CHECKIN_TIME` bigint(13) NOT NULL COMMENT '实例将状态报告给集群中的其它实例的上一次时间',
    `CHECKIN_INTERVAL`  bigint(13) NOT NULL COMMENT '实例间状态报告的时间频率',
    PRIMARY KEY (`SCHED_NAME`, `INSTANCE_NAME`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COMMENT='存储少量的有关 Scheduler 的状态信息' /* `compression`='tokudb_zlib' */;

--
-- Table structure for table `qrtz_simple_triggers`
--

DROP TABLE IF EXISTS `qrtz_simple_triggers`;
CREATE TABLE `qrtz_simple_triggers`
(
    `SCHED_NAME`      varchar(120) NOT NULL,
    `TRIGGER_NAME`    varchar(200) NOT NULL,
    `TRIGGER_GROUP`   varchar(200) NOT NULL,
    `REPEAT_COUNT`    bigint(7) NOT NULL COMMENT '重复次数',
    `REPEAT_INTERVAL` bigint(12) NOT NULL COMMENT '重复间隔',
    `TIMES_TRIGGERED` bigint(10) NOT NULL COMMENT '已出发次数',
    PRIMARY KEY (`SCHED_NAME`, `TRIGGER_NAME`, `TRIGGER_GROUP`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COMMENT='存储简单的 Trigger，包括重复次数，间隔，以及已触的次数' /* `compression`='tokudb_zlib' */;

--
-- Table structure for table `qrtz_simprop_triggers`
--

DROP TABLE IF EXISTS `qrtz_simprop_triggers`;
CREATE TABLE `qrtz_simprop_triggers`
(
    `SCHED_NAME`    varchar(120) NOT NULL,
    `TRIGGER_NAME`  varchar(200) NOT NULL,
    `TRIGGER_GROUP` varchar(200) NOT NULL,
    `STR_PROP_1`    varchar(512)   DEFAULT NULL,
    `STR_PROP_2`    varchar(512)   DEFAULT NULL,
    `STR_PROP_3`    varchar(512)   DEFAULT NULL,
    `INT_PROP_1`    int(11) DEFAULT NULL,
    `INT_PROP_2`    int(11) DEFAULT NULL,
    `LONG_PROP_1`   bigint(20) DEFAULT NULL,
    `LONG_PROP_2`   bigint(20) DEFAULT NULL,
    `DEC_PROP_1`    decimal(13, 4) DEFAULT NULL,
    `DEC_PROP_2`    decimal(13, 4) DEFAULT NULL,
    `BOOL_PROP_1`   varchar(1)     DEFAULT NULL,
    `BOOL_PROP_2`   varchar(1)     DEFAULT NULL,
    PRIMARY KEY (`SCHED_NAME`, `TRIGGER_NAME`, `TRIGGER_GROUP`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 /* `compression`='tokudb_zlib' */;

--
-- Table structure for table `qrtz_triggers`
--

DROP TABLE IF EXISTS `qrtz_triggers`;
CREATE TABLE `qrtz_triggers`
(
    `SCHED_NAME`     varchar(120) NOT NULL,
    `TRIGGER_NAME`   varchar(200) NOT NULL,
    `TRIGGER_GROUP`  varchar(200) NOT NULL,
    `JOB_NAME`       varchar(200) NOT NULL,
    `JOB_GROUP`      varchar(200) NOT NULL,
    `DESCRIPTION`    varchar(250) DEFAULT NULL,
    `NEXT_FIRE_TIME` bigint(13) DEFAULT NULL,
    `PREV_FIRE_TIME` bigint(13) DEFAULT NULL,
    `PRIORITY`       int(11) DEFAULT NULL,
    `TRIGGER_STATE`  varchar(16)  NOT NULL,
    `TRIGGER_TYPE`   varchar(8)   NOT NULL,
    `START_TIME`     bigint(13) NOT NULL,
    `END_TIME`       bigint(13) DEFAULT NULL,
    `CALENDAR_NAME`  varchar(200) DEFAULT NULL,
    `MISFIRE_INSTR`  smallint(2) DEFAULT NULL,
    `JOB_DATA`       blob,
    PRIMARY KEY (`SCHED_NAME`, `TRIGGER_NAME`, `TRIGGER_GROUP`),
    KEY              `IDX_QRTZ_T_J` (`SCHED_NAME`,`JOB_NAME`,`JOB_GROUP`),
    KEY              `IDX_QRTZ_T_JG` (`SCHED_NAME`,`JOB_GROUP`),
    KEY              `IDX_QRTZ_T_C` (`SCHED_NAME`,`CALENDAR_NAME`),
    KEY              `IDX_QRTZ_T_G` (`SCHED_NAME`,`TRIGGER_GROUP`),
    KEY              `IDX_QRTZ_T_STATE` (`SCHED_NAME`,`TRIGGER_STATE`),
    KEY              `IDX_QRTZ_T_N_STATE` (`SCHED_NAME`,`TRIGGER_NAME`,`TRIGGER_GROUP`,`TRIGGER_STATE`),
    KEY              `IDX_QRTZ_T_N_G_STATE` (`SCHED_NAME`,`TRIGGER_GROUP`,`TRIGGER_STATE`),
    KEY              `IDX_QRTZ_T_NEXT_FIRE_TIME` (`SCHED_NAME`,`NEXT_FIRE_TIME`),
    KEY              `IDX_QRTZ_T_NFT_ST` (`SCHED_NAME`,`TRIGGER_STATE`,`NEXT_FIRE_TIME`),
    KEY              `IDX_QRTZ_T_NFT_MISFIRE` (`SCHED_NAME`,`MISFIRE_INSTR`,`NEXT_FIRE_TIME`),
    KEY              `IDX_QRTZ_T_NFT_ST_MISFIRE` (`SCHED_NAME`,`MISFIRE_INSTR`,`NEXT_FIRE_TIME`,`TRIGGER_STATE`),
    KEY              `IDX_QRTZ_T_NFT_ST_MISFIRE_GRP` (`SCHED_NAME`,`MISFIRE_INSTR`,`NEXT_FIRE_TIME`,`TRIGGER_GROUP`,`TRIGGER_STATE`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COMMENT='存储已配置的 Trigger 的信息' /* `compression`='tokudb_zlib' */;


--
-- Table structure for table `server`
--

DROP TABLE IF EXISTS `server`;
CREATE TABLE `server`
(
    `ip`         varchar(16) NOT NULL COMMENT 'ip',
    `host`       varchar(255) DEFAULT NULL COMMENT 'host',
    `nmon`       varchar(255) DEFAULT NULL COMMENT 'nmon version',
    `cpus`       tinyint(4) DEFAULT NULL COMMENT 'logic cpu num',
    `cpu_model`  varchar(255) DEFAULT NULL COMMENT 'cpu 型号',
    `dist`       varchar(255) DEFAULT NULL COMMENT '发行版信息',
    `kernel`     varchar(255) DEFAULT NULL COMMENT '内核信息',
    `ulimit`     varchar(255) DEFAULT NULL COMMENT 'ulimit -n,ulimit -u',
    `updatetime` timestamp NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`ip`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

--
-- Table structure for table `server_stat`
--

DROP TABLE IF EXISTS `server_stat`;
CREATE TABLE `server_stat`
(
    `ip`         varchar(16) NOT NULL COMMENT 'ip',
    `cdate`      date        NOT NULL COMMENT '数据收集天',
    `ctime`      char(4)     NOT NULL COMMENT '数据收集小时分钟',
    `cuser`      float DEFAULT NULL COMMENT '用户态占比',
    `csys`       float DEFAULT NULL COMMENT '内核态占比',
    `cwio`       float DEFAULT NULL COMMENT 'wio占比',
    `c_ext`      text COMMENT '子cpu占比',
    `cload1`     float DEFAULT NULL COMMENT '1分钟load',
    `cload5`     float DEFAULT NULL COMMENT '5分钟load',
    `cload15`    float DEFAULT NULL COMMENT '15分钟load',
    `mtotal`     float DEFAULT NULL COMMENT '总内存,单位M',
    `mfree`      float DEFAULT NULL COMMENT '空闲内存',
    `mcache`     float DEFAULT NULL COMMENT 'cache',
    `mbuffer`    float DEFAULT NULL COMMENT 'buffer',
    `mswap`      float DEFAULT NULL COMMENT 'cache',
    `mswap_free` float DEFAULT NULL COMMENT 'cache',
    `nin`        float DEFAULT NULL COMMENT '网络入流量 单位K/s',
    `nout`       float DEFAULT NULL COMMENT '网络出流量 单位k/s',
    `nin_ext`    text COMMENT '各网卡入流量详情',
    `nout_ext`   text COMMENT '各网卡出流量详情',
    `tuse`       int(11) DEFAULT NULL COMMENT 'tcp estab连接数',
    `torphan`    int(11) DEFAULT NULL COMMENT 'tcp orphan连接数',
    `twait`      int(11) DEFAULT NULL COMMENT 'tcp time wait连接数',
    `dread`      float DEFAULT NULL COMMENT '磁盘读速率 单位K/s',
    `dwrite`     float DEFAULT NULL COMMENT '磁盘写速率 单位K/s',
    `diops`      float DEFAULT NULL COMMENT '磁盘io速率 交互次数/s',
    `dbusy`      float DEFAULT NULL COMMENT '磁盘io带宽使用百分比',
    `d_ext`      text COMMENT '磁盘各分区占比',
    `dspace`     text COMMENT '磁盘各分区空间使用率',
    PRIMARY KEY (`ip`, `cdate`, `ctime`),
    KEY          `idx_cdate` (`cdate`),
    KEY          `idx_cdate_ctime` (`cdate`,`ctime`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

-- ----------------------------
--  Table structure for `system_resource`
-- ----------------------------
DROP TABLE IF EXISTS `system_resource`;
CREATE TABLE `system_resource`
(
    `id`           int(11) unsigned NOT NULL AUTO_INCREMENT COMMENT '资源ID',
    `name`         varchar(64) NOT NULL COMMENT '资源名称',
    `intro`        varchar(255) DEFAULT NULL COMMENT '资源说明',
    `type`         tinyint(4) NOT NULL COMMENT '1:仓库地址 2:脚本 3:资源包 4:公钥/私钥 6:目录管理 7:迁移工具管理',
    `lastmodify`   datetime     DEFAULT NULL COMMENT '最后更新时间',
    `dir`          varchar(128) DEFAULT NULL COMMENT '资源路径',
    `url`          varchar(128) DEFAULT NULL COMMENT '仓库地址',
    `ispush`       tinyint(4) NOT NULL DEFAULT '0' COMMENT '0:未推送 1:已推送',
    `status`       tinyint(4) NOT NULL DEFAULT '1' COMMENT '0:无效 1:有效',
    `username`     varchar(255) DEFAULT NULL COMMENT '最后修改人',
    `task_id`      bigint(11) DEFAULT NULL COMMENT '迁移任务id',
    `compile_info` varchar(255) DEFAULT NULL COMMENT '编译信息',
    order_num      int(6) NOT NULL DEFAULT '0' COMMENT '排序',
    PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=50 DEFAULT CHARSET=utf8;

-- ----------------------------
--  Records of `system_resource`
-- ----------------------------
BEGIN;
INSERT INTO `system_resource`
VALUES (1, 'cachecloud-init.sh', '容器初始化脚本', 2, '2023-02-06 02:57:38', '/script', '', 0, 1, 'admin', NULL, NULL, 0),
       (2, '192.168.222.101', NULL, 1, '2023-02-06 07:50:47', '/data/nginx/html/resource',
        'http://192.168.222.101/resource', 0, 1, 'admin', NULL, NULL, 0),
       (4, 'cachecloud-env.sh', '宿主环境脚本', 2, '2023-02-06 02:58:01', '/script', '', 0, 1, 'admin', NULL, NULL, 0),
       (5, 'id_rsa', '私钥文件', 4, '2020-07-07 10:45:39', '/ssh', '', 0, 1, NULL, NULL, NULL, 0),
       (6, 'id_rsa.pub', '公钥文件', 4, '2020-07-07 10:45:45', '/ssh', '', 0, 1, NULL, NULL, NULL, 0),
       (21, '/script', '脚本目录管理', 6, '2023-02-06 02:49:08', '', NULL, 0, 1, 'admin', NULL, NULL, 0),
       (28, '/ssh', 'ssh目录', 6, '2023-02-06 02:49:04', NULL, NULL, 0, 1, 'admin', NULL, NULL, 0),
       (29, 'redis-6.2.10', 'redis-6.2.10 资源包 ', 3, '2023-02-06 08:31:17', '/redis',
        'http://192.168.222.101/resource/redis/redis-6.2.10.tar.gz', 0, 1, 'admin', NULL, NULL, 0),
       (32, '/redis', 'redis资源包管理', 6, '2023-02-06 02:49:06', NULL, NULL, 0, 1, 'admin', NULL, NULL, 0),
       (33, '/tool', '迁移工具，nmon和redis-cli需要手动上传', 6, '2023-02-06 02:48:55', NULL, NULL, 0, 1, 'admin', NULL, NULL, 0),
       (40, 'redis-shake-2.1.2', 'redis 2.1.2', 7, '2020-08-11 10:53:26', '/tool',
        'http://192.168.222.101/resource/tool/redis-shake-2.1.2-make.tar.gz', 0,
        1, 'admin', NULL, NULL, 0);
COMMIT;

--
-- Table structure for table `task_queue`
--

DROP TABLE IF EXISTS `task_queue`;
CREATE TABLE `task_queue`
(
    `id`              bigint(20) unsigned NOT NULL AUTO_INCREMENT,
    `app_id`          bigint(20) NOT NULL COMMENT '应用id',
    `class_name`      varchar(255) NOT NULL COMMENT '类名',
    `important_info`  varchar(255) NOT NULL DEFAULT '' COMMENT '重要信息',
    `execute_ip_port` varchar(255)          DEFAULT '' COMMENT '执行任务的ip:port',
    `param`           longtext     NOT NULL COMMENT '任务参数(json):随着任务变化',
    `init_param`      longtext     NOT NULL COMMENT '初始化任务参数(json):不变',
    `status`          tinyint(4) NOT NULL COMMENT '状态：0等待，1运行，2中断，3失败',
    `parent_task_id`  bigint(20) NOT NULL COMMENT '父任务id',
    `create_time`     datetime     NOT NULL COMMENT '创建时间',
    `update_time`     datetime     NOT NULL COMMENT '修改时间',
    `start_time`      datetime     NOT NULL COMMENT '开始时间',
    `end_time`        datetime     NOT NULL COMMENT '结束时间',
    `priority`        int(11) NOT NULL COMMENT '优先级',
    `error_code`      int(11) NOT NULL COMMENT '错误代码',
    `error_msg`       varchar(255) NOT NULL COMMENT '错误消息',
    `task_note`       varchar(255) NOT NULL COMMENT '备注',
    PRIMARY KEY (`id`),
    KEY               `idx_app_id` (`app_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COMMENT='任务表';

--
-- Table structure for table `task_step_flow`
--

DROP TABLE IF EXISTS `task_step_flow`;
CREATE TABLE `task_step_flow`
(
    `id`              bigint(20) unsigned NOT NULL AUTO_INCREMENT,
    `task_id`         bigint(20) NOT NULL COMMENT '任务id',
    `child_task_id`   bigint(20) NOT NULL DEFAULT '0' COMMENT '子任务id',
    `execute_ip_port` varchar(255) DEFAULT '' COMMENT '执行任务的ip:port',
    `class_name`      varchar(255) NOT NULL COMMENT '类名',
    `step_name`       varchar(255) NOT NULL COMMENT '步骤名',
    `order_no`        int(11) NOT NULL COMMENT '序号',
    `status`          tinyint(4) NOT NULL COMMENT '状态：0未开始、1成功、2中断、3跳过、4失败',
    `log`             text COMMENT '日志',
    `start_time`      datetime     NOT NULL COMMENT '开始时间',
    `end_time`        datetime     NOT NULL COMMENT '结束时间',
    `create_time`     datetime     NOT NULL COMMENT '创建时间',
    `update_time`     datetime     NOT NULL COMMENT '修改时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_task_class_step` (`task_id`,`class_name`,`step_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COMMENT='任务步骤流表';

--
-- Table structure for table `task_step_meta`
--

DROP TABLE IF EXISTS `task_step_meta`;
CREATE TABLE `task_step_meta`
(
    `id`          bigint(20) unsigned NOT NULL AUTO_INCREMENT,
    `class_name`  varchar(255) NOT NULL COMMENT '类名',
    `step_name`   varchar(255) NOT NULL COMMENT '步骤名',
    `step_desc`   varchar(255) NOT NULL COMMENT '步骤描述',
    `ops_device`  varchar(255) NOT NULL COMMENT '运维建议',
    `timeout`     int(11) NOT NULL COMMENT '超时时间',
    `create_time` datetime     NOT NULL COMMENT '创建时间',
    `update_time` datetime     NOT NULL COMMENT '修改时间',
    `order_no`    int(11) NOT NULL COMMENT '序号',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_class_step` (`class_name`,`step_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COMMENT='任务步骤元数据表';
/*!40101 SET character_set_client = @saved_cs_client */;

-- ----------------------------
--  Table structure for `standard_statistics`
-- ----------------------------
DROP TABLE IF EXISTS `standard_statistics`;
CREATE TABLE `standard_statistics`
(
    `id`                bigint(20) NOT NULL AUTO_INCREMENT COMMENT 'id',
    `collect_time`      bigint(20) NOT NULL COMMENT '收集时间:格式yyyyMMddHHmm',
    `ip`                varchar(16)    NOT NULL COMMENT 'ip地址',
    `port`              int(11) NOT NULL COMMENT '端口/hostId',
    `db_type`           varchar(16)    NOT NULL COMMENT '收集的数据类型',
    `info_json`         text           NOT NULL COMMENT '收集的json数据',
    `diff_json`         text           NOT NULL COMMENT '上一次收集差异的json数据',
    `created_time`      timestamp      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `cluster_info_json` varchar(20480) NOT NULL DEFAULT '' COMMENT '收集的cluster info json数据',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uniq_index` (`ip`,`port`,`db_type`,`collect_time`),
    KEY                 `idx_create_time` (`created_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 ROW_FORMAT=COMPACT;

--
-- Table structure for table `app_alert_record`
--
DROP TABLE IF EXISTS `app_alert_record`;
CREATE TABLE `app_alert_record`
(
    `id`              bigint(20) NOT NULL AUTO_INCREMENT COMMENT '自增id',
    `visible_type`    int(1) NOT NULL COMMENT '可见类型（0：均可见；1：仅管理员可见；）',
    `important_level` int(1) NOT NULL COMMENT '重要类型（0：一般；1：重要；2：紧急）',
    `create_time`     timestamp                     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `app_id`          bigint(20) DEFAULT NULL COMMENT 'app id',
    `instance_id`     bigint(20) DEFAULT NULL COMMENT '实例id',
    `ip`              varchar(16) COLLATE utf8_bin           DEFAULT NULL COMMENT '机器ip',
    `port`            int(10) DEFAULT NULL COMMENT '端口号',
    `title`           varchar(255) COLLATE utf8_bin NOT NULL COMMENT '报警标题',
    `content`         varchar(500) COLLATE utf8_bin NOT NULL COMMENT '报警内容',
    PRIMARY KEY (`id`),
    KEY               `app_id` (`app_id`),
    KEY               `ip` (`ip`),
    KEY               `idx_inst_id` (`instance_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COMMENT='报警记录表';

--
-- Table structure for table `config_restart_record`
--
DROP TABLE IF EXISTS `config_restart_record`;
CREATE TABLE `config_restart_record`
(
    `id`           bigint(20) NOT NULL AUTO_INCREMENT,
    `app_id`       bigint(20) NOT NULL COMMENT '应用id',
    `app_name`     varchar(36)   NOT NULL COMMENT '应用名称',
    `operate_type` char(1)       NOT NULL COMMENT '操作类型（0:滚动重启，1:修改配置强制重启；2：修改配置）',
    `param`        varchar(2000) NOT NULL COMMENT '初始化任务参数(json):不变',
    `status`       tinyint(4) NOT NULL COMMENT '状态：0等待，1运行，2成功，3失败，4配置修改待重启',
    `start_time`   datetime      NOT NULL COMMENT '开始时间',
    `end_time`     datetime      NOT NULL COMMENT '结束时间',
    `create_time`  datetime      NOT NULL COMMENT '创建时间',
    `update_time`  datetime      NOT NULL COMMENT '修改时间',
    `log`          longtext COMMENT '日志信息',
    `user_name`    varchar(64)   DEFAULT NULL COMMENT '操作人员姓名',
    `user_id`      bigint(20) NOT NULL COMMENT '用户id',
    `instances`    varchar(1000) DEFAULT NULL COMMENT '涉及实例id列表的json格式',
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COMMENT='重启记录表';

--
-- Table structure for table `module_info`
--
DROP TABLE IF EXISTS `module_info`;
CREATE TABLE `module_info`
(
    `id`      int(11) NOT NULL AUTO_INCREMENT,
    `name`    varchar(64)  NOT NULL,
    `git_url` varchar(255) NOT NULL DEFAULT '' COMMENT 'git resource',
    `info`    varchar(128)          DEFAULT NULL COMMENT '模块信息说明',
    `status`  tinyint(4) NOT NULL DEFAULT '1' COMMENT '0:无效 1:有效',
    PRIMARY KEY (`id`),
    UNIQUE KEY `NAMEKEY` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COMMENT='Redis模块信息表';

--
-- Table structure for table `module_version`
--
DROP TABLE IF EXISTS `module_version`;
CREATE TABLE `module_version`
(
    `id`          int(11) NOT NULL AUTO_INCREMENT,
    `module_id`   int(11) NOT NULL,
    `version_id`  int(11) NOT NULL COMMENT '关联版本号',
    `create_time` datetime     DEFAULT NULL COMMENT '创建时间',
    `so_path`     varchar(255) DEFAULT NULL COMMENT '编译后so库的地址',
    `tag`         varchar(64) NOT NULL COMMENT '模块版本号',
    `status`      int(255) NOT NULL DEFAULT '0' COMMENT '是否可用(关联so地址)：0 不可用 1：可用',
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COMMENT='Redis模块版本管理表';

--
-- Table structure for table `app_import`
--
DROP TABLE IF EXISTS `app_import`;
CREATE TABLE `app_import`
(
    `id`                 bigint(20) unsigned NOT NULL AUTO_INCREMENT,
    `app_id`             bigint(20) DEFAULT NULL COMMENT '目标应用id',
    `instance_info`      text COMMENT '源redis实例信息',
    `redis_password`     varchar(200)       DEFAULT NULL COMMENT '源redis密码',
    `status`             int(11) DEFAULT NULL COMMENT '迁移状态：PREPARE(0, "准备", "应用导入-未开始"),     START(1, "进行中...", "应用导入-开始"),     ERROR(2, "error", "应用导入-出错"),     VERSION_BUILD_START(11, "进行中...", "新建redis版本-进行中"),     VERSION_BUILD_ERROR(12, "error", "新建redis版本-出错"),     VERSION_BUILD_END(20, "成功", "新建redis版本-完成"),     APP_BUILD_INIT(21, "准备就绪", "新建redis应用-准备就绪"),     APP_BUILD_START(22, "进行中...", "新建redis应用-进行中"),     APP_BUILD_ERROR(23, "error", "新建redis应用-出错"),     APP_BUILD_END(30, "成功", "新建redis应用-完成"),     MIGRATE_INIT(31, "准备就绪", "数据迁移-准备就绪"),     MIGRATE_START(32, "进行中...", "数据迁移-进行中"),     MIGRATE_ERROR(33, "error", "数据迁移-出错"),     MIGRATE_END(3, "成功", "应用导入-成功")',
    `step`               int(11) DEFAULT NULL COMMENT '导入阶段',
    `create_time`        timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`        timestamp NULL DEFAULT CURRENT_TIMESTAMP,
    `migrate_id`         bigint(20) DEFAULT NULL COMMENT '数据迁移id',
    `mem_size`           int(11) DEFAULT NULL COMMENT '目标应用内存大小，单位G',
    `redis_version_name` varchar(20)        DEFAULT NULL COMMENT '目标应用redis版本，格式：redis-x.x.x',
    `app_build_task_id`  bigint(20) DEFAULT NULL COMMENT '目标应用部署任务id',
    `source_type`        int(11) DEFAULT NULL COMMENT '源redis类型：7:cluster, 6:sentinel, 5:standalone',
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

-- redis_module_config definition

CREATE TABLE `redis_module_config`
(
    `id`           int(11) NOT NULL AUTO_INCREMENT,
    `config_key`   varchar(128) NOT NULL COMMENT '配置名',
    `config_value` varchar(512) NOT NULL COMMENT '配置值',
    `info`         varchar(512) NOT NULL COMMENT '配置说明',
    `update_time`  datetime     NOT NULL COMMENT '更新时间',
    `type`         mediumint(9) NOT NULL COMMENT '类型：2.cluster节点特殊配置, 5:sentinel节点配置, 6:redis普通节点',
    `status`       tinyint(4) NOT NULL COMMENT '1有效,0无效',
    `version_id`   int(11) NOT NULL COMMENT 'Module version版本表主键id',
    `refresh`      tinyint(4) DEFAULT '0' COMMENT '是否可重置：0不可，1可重置',
    `module_id`    int(11) NOT NULL DEFAULT '7' COMMENT 'Module 信息表id',
    `config_type`  tinyint(4) NOT NULL DEFAULT '0' COMMENT '配置类型，0：加载和运行配置；1：加载时配置；2：运行时配置',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uniq_configkey_type_version_id` (`config_key`,`type`,`version_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE=utf8_bin COMMENT='redis模块配置表';


-- app_to_module definition

CREATE TABLE `app_to_module`
(
    `id`                bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键',
    `app_id`            bigint(20) NOT NULL COMMENT '应用id',
    `module_id`         int(11) NOT NULL COMMENT '模块info id',
    `module_version_id` int(11) NOT NULL COMMENT '模块版本id',
    PRIMARY KEY (`id`),
    UNIQUE KEY `app_to_module_un` (`app_id`,`module_id`,`module_version_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE=utf8_bin COMMENT='应用与模块关系表';

-- =============================================================================
-- 增量表（原 external_redis.sql / app_key_analysis_stats.sql）
-- =============================================================================

-- 外部纳管 Redis 登记（关联 app_desc / instance_info，不依赖 machine_info）
DROP TABLE IF EXISTS `external_redis`;
CREATE TABLE `external_redis`
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

-- Client command latency summary
-- ----------------------------
--  Table structure for table `app_client_exception_minute_stat`
--  客户端 SDK 上报的异常分钟统计。AppClientExceptionStatDao / AppStatsDao 一直在查这张表，
--  但历史脚本从未创建，导致相关查询在运行时报表不存在。
-- ----------------------------
DROP TABLE IF EXISTS `app_client_exception_minute_stat`;
CREATE TABLE `app_client_exception_minute_stat`
(
    `id`              bigint(20)   NOT NULL AUTO_INCREMENT,
    `app_id`          bigint(20)   NOT NULL COMMENT '应用id',
    `collect_time`    bigint(20)   NOT NULL COMMENT '采集时间 yyyyMMddHHmm',
    `client_ip`       varchar(64)  NOT NULL DEFAULT '' COMMENT '客户端ip',
    `report_time`     datetime              DEFAULT NULL COMMENT '客户端上报时间',
    `create_time`     datetime              DEFAULT NULL COMMENT '入库时间',
    `exception_class` varchar(255) NOT NULL DEFAULT '' COMMENT '异常类名',
    `exception_count` bigint(20)   NOT NULL DEFAULT '0' COMMENT '异常次数',
    `instance_host`   varchar(64)  NOT NULL DEFAULT '' COMMENT '实例host',
    `instance_port`   int(11)      NOT NULL DEFAULT '0' COMMENT '实例端口',
    `instance_id`     int(11)      NOT NULL DEFAULT '0' COMMENT '实例id',
    `type`            int(11)      NOT NULL DEFAULT '0' COMMENT '异常类型',
    PRIMARY KEY (`id`),
    KEY               `idx_app_collect` (`app_id`,`collect_time`),
    KEY               `idx_collect_time` (`collect_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='客户端异常分钟统计';

-- ----------------------------
--  Table structure for table `app_client_costtime_minute_stat`
--  客户端命令耗时分钟明细（_total 为其聚合表，由 DatabaseSchemaInitializer 创建）。
-- ----------------------------
DROP TABLE IF EXISTS `app_client_costtime_minute_stat`;
CREATE TABLE `app_client_costtime_minute_stat`
(
    `id`                      bigint(20)  NOT NULL AUTO_INCREMENT,
    `app_id`                  bigint(20)  NOT NULL COMMENT '应用id',
    `collect_time`            bigint(20)  NOT NULL COMMENT '采集时间 yyyyMMddHHmm',
    `client_ip`               varchar(64) NOT NULL DEFAULT '' COMMENT '客户端ip',
    `report_time`             datetime             DEFAULT NULL COMMENT '客户端上报时间',
    `create_time`             datetime             DEFAULT NULL COMMENT '入库时间',
    `command`                 varchar(64) NOT NULL DEFAULT '' COMMENT '命令',
    `median`                  int(11)     NOT NULL DEFAULT '0' COMMENT '中位数耗时',
    `mean`                    double      NOT NULL DEFAULT '0' COMMENT '平均耗时',
    `ninety_percent_max`      int(11)     NOT NULL DEFAULT '0' COMMENT 'p90 耗时',
    `ninety_nine_percent_max` int(11)     NOT NULL DEFAULT '0' COMMENT 'p99 耗时',
    `hundred_max`             int(11)     NOT NULL DEFAULT '0' COMMENT '最大耗时',
    `count`                   int(11)     NOT NULL DEFAULT '0' COMMENT '调用次数',
    `instance_host`           varchar(64) NOT NULL DEFAULT '' COMMENT '实例host',
    `instance_port`           int(11)     NOT NULL DEFAULT '0' COMMENT '实例端口',
    `instance_id`             bigint(20)  NOT NULL DEFAULT '0' COMMENT '实例id',
    PRIMARY KEY (`id`),
    KEY                       `idx_app_inst_client_time` (`app_id`,`instance_id`,`client_ip`,`collect_time`),
    KEY                       `idx_collect_time` (`collect_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='客户端命令耗时分钟统计';

-- ----------------------------
--  Table structure for table `app_client_datasize_minute_stat`
--  客户端上报各类 map 的尺寸，仅写入不查询，用于排查客户端上报量。
-- ----------------------------
DROP TABLE IF EXISTS `app_client_datasize_minute_stat`;
CREATE TABLE `app_client_datasize_minute_stat`
(
    `id`                 bigint(20)  NOT NULL AUTO_INCREMENT,
    `collect_time`       bigint(20)  NOT NULL COMMENT '采集时间 yyyyMMddHHmm',
    `client_ip`          varchar(64) NOT NULL DEFAULT '' COMMENT '客户端ip',
    `report_time`        datetime             DEFAULT NULL COMMENT '客户端上报时间',
    `create_time`        datetime             DEFAULT NULL COMMENT '入库时间',
    `cost_map_size`      int(11)     NOT NULL DEFAULT '0' COMMENT '耗时map尺寸',
    `value_map_size`     int(11)     NOT NULL DEFAULT '0' COMMENT '值map尺寸',
    `exception_map_size` int(11)     NOT NULL DEFAULT '0' COMMENT '异常map尺寸',
    `collect_map_size`   int(11)     NOT NULL DEFAULT '0' COMMENT '采集map尺寸',
    PRIMARY KEY (`id`),
    KEY                  `idx_collect_time` (`collect_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='客户端上报数据尺寸分钟统计';

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

-- 键值分析分布统计快照（解决多任务节点各自写本地 assist Redis、API 读不到结果的问题）
CREATE TABLE IF NOT EXISTS app_key_analysis_stats (
    audit_id     BIGINT UNSIGNED NOT NULL PRIMARY KEY,
    app_id       BIGINT UNSIGNED NOT NULL,
    stats_json   MEDIUMTEXT      NOT NULL,
    create_time  TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time  TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_app_id (app_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

--
-- Table structure for table `operation_audit`
--

DROP TABLE IF EXISTS `operation_audit`;
CREATE TABLE `operation_audit`
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
