package com.shcj.cache.configuration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.StreamUtils;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;

@Component
public class DatabaseSchemaInitializer {

    private static final Logger logger = LoggerFactory.getLogger(DatabaseSchemaInitializer.class);

    private static final String CREATE_APP_CLIENT_MINUTE_COST_TOTAL =
            "CREATE TABLE IF NOT EXISTS app_client_costtime_minute_stat_total ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT,"
                    + "app_id BIGINT NOT NULL,"
                    + "collect_time BIGINT NOT NULL,"
                    + "create_time DATETIME NOT NULL,"
                    + "command VARCHAR(64) NOT NULL,"
                    + "mean DOUBLE NOT NULL DEFAULT 0,"
                    + "median INT NOT NULL DEFAULT 0,"
                    + "ninety_percent_max INT NOT NULL DEFAULT 0,"
                    + "ninety_nine_percent_max INT NOT NULL DEFAULT 0,"
                    + "hundred_max INT NOT NULL DEFAULT 0,"
                    + "total_cost DOUBLE NOT NULL DEFAULT 0,"
                    + "total_count BIGINT NOT NULL DEFAULT 0,"
                    + "max_instance_host VARCHAR(64) DEFAULT '',"
                    + "max_instance_port INT NOT NULL DEFAULT 0,"
                    + "max_instance_id BIGINT NOT NULL DEFAULT 0,"
                    + "max_client_ip VARCHAR(64) DEFAULT '',"
                    + "accumulation INT NOT NULL DEFAULT 1,"
                    + "PRIMARY KEY (id),"
                    + "UNIQUE KEY uk_app_command_collect (app_id, command, collect_time),"
                    + "KEY idx_collect_time (collect_time)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4";

    private static final String CREATE_BENCHMARK_TASK =
            "CREATE TABLE IF NOT EXISTS benchmark_task ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT,"
                    + "app_id BIGINT NOT NULL DEFAULT 0,"
                    + "app_name VARCHAR(128) NOT NULL DEFAULT '',"
                    + "target_desc VARCHAR(512) NOT NULL DEFAULT '' COMMENT '整集群或选中的节点',"
                    + "options_json TEXT COMMENT '参数快照，用于两次压测的可比性',"
                    + "status VARCHAR(16) NOT NULL DEFAULT '',"
                    + "total_requests BIGINT NOT NULL DEFAULT 0,"
                    + "error_count BIGINT NOT NULL DEFAULT 0,"
                    + "qps BIGINT NOT NULL DEFAULT 0,"
                    + "p50_ms DOUBLE NOT NULL DEFAULT 0,"
                    + "p95_ms DOUBLE NOT NULL DEFAULT 0,"
                    + "p99_ms DOUBLE NOT NULL DEFAULT 0,"
                    + "max_ms DOUBLE NOT NULL DEFAULT 0,"
                    + "avg_ms DOUBLE NOT NULL DEFAULT 0,"
                    + "client_cpu_percent DOUBLE NOT NULL DEFAULT 0 COMMENT '压测期间平台自身CPU',"
                    + "command_stats_json TEXT,"
                    + "ramp_json TEXT COMMENT '快捷压测各档结果',"
                    + "ramp_message VARCHAR(512) DEFAULT NULL COMMENT '快捷压测结论',"
                    + "peak_concurrency INT NOT NULL DEFAULT 0 COMMENT '峰值所在并发档',"
                    + "target_cpu_percent DOUBLE NOT NULL DEFAULT 0 COMMENT '峰值档下目标节点CPU(单核%)',"
                    + "avg_target_cpu_percent DOUBLE NOT NULL DEFAULT 0 COMMENT '全程承压节点平均CPU(单核%)',"
                    + "error_stats_json TEXT,"
                    + "user_name VARCHAR(64) NOT NULL DEFAULT '',"
                    + "error_msg VARCHAR(1024) DEFAULT NULL,"
                    + "start_time DATETIME NOT NULL,"
                    + "end_time DATETIME DEFAULT NULL,"
                    + "PRIMARY KEY (id),"
                    + "KEY idx_app_start (app_id, start_time),"
                    + "KEY idx_start_time (start_time)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='压测任务与结果'";

    private static final String CREATE_OPERATION_AUDIT =
            "CREATE TABLE IF NOT EXISTS operation_audit ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT,"
                    + "user_name VARCHAR(64) NOT NULL DEFAULT '' COMMENT '操作人',"
                    + "module VARCHAR(64) NOT NULL DEFAULT '' COMMENT '业务域',"
                    + "http_method VARCHAR(8) NOT NULL DEFAULT '',"
                    + "request_uri VARCHAR(512) NOT NULL DEFAULT '',"
                    + "handler VARCHAR(255) DEFAULT NULL COMMENT 'Controller#方法',"
                    + "app_id BIGINT DEFAULT NULL,"
                    + "instance_id BIGINT DEFAULT NULL,"
                    + "params TEXT COMMENT '请求参数(已脱敏)',"
                    + "client_ip VARCHAR(64) NOT NULL DEFAULT '',"
                    + "status_code INT NOT NULL DEFAULT 0,"
                    + "success TINYINT NOT NULL DEFAULT 1,"
                    + "error_msg VARCHAR(1024) DEFAULT NULL,"
                    + "cost_ms BIGINT NOT NULL DEFAULT 0,"
                    + "object_label VARCHAR(255) DEFAULT NULL COMMENT '操作对象，写入时固化的快照',"
                    + "create_time DATETIME NOT NULL,"
                    + "PRIMARY KEY (id),"
                    + "KEY idx_create_time (create_time),"
                    + "KEY idx_user_name (user_name),"
                    + "KEY idx_module (module),"
                    + "KEY idx_app_id (app_id)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='平台变更操作审计'";

    private static final String ADD_APP_DESC_REDIS_VERSION =
            "ALTER TABLE app_desc ADD COLUMN redis_version VARCHAR(64) DEFAULT NULL COMMENT '从Redis实例探测到的版本'";

    private static final String ADD_INSTANCE_STATS_UPTIME =
            "ALTER TABLE instance_statistics ADD COLUMN uptime_in_seconds BIGINT NOT NULL DEFAULT 0 "
                    + "COMMENT '实例已运行秒数'";

    private final JdbcTemplate jdbcTemplate;

    public DatabaseSchemaInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    public void initialize() {
        jdbcTemplate.execute(CREATE_APP_CLIENT_MINUTE_COST_TOTAL);
        jdbcTemplate.execute(CREATE_OPERATION_AUDIT);
        jdbcTemplate.execute(CREATE_BENCHMARK_TASK);
        executeScript("sql/risk-assess-schema.sql");
        ensureColumn("instance_risk_metric_minute", "total_system_memory",
                "ALTER TABLE instance_risk_metric_minute ADD COLUMN total_system_memory BIGINT NOT NULL DEFAULT 0 "
                        + "COMMENT '机器总内存' AFTER max_memory");
        ensureColumn("benchmark_task", "ramp_json",
                "ALTER TABLE benchmark_task ADD COLUMN ramp_json TEXT COMMENT '快捷压测各档结果', "
                        + "ADD COLUMN ramp_message VARCHAR(512) DEFAULT NULL COMMENT '快捷压测结论', "
                        + "ADD COLUMN peak_concurrency INT NOT NULL DEFAULT 0 COMMENT '峰值所在并发档', "
                        + "ADD COLUMN target_cpu_percent DOUBLE NOT NULL DEFAULT 0 COMMENT '峰值档下目标节点CPU'");
        ensureColumn("benchmark_task", "avg_target_cpu_percent",
                "ALTER TABLE benchmark_task ADD COLUMN avg_target_cpu_percent DOUBLE NOT NULL DEFAULT 0 "
                        + "COMMENT '全程承压节点平均CPU(单核%)'");
        ensureColumn("operation_audit", "object_label",
                "ALTER TABLE operation_audit ADD COLUMN object_label VARCHAR(255) DEFAULT NULL "
                        + "COMMENT '操作对象，写入时固化的快照' AFTER instance_id");
        ensureRedisVersionColumn();
        ensureInstanceStatsUptimeColumn();
        logger.info("runtime database schema initialized");
    }

    private void ensureRedisVersionColumn() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() "
                        + "AND table_name = 'app_desc' AND column_name = 'redis_version'",
                Integer.class);
        if (count != null && count == 0) {
            jdbcTemplate.execute(ADD_APP_DESC_REDIS_VERSION);
            logger.info("added app_desc.redis_version column");
        }
    }

    private void ensureInstanceStatsUptimeColumn() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() "
                        + "AND table_name = 'instance_statistics' AND column_name = 'uptime_in_seconds'",
                Integer.class);
        if (count != null && count == 0) {
            jdbcTemplate.execute(ADD_INSTANCE_STATS_UPTIME);
            logger.info("instance_statistics.uptime_in_seconds added");
        }
    }

    /**
     * 执行 classpath 下的建表脚本。脚本内全部是 CREATE TABLE IF NOT EXISTS，可重复执行。
     * 表结构较多时用脚本文件比在 Java 里拼字符串更易审阅。
     */
    private void executeScript(String location) {
        try {
            String script = StreamUtils.copyToString(
                    new ClassPathResource(location).getInputStream(), java.nio.charset.StandardCharsets.UTF_8);
            for (String statement : script.split(";")) {
                String sql = statement.trim();
                if (sql.isEmpty() || sql.startsWith("--")) {
                    continue;
                }
                jdbcTemplate.execute(sql);
            }
            logger.info("schema script executed: {}", location);
        } catch (Exception e) {
            logger.error("execute schema script {} failed: {}", location, e.getMessage(), e);
        }
    }

    /**
     * CREATE TABLE IF NOT EXISTS 不会给已存在的表补列，后续迭代新增的列走这里。
     */
    private void ensureColumn(String table, String column, String alterSql) {
        try {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() "
                            + "AND table_name = ? AND column_name = ?",
                    Integer.class, table, column);
            if (count != null && count == 0) {
                jdbcTemplate.execute(alterSql);
                logger.info("{}.{} added", table, column);
            }
        } catch (Exception e) {
            logger.error("ensure column {}.{} failed: {}", table, column, e.getMessage(), e);
        }
    }
}
