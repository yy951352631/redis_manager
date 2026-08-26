# CacheCloud deployment

The production stack runs from `/app/cachecloud` and exposes only the web
portal on host port `28082`. MySQL and the assist Redis are private to the
Compose network and persist in named volumes.

For a manual first run:

```bash
cp deploy/.env.example .env
# Replace both passwords before starting.
docker compose up -d
docker compose ps
```

`deploy/mysql/init.sql` is executed only when the MySQL volume is empty. Do not
delete the `cachecloud_mysql-data` volume during routine application upgrades.

## 数据库名

业务库为 `redis_manager`。改名需同步四处：`compose.yaml` 的
`CACHECLOUD_PRIMARY_URL`、`.env` 的 `CACHECLOUD_DB_NAME`、
`deploy/mysql/init.sql` 顶部的 `CREATE DATABASE` / `USE`，
以及 `application*.yml` 里的 datasource 默认值。

## 备份与恢复

`mysql-backup` sidecar 常驻运行：启动即备份一次，之后按
`MYSQL_BACKUP_INTERVAL_SECONDS` 循环，产物为
`deploy/mysql/backups/redis_manager-<时间戳>.sql.gz`，超过
`MYSQL_BACKUP_KEEP_DAYS` 天自动清理。

MySQL 已开启 binlog（见 `deploy/mysql/conf.d/binlog.cnf`），dump 头部带
`CHANGE MASTER TO ... MASTER_LOG_POS=<位点>`，因此可以恢复到故障前的任意时刻，
而不只是回到最近一次快照。

恢复整库：

```bash
gunzip -c deploy/mysql/backups/redis_manager-20260823-221904.sql.gz | \
  docker exec -i cachecloud-mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD"
```

再重放快照之后的增量（PITR）：

```bash
docker exec cachecloud-mysql mysqlbinlog \
  --start-position=<dump 头部的 MASTER_LOG_POS> \
  --stop-datetime="2026-08-23 22:30:00" \
  /var/lib/mysql/mysql-bin.000001 \
  | docker exec -i cachecloud-mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" redis_manager
```

**恢复演练比备份本身更重要**：把 dump 里的库名换成临时库导入，比对行数，
确认备份真的能用，再删掉临时库。
