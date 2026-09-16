# 数据库初始化

## 场景怎么选

| 现状 | 怎么做 |
|------|--------|
| 空库 / 全新部署 | 只跑 `init.sql` |
| 已按 **cache-cloud-master**（`v1.0.2`）建好库 | 只跑 `upgrade.sql`，**不要**再跑 `init.sql` |

## 全新部署

```bash
mysql -uroot -p < init.sql
```

库名默认 `redis_manager`，需与 `application*.yml` 一致；若用其它库名，改 `init.sql` 顶部两处后再导入。

默认管理员：`admin` / `admin%TGB7ygv`（库中为 BCrypt 哈希，首次登录后请立即修改）。

## 从 cache-cloud-master 升级（已有库）

```bash
mysql -uroot -p redis_manager < upgrade.sql
```

可重复执行（已存在的列/表会跳过）。不要对已有库执行 `init.sql`（含 `DROP TABLE`）。

`init.sql` 顶部有一道护栏：目标库里只要 `app_desc` 有数据，就会在任何 `DROP` 之前
以「表不存在」的错误中止（`ABORT__target_database_is_not_empty__run_upgrade_sql_instead`），
mysql 客户端遇错即停。看到这个报错说明你选错脚本了，改用 `upgrade.sql`。

另注意 `init.sql` 第 18-19 行硬编码了 `CREATE DATABASE` / `USE redis_manager`：
**在命令行指定别的库名不会生效**，脚本始终作用于 `redis_manager`。要换库名请改这两行。

## 其它文件（按需，与建库无关）

- `my.cnf` / `os_params.txt` — MySQL / 系统参数参考
