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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='风险评估-命令耗时分钟样本(留7天)';

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='风险评估-命令耗时小时归档(留30天)';

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

CREATE TABLE IF NOT EXISTS `offline_analysis_record` (
  `id`          bigint(20)   NOT NULL AUTO_INCREMENT,
  `file_name`   varchar(255) NOT NULL COMMENT '上传时的原始文件名',
  `file_size`   bigint(20)   NOT NULL DEFAULT '0' COMMENT '文件字节数',
  `file_path`   varchar(512) NOT NULL DEFAULT '' COMMENT '服务端落盘路径，分析完成后删除',
  `file_type`   varchar(16)  NOT NULL DEFAULT 'RDB' COMMENT 'RDB/AOF',
  `status`      tinyint(4)   NOT NULL DEFAULT '0' COMMENT '0待分析 1分析中 2已完成 3失败',
  `progress`    int(11)      NOT NULL DEFAULT '0' COMMENT '进度百分比',
  `key_count`   bigint(20)   NOT NULL DEFAULT '0' COMMENT '解析出的 key 总数',
  `error_msg`   varchar(1024)         DEFAULT NULL COMMENT '失败原因',
  `result_json` mediumtext            COMMENT '分析结果，结构与键值分析的 KeyAnalysisStatsSnapshotDto 一致',
  `user_name`   varchar(64)  NOT NULL DEFAULT '' COMMENT '上传人',
  `create_time` datetime     NOT NULL COMMENT '上传时间',
  `update_time` datetime     NOT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_create_time` (`create_time`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='离线数据分析记录（上传 RDB 文件解析）';

CREATE TABLE IF NOT EXISTS `instance_runtime_profile` (
  `instance_id`   bigint(20)  NOT NULL COMMENT '实例id',
  `app_id`        bigint(20)  NOT NULL DEFAULT '0',
  `os_arch`       varchar(32)          DEFAULT NULL COMMENT 'X86/ARM，解析自 INFO server 的 os',
  `redis_version` varchar(32)          DEFAULT NULL COMMENT 'INFO server 的 redis_version',
  `major_version` varchar(16)          DEFAULT NULL COMMENT '大版本，如 6.2',
  `update_time`   datetime    NOT NULL,
  PRIMARY KEY (`instance_id`),
  KEY `idx_arch_version` (`os_arch`,`major_version`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='风险评估-实例运行画像，命令耗时基线按架构与大版本分组时使用';
