# redis_manager

Redis 运维管理平台（基于 CacheCloud 二次开发，前后端分离）。

提供集群纳管、指标采集与图表、慢日志、键值分析、离线 RDB/AOF 分析、风险评估、
数据迁移、压测工具与操作审计。

---

## 目录

- [架构](#架构)
- [部署方式选择](#部署方式选择)
- [容器化部署](#容器化部署)（推荐）
- [配置项](#配置项)
- [验证](#验证)
- [升级](#升级)
- [备份与恢复](#备份与恢复)
- [常见问题](#常见问题)
- [非容器部署](#非容器部署)
- [开发环境](#开发环境)

---

## 架构

```text
浏览器
  │
  ▼
cachecloud-frontend  (nginx:1.27, 容器端口 80 → 宿主 28082)
  ├─ /                        静态资源（Vue3 SPA，构建产物 dist）
  └─ /api/v1 /manage /admin   反向代理到后端
       │
       ▼
cachecloud-web  (tomcat:9 + JDK8, 容器端口 8080 → 宿主 28083)
       ├──► cachecloud-mysql  (mysql:5.7.44)   业务库 redis_manager，已开 binlog
       └──► 3 x Sentinel ──► Redis 6.2.24（1 主 1 从） 平台自用，非被纳管对象

cachecloud-mysql-backup  (sidecar)  定时 mysqldump 到 deploy/mysql/backups/
```

被纳管的 Redis 实例在平台之外，通过网络与 SSH 访问，不由本 compose 管理。

| 组件 | 说明 |
|------|------|
| `cachecloud-ui` | Vue3 + Vite 前端，构建成静态站点由 nginx 托管 |
| `cachecloud-web` | Spring Boot（打 WAR 跑在外置 Tomcat），业务后端与采集调度 |
| `cachecloud-custom` | 后端依赖的内部 jar，随后端一起构建 |

---

## 部署方式选择

| 场景 | 方式 | 文档 |
|------|------|------|
| 有 Docker，联网或已有镜像 | Docker Compose | 本文档 |
| 生产机无 Docker / 内网离线 | 裸机 + 离线介质包 | [`docs/deploy/offline-install.md`](docs/deploy/offline-install.md) |
| CI 自动发布 | GitLab CI | `.gitlab-ci.yml` |

---

## 容器化部署

### 1. 前置条件

| 项 | 要求 |
|----|------|
| Docker Engine | 20.10+ |
| Docker Compose | v2（`docker compose`，不是 `docker-compose`） |
| 磁盘 | ≥ 40 GB 可用。指标按分钟写入，MySQL 数据 + binlog 稳态约 5 GB/周 |
| 内存 | ≥ 4 GB |
| 端口 | 28082（前端）、28083（后端，仅排障用，可不放行） |

> **磁盘务必留足**：Docker 虚拟盘写满会让 MySQL 停止写入，平台所有接口挂起。
> binlog 默认保留 7 天（`deploy/mysql/conf.d/binlog.cnf`），稳态约 4 GB。

### 2. 准备配置

```bash
cp deploy/.env.example .env
chmod 600 .env
$EDITOR .env          # 至少替换 MYSQL_ROOT_PASSWORD 和 REDIS_PASSWORD
```

`.env` 必须放在仓库根目录（与 `compose.yaml` 同级），已被 `.gitignore` 忽略。

### 3. 构建镜像

联网机器上：

```bash
docker build -t cachecloud:local .
docker build -f cachecloud-ui/Dockerfile -t cachecloud-ui:local cachecloud-ui
```

两个 Dockerfile 都是多阶段构建，宿主机不需要装 JDK / Maven / Node。
构建产物名要与 `.env` 里的 `CACHECLOUD_IMAGE` / `CACHECLOUD_FRONTEND_IMAGE` 一致。

需要走代理时加 `--build-arg HTTP_PROXY=... --build-arg HTTPS_PROXY=... --build-arg NO_PROXY=...`。

### 4. 启动

```bash
docker compose config -q     # 先校验配置，不输出即通过
docker compose up -d
docker compose ps
```

首次启动时序：MySQL 起来并通过健康检查 → 执行 `deploy/mysql/init.sql` 建库建表 →
后端启动（`DatabaseSchemaInitializer` 补齐运行期表并写入风险评估默认规则）→ 前端启动。
后端首次启动约 60–90 秒。

**`init.sql` 只在 MySQL 数据卷为空时执行一次。** 日常升级不要删 `cachecloud_mysql-data` 卷。

### 5. 访问

浏览器打开 `http://<宿主机IP>:28082`，默认账号 `admin` / `admin%TGB7ygv`。

> **首次登录后立刻改密码**。该口令写死在 `init.sql` 的 `app_user` 种子数据里
> （存的是 MD5），任何一份代码副本都能看到。

---

## 配置项

`.env` 中的变量（完整清单见 `deploy/.env.example`）：

| 变量 | 必填 | 默认 | 说明 |
|------|------|------|------|
| `CACHECLOUD_IMAGE` | 是 | — | 后端镜像 |
| `CACHECLOUD_FRONTEND_IMAGE` | 是 | — | 前端镜像 |
| `MYSQL_ROOT_PASSWORD` | 是 | — | MySQL root 口令，后端也用它连库 |
| `REDIS_PASSWORD` | 是 | — | 平台自用 Redis 口令 |
| `CACHECLOUD_REDIS_IMAGE` | 否 | `redis:6.2.24-alpine` | 平台自用 Redis 主从与 Sentinel 镜像，必须保持 6.2.24 |
| `CACHECLOUD_FRONTEND_PORT` | 否 | 28082 | 前端对外端口 |
| `CACHECLOUD_BACKEND_PORT` | 否 | 28083 | 后端直连端口（排障用） |
| `SERVER_DOMAIN` | 否 | `http://127.0.0.1:28082` | 浏览器实际访问地址，含端口 |
| `CACHECLOUD_DB_NAME` | 否 | `redis_manager` | 仅备份 sidecar 用，改库名见下 |
| `MYSQL_BACKUP_INTERVAL_SECONDS` | 否 | 21600 | 备份间隔 |
| `MYSQL_BACKUP_KEEP_DAYS` | 否 | 14 | 备份保留天数 |

后端还认这些环境变量（在 `compose.yaml` 的 `web.environment` 里改）：

| 变量 | 说明 |
|------|------|
| `SPRING_PROFILES_ACTIVE` | `local` / `online` / `test`，容器默认 `local` |
| `CACHECLOUD_CORS_ALLOWED_ORIGINS` | 跨域白名单。同源部署留空即可，**不要填 `*`**（已开 allowCredentials） |
| `CACHECLOUD_ALERT_ENABLED` | 报警与巡检作业开关，留空按 profile 判定 |
| `CACHECLOUD_AI_ENABLED` / `DEEPSEEK_API_KEY` | AI 辅助分析，默认关闭 |

**改库名要同步四处**：`compose.yaml` 的 `CACHECLOUD_PRIMARY_URL`、`.env` 的
`CACHECLOUD_DB_NAME`、`deploy/mysql/init.sql` 顶部的 `CREATE DATABASE` / `USE`、
以及 `application*.yml` 的 datasource 默认值。

---

## 验证

```bash
# 容器状态，九个都应是 running / healthy
docker compose ps

# 三个 Sentinel 应返回同一个当前主节点
for n in 1 2 3; do
  docker exec "cachecloud-redis-sentinel-$n" redis-cli -p 26379 \
    sentinel get-master-addr-by-name cachecloud-master
done

# 后端健康检查
curl -fsS http://127.0.0.1:28083/api/v1/health && echo OK

# 前端
curl -fsSI http://127.0.0.1:28082/ | head -1

# 建表是否完整，应为 72
docker exec cachecloud-mysql sh -c \
  'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -N -B -e \
   "select count(*) from information_schema.tables where table_schema=\"redis_manager\""'
```

登录后确认「集群管理」能列出集群、图表有数据点（采集按分钟写入，需等 1–2 分钟）。

---

## 升级

```bash
git pull
docker build -t cachecloud:<tag> .
docker build -f cachecloud-ui/Dockerfile -t cachecloud-ui:<tag> cachecloud-ui
$EDITOR .env                 # 改 CACHECLOUD_IMAGE / CACHECLOUD_FRONTEND_IMAGE
docker compose up -d
```

数据库无需手工操作：后端启动时 `DatabaseSchemaInitializer` 会补建新表、新列，
可重复执行。**不要**对已有库跑 `init.sql`（含大量 `DROP TABLE`）——该文件顶部有护栏，
检测到 `app_desc` 已有数据会在任何 `DROP` 之前报错中止，但不要依赖它。
已有库的增量脚本见 `cachecloud-web/sql/README.md`。

回滚：把 `.env` 里的镜像 tag 改回上一版，再 `docker compose up -d`。

---

## 备份与恢复

`mysql-backup` sidecar 常驻：启动即备份一次，之后按 `MYSQL_BACKUP_INTERVAL_SECONDS`
循环，产物为 `deploy/mysql/backups/redis_manager-<时间戳>.sql.gz`，
超过 `MYSQL_BACKUP_KEEP_DAYS` 天自动清理。

MySQL 已开 binlog，dump 头部带 `MASTER_LOG_POS`，可恢复到故障前任意时刻，
而不只是回到最近一次快照。恢复步骤与 PITR 重放见 [`deploy/README.md`](deploy/README.md)。

> 恢复演练比备份本身更重要：把 dump 导进临时库比对行数，确认备份真的能用。

---

## 常见问题

| 现象 | 原因与处理 |
|------|-----------|
| `docker compose up` 报 `CACHECLOUD_FRONTEND_IMAGE is missing` | `.env` 不在仓库根目录，或未从 `deploy/.env.example` 复制 |
| 所有接口挂起、`docker logs cachecloud-web` 里是 `Communications link failure` | 磁盘写满，MySQL 停止写入。`df -h` 确认后清理，见「前置条件」 |
| 上传 RDB 报 `413 Request Entity Too Large` | 前端镜像是旧版。`cachecloud-ui/docker/nginx.conf` 需含 `client_max_body_size 2200m`，重新构建前端镜像 |
| 上传报 `Current request is not a multipart request` | 前端构建产物是旧版，重新构建 |
| 页面能开但接口 403 `Invalid CORS request` | `SERVER_DOMAIN` 与浏览器实际访问地址（含端口）不一致 |
| 图表整片空白 | 该集群当前没有业务流量属正常（曲线为 0）；若连 0 都没有，查采集作业与 MySQL |
| 登录失败 | 确认 `app_user` 表里 `admin` 的 `password` 为 `fae3997daa86ba75a7d81c1b7d8228fd` |

排障入口：

```bash
docker compose logs -f web          # 后端
docker compose logs -f frontend     # nginx 访问与错误日志
docker exec cachecloud-web jstack 1 # 卡住时看线程栈
```

---

## 非容器部署

生产机不允许装 Docker，或需要内网离线安装时，使用裸机部署：

- 手册：[`docs/deploy/offline-install.md`](docs/deploy/offline-install.md)
- 打包：`bash scripts/build-offline-package.sh`，产出可拷贝到内网的离线介质包
  （含 WAR、前端 dist、SQL 初始化脚本、安装/卸载脚本、systemd 单元与配置模板）

---

## 开发环境

```bash
# 后端（需要 JDK8 + Maven，连本机 MySQL/Redis）
mvn -pl cachecloud-web -am spring-boot:run -Dspring-boot.run.profiles=local

# 前端（Node 20.19+/22.12+ 与 pnpm 10+，Vite 代理到 8080）
cd cachecloud-ui && pnpm install && pnpm dev     # http://localhost:3333
```

- 后端测试默认跳过，需显式打开：`mvn -pl cachecloud-web -am -DskipTests=false test`
- 前端部署细节（子路径、Nginx 模板）见 [`cachecloud-ui/DEPLOY.md`](cachecloud-ui/DEPLOY.md)
- 数据库脚本说明见 [`cachecloud-web/sql/README.md`](cachecloud-web/sql/README.md)
