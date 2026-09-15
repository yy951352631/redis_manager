# CacheCloud 非容器环境安装部署手册

适用于生产机不允许安装 Docker、或需要在无外网的内网环境部署的场景。
容器化部署见仓库根目录 [`README.md`](../../README.md)。

---

## 目录

- [1. 部署形态](#1-部署形态)
- [2. 环境要求](#2-环境要求)
- [3. 制作离线介质包](#3-制作离线介质包)
- [4. 基础组件安装](#4-基础组件安装)
- [5. 安装平台](#5-安装平台)
- [6. 验证](#6-验证)
- [7. 日常运维](#7-日常运维)
- [8. 升级与回滚](#8-升级与回滚)
- [9. 卸载](#9-卸载)
- [10. 常见问题](#10-常见问题)
- [附录 A：介质包结构](#附录-a介质包结构)
- [附录 B：配置项清单](#附录-b配置项清单)

---

## 1. 部署形态

```text
                         ┌──────────────────────────────┐
   浏览器  ──── :8080 ──▶ │ nginx                        │
                         │  /              → www/dist   │  静态资源
                         │  /api/v1 /manage → 127.0.0.1:8081 │
                         └──────────────┬───────────────┘
                                        │
                         ┌──────────────▼───────────────┐
                         │ Tomcat 9 (JDK 8)             │  仅监听回环
                         │  webapps/ROOT ← cachecloud-web.war │
                         └───────┬──────────────┬───────┘
                                 │              │
                     ┌───────────▼──┐   ┌───────▼──────────────────┐
                     │ MySQL 5.7    │   │ 3 x Sentinel             │
                     │ redis_manager│   │ Redis 6.2.24（1 主 1 从）│
                     └──────────────┘   └──────────────────────────┘

           被纳管的 Redis 实例在平台之外，经网络与 SSH 访问
```

单机部署时四个组件同机；MySQL / Redis 也可以指向已有实例，改配置即可。

**后端只监听 `127.0.0.1`**，外部流量一律经 nginx。这一点由 `conf/server.xml`
模板保证，不要改成 `0.0.0.0` 再直接对外暴露。

---

## 2. 环境要求

### 操作系统

在 CentOS 7 / Anolis 8 / Ubuntu 20.04 上验证过。其它 Linux 发行版应当可用，
脚本依赖 `bash`、`systemd`、`curl`、`tar`、`python3`（用于配置模板渲染）。

### 硬件

| 项 | 最低 | 建议 | 说明 |
|----|------|------|------|
| CPU | 2 核 | 4 核 | 采集调度与压测都吃 CPU |
| 内存 | 4 GB | 8 GB | Tomcat 默认 `-Xmx1536m`，MySQL 另算 |
| 磁盘 | 40 GB | 100 GB | **见下方说明** |

> **磁盘是最容易踩的坑。** 平台按分钟为每个实例写指标，MySQL 数据 + binlog
> 稳态约 5 GB/周（binlog 默认留 7 天约 4 GB）。磁盘写满会让 MySQL 停止写入，
> 表现为平台所有接口挂起、日志刷 `Communications link failure`。
> 务必对数据目录做容量告警。

### 基础组件版本

| 组件 | 验证版本 | 说明 |
|------|---------|------|
| JDK | 8（Temurin / OpenJDK 均可） | 后端按 JDK 8 编译，高版本 JVM 未验证 |
| Tomcat | 9.0.x | 介质包可自带，见下 |
| MySQL | 5.7.44 | 8.0 亦可，注意 `sql_mode` 与排序规则 |
| Redis | 6.2.24 | 平台自用缓存，1 主 1 从 3 Sentinel，**不是**被纳管对象 |
| nginx | 1.20+ | 托管前端并反代后端 |

### 端口

| 端口 | 用途 | 是否对外 |
|------|------|---------|
| 8080 | nginx，浏览器访问 | 是 |
| 8081 | Tomcat，仅回环 | 否 |
| 3306 | MySQL | 否 |
| 6379/6380 | Redis 主/从 | 否 |
| 26379-26381 | Redis Sentinel | 否 |

前两个可在 `conf/cachecloud.env` 里改。

---

## 3. 制作离线介质包

在**联网的构建机**上执行（需要 JDK8 + Maven + Node 20.19+/22.12+ + pnpm 10+）：

```bash
git clone <仓库地址> && cd cachecloud
bash scripts/build-offline-package.sh
```

产物：

- `dist/cachecloud-offline-<版本>.tar.gz` 与同名 `.sha256`
- `dist/install-cachecloud-offline.sh`：校验、解包、自检并调用包内安装器
- `dist/cachecloud.env.example`：目标机环境配置模板

常用参数：

| 参数 | 作用 |
|------|------|
| `--skip-build` | 复用已有的 `cachecloud-web/target/*.war` 与 `cachecloud-ui/dist` |
| `--with-deps` | 顺带把 Tomcat 压缩包下进 `deps/`，让介质包更自包含 |
| `CC_VERSION=x.y.z` | 指定版本号，默认取 `git describe` |

把 tar.gz 拷到目标机后先核对校验和：

```bash
bash install-cachecloud-offline.sh --verify-only cachecloud-offline-<版本>.tar.gz
```

入口脚本会依次校验外层 `.sha256` 和包内 `MANIFEST.sha256`。没有入口脚本时，
仍可手工执行 `shasum`、`tar` 和包内的 `install.sh`。

---

## 4. 基础组件安装

介质包**不含** JDK / MySQL / Redis / nginx —— 这些的分发形式与发行版强相关，
且多数需要按 OS 版本选包。在联网机上执行 `bash scripts/fetch-deps.sh --all`
会打印各组件的获取方式；下好后放进 `deps/` 一并拷入内网。

### 4.1 JDK 8

```bash
# RHEL / CentOS / Anolis
yum install -y java-1.8.0-openjdk-devel
# 离线：先在联网同版本机器上
#   yum install --downloadonly --downloaddir=./deps java-1.8.0-openjdk-devel
# 内网：rpm -Uvh deps/*.rpm

java -version    # 应显示 1.8.x
```

### 4.2 MySQL 5.7

安装略（按发行版）。**必须**确认以下参数，否则建表或写入会出问题：

```ini
[mysqld]
character-set-server            = utf8mb4
collation-server                = utf8mb4_bin
lower_case_table_names          = 1
max_allowed_packet              = 256M
sql_mode = STRICT_TRANS_TABLES,NO_ZERO_IN_DATE,NO_ZERO_DATE,ERROR_FOR_DIVISION_BY_ZERO,NO_AUTO_CREATE_USER,NO_ENGINE_SUBSTITUTION

# 建议开启 binlog，用于恢复到故障前任意时刻
server-id        = 1
log-bin          = mysql-bin
binlog-format    = ROW
binlog-row-image = FULL
expire-logs-days = 7
max-binlog-size  = 256M
```

`lower_case_table_names=1` 只能在**初始化前**设置，建库后再改会导致启动失败。

平台默认用 `root` 连库。要用独立账号，建好后授权：

```sql
CREATE DATABASE redis_manager DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;
CREATE USER 'cachecloud'@'127.0.0.1' IDENTIFIED BY '<强口令>';
GRANT ALL PRIVILEGES ON redis_manager.* TO 'cachecloud'@'127.0.0.1';
-- 后端启动时要读 information_schema 判断表/列是否存在，上面的授权已够
FLUSH PRIVILEGES;
```

### 4.3 Redis Sentinel（平台自用）

```bash
tar xzf redis-6.2.24.tar.gz && cd redis-6.2.24 && make && make install
```

部署 1 主 1 从 3 Sentinel，逻辑主节点名固定为 `cachecloud-master`。下面是同机端口
示例；生产环境建议把主从和 Sentinel 分散到不同主机，否则只能防进程故障，不能防
宿主机故障。

主节点 `redis-master.conf`：

```ini
bind 127.0.0.1
port 6379
requirepass <强口令>
masterauth <强口令>
appendonly yes
maxmemory 512mb
maxmemory-policy allkeys-lru
```

从节点 `redis-replica.conf`：

```ini
bind 127.0.0.1
port 6380
replicaof 127.0.0.1 6379
requirepass <强口令>
masterauth <强口令>
appendonly yes
```

分别创建 3 份可写的 Sentinel 配置，端口使用 `26379`、`26380`、`26381`：

```ini
bind 127.0.0.1
port 26379
sentinel monitor cachecloud-master 127.0.0.1 6379 2
sentinel auth-pass cachecloud-master <强口令>
sentinel down-after-milliseconds cachecloud-master 5000
sentinel failover-timeout cachecloud-master 30000
sentinel parallel-syncs cachecloud-master 1
```

使用 systemd 分别托管 5 个进程；Redis 进程执行 `redis-server <配置>`，Sentinel
执行 `redis-sentinel <配置>`。Sentinel 会重写配置文件，不能以只读方式挂载。启动后确认：

```bash
redis-cli -p 26379 sentinel get-master-addr-by-name cachecloud-master
redis-cli -p 26379 sentinel ckquorum cachecloud-master
```

> 这个 Redis 只给平台自己用（缓存、分布式锁），不要把它当作被纳管的业务实例。

### 4.4 nginx

```bash
yum install -y nginx          # 或 apt-get install -y nginx
systemctl enable --now nginx
```

站点配置由 `install.sh` 写到 `conf.d/cachecloud.conf`，此处不用手工配。

---

## 5. 安装平台

### 5.1 填写配置

```bash
cp cachecloud.env.example cachecloud.env
chmod 600 cachecloud.env
vi cachecloud.env
```

至少要改 `CC_MYSQL_PASSWORD` 和 `CC_REDIS_PASSWORD`，并核对
`CC_REDIS_SENTINEL_MASTER`、`CC_REDIS_SENTINEL_NODES` 与实际部署一致。
完整清单见[附录 B](#附录-b配置项清单)。

`CC_SERVER_DOMAIN` **必须**填浏览器实际访问到的地址并带上端口
（例如 `http://10.1.2.3:8080`）。填错会让后端把同源请求判成跨域，接口返回
`403 Invalid CORS request`。

### 5.2 自检

使用外层安装入口时，自检会在正式安装前自动执行。手工解包安装时执行
`bash scripts/preflight.sh`。

只读检查，会一次性列出所有不满足的条件：配置项、JDK 版本、Tomcat、nginx、
MySQL 连通性与库是否为空、Redis 端口、端口占用、磁盘余量、介质包完整性。
**通过后再装**，否则容易装到一半才发现缺东西。

### 5.3 安装

```bash
sudo bash install-cachecloud-offline.sh cachecloud-offline-<版本>.tar.gz cachecloud.env
```

若已手工解包并进入介质包目录，也可以继续执行 `sudo bash install.sh`。

脚本会依次：

1. 建系统账号 `cachecloud` 与目录 `/opt/cachecloud`
2. 准备 Tomcat（复用已有的，或从 `deps/` 解压），清掉自带样例应用
3. 部署 `ROOT.war` 与前端 `dist`
4. **库为空时**导入 `sql/init.sql`；已有表则跳过（升级场景）
5. 渲染 `setenv.sh`、`server.xml`、nginx 站点配置、systemd 单元
6. 启动服务并等待 `/api/v1/health` 就绪，再 reload nginx

首次启动要展开 WAR 并初始化，约 60–90 秒，脚本最多等 180 秒。

安装是**可重复执行**的：再跑一次等于升级，已有库不会被动。

---

## 6. 验证

```bash
systemctl status cachecloud-web
curl -fsS http://127.0.0.1:8081/api/v1/health && echo OK    # 后端
curl -fsSI http://127.0.0.1:8080/ | head -1                 # 前端

# 建表是否完整，应为 72
mysql -h127.0.0.1 -uroot -p -N -B -e \
  "select count(*) from information_schema.tables where table_schema='redis_manager'"
```

浏览器打开 `http://<服务器IP>:8080`，用 `admin` / `admin%TGB7ygv` 登录。

> **首次登录后立刻改密码。** 该口令写死在 `init.sql` 的 `app_user` 种子数据里
> （存的是 MD5 `fae3997daa86ba75a7d81c1b7d8228fd`），任何一份代码副本都能看到。

登录后新增一套 Redis 集群，等 1–2 分钟确认「集群管理」里图表开始出点
（采集按分钟写入）。

---

## 7. 日常运维

### 服务控制

```bash
systemctl {start|stop|restart|status} cachecloud-web
```

`TimeoutStopSec=60`，停机会给 Quartz 作业留收尾时间，不要用 `kill -9`。

### 日志

| 内容 | 位置 |
|------|------|
| 启动与运行日志 | `journalctl -u cachecloud-web -f` |
| Tomcat 标准输出 | `/opt/cachecloud/tomcat/logs/catalina.out` |
| 访问日志 | `/opt/cachecloud/tomcat/logs/access.<日期>.log` |
| nginx | `/var/log/nginx/cachecloud.{access,error}.log` |
| OOM heap dump | `/opt/cachecloud/logs/*.hprof` |

`catalina.out` 不会自动轮转，配一条 logrotate：

```
/opt/cachecloud/tomcat/logs/catalina.out {
    daily
    rotate 14
    compress
    copytruncate
    missingok
    notifempty
}
```

### 数据库备份

容器方案有备份 sidecar，裸机需自行配 cron：

```bash
# /etc/cron.d/cachecloud-backup —— 每 6 小时全量，保留 14 天
0 */6 * * * root /usr/bin/mysqldump --single-transaction --master-data=2 \
  -h127.0.0.1 -uroot -p'<口令>' redis_manager \
  | gzip > /data/backup/redis_manager-$(date +\%Y\%m\%d-\%H\%M\%S).sql.gz \
  && find /data/backup -name 'redis_manager-*.sql.gz' -mtime +14 -delete
```

`--master-data=2` 会把 binlog 位点写进 dump 头部，因此可以恢复到故障前任意时刻，
而不只是回到最近一次快照。

> 恢复演练比备份本身更重要：把 dump 导进临时库比对行数，确认备份真的能用。

### 数据增长

指标表是主要增长源，平台自带清理作业（`CleanUpMinuteStatisticsJob` 等）。
若磁盘吃紧，重点关注：

```sql
SELECT table_name, ROUND(data_length/1024/1024) AS data_mb,
       ROUND(index_length/1024/1024) AS idx_mb
FROM information_schema.tables
WHERE table_schema='redis_manager'
ORDER BY data_length + index_length DESC LIMIT 10;
```

binlog 单独占空间，`expire-logs-days` 决定稳态大小。手工清理：

```sql
SHOW BINARY LOGS;                          -- 看当前正在写哪个
PURGE BINARY LOGS TO 'mysql-bin.000026';   -- 清掉它之前的
```

---

## 8. 升级与回滚

### 升级

```bash
# 1. 构建机出新介质包
CC_VERSION=1.3.0 bash scripts/build-offline-package.sh

# 2. 目标机：先备份
mysqldump --single-transaction --master-data=2 -uroot -p redis_manager \
  | gzip > /data/backup/before-1.3.0.sql.gz

# 3. 解包后用同一份 conf/cachecloud.env（从上一版拷过来）
tar xzf cachecloud-offline-1.3.0.tar.gz && cd cachecloud-offline-1.3.0
cp ../cachecloud-offline-1.2.0/conf/cachecloud.env conf/
bash scripts/preflight.sh
sudo bash install.sh
```

数据库**不需要**手工操作：后端启动时 `DatabaseSchemaInitializer` 会补建新表、
新列，可重复执行。库里已有表时 `install.sh` 会跳过 `init.sql`。

> **不要**对已有库执行 `init.sql`，它含大量 `DROP TABLE`。该文件顶部有护栏
> （检测到 `app_desc` 已有数据会在任何 `DROP` 之前报错中止），但不要依赖它。

### 回滚

```bash
cd ../cachecloud-offline-<上一版>
sudo bash install.sh
```

回滚只换应用文件，**不会**回退数据库结构。新版加的表和列会留着，
旧版不认识它们，不影响运行。若新版做过破坏性的结构变更（改列类型、删列），
则需要从备份恢复库——所以升级前的备份不能省。

---

## 9. 卸载

```bash
sudo bash uninstall.sh            # 停服务、删应用与配置，保留数据库
sudo bash uninstall.sh --purge    # 额外删除数据库（不可恢复）
```

两种都会要求输入 `yes` 确认。MySQL / Redis / nginx / JDK 不会被动——
它们可能还被别的系统用着。运行账号与 `/opt/cachecloud` 也会保留，
确认无用后手工清理。

---

## 10. 常见问题

| 现象 | 原因与处理 |
|------|-----------|
| `install.sh` 报「配置项 X 还是模板默认值」 | `conf/cachecloud.env` 没改完，`replace-me` 之类占位没替换 |
| 后端 180 秒没就绪 | 看 `catalina.out`。多数是连不上 MySQL（地址/口令/授权）或端口被占 |
| 页面能开但接口 403 `Invalid CORS request` | `CC_SERVER_DOMAIN` 与浏览器实际访问地址（含端口）不一致，改后 `systemctl restart cachecloud-web` |
| 页面 403 / 静态资源 404，nginx 日志是 `Permission denied` | SELinux。`setsebool -P httpd_can_network_connect 1`，并 `chcon -Rt httpd_sys_content_t /opt/cachecloud/www/dist` |
| 上传 RDB 报 `413 Request Entity Too Large` | nginx 站点配置缺 `client_max_body_size`。确认 `conf.d/cachecloud.conf` 是本介质包生成的 |
| 上传报 `Current request is not a multipart request` | 前端 dist 是旧版，重新出包 |
| 刷新页面 404 | nginx 缺 `try_files ... /index.html`，同上 |
| 所有接口挂起，日志刷 `Communications link failure` | **磁盘写满**，MySQL 停止写入。`df -h` 确认后清理 binlog 与旧备份 |
| 图表整片空白 | 该集群当前没有业务流量属正常（曲线为 0）；连 0 都没有则查采集作业与 MySQL |
| 登录失败 | 确认 `app_user` 表里 `admin` 的 `password` 为 `fae3997daa86ba75a7d81c1b7d8228fd` |

排障常用：

```bash
journalctl -u cachecloud-web -n 200 --no-pager
tail -200 /opt/cachecloud/tomcat/logs/catalina.out
sudo -u cachecloud jstack $(cat /opt/cachecloud/cachecloud-web.pid)   # 卡住时看线程栈
```

---

## 附录 A：介质包结构

```text
cachecloud-offline-<版本>/
├── README.md              本手册
├── VERSION                版本号、git sha、构建时间
├── MANIFEST.sha256        逐文件校验和
├── install.sh             安装（可重复执行 = 升级）
├── uninstall.sh           卸载
├── app/
│   ├── cachecloud-web.war 后端
│   └── dist/              前端静态资源
├── sql/
│   ├── init.sql           全新初始化（72 张表 + 种子数据）
│   ├── upgrade.sql        老版本库的增量脚本
│   └── README.md          脚本选用说明
├── conf/
│   ├── cachecloud.env.example  配置模板 → 复制成 cachecloud.env
│   ├── setenv.sh               Tomcat JVM 参数与应用环境变量
│   ├── server.xml              精简版 Tomcat 配置（仅监听回环）
│   ├── nginx-cachecloud.conf   nginx 站点配置
│   └── cachecloud-web.service  systemd 单元
├── scripts/
│   ├── preflight.sh       环境自检（只读）
│   ├── fetch-deps.sh      联网机上拉取第三方介质
│   └── lib.sh             共用函数
└── deps/                  第三方介质（Tomcat 等），默认为空
```

`conf/` 下除 `cachecloud.env` 外都是模板，`@VAR@` 占位由 `install.sh` 渲染。
直接改目标机上生成的文件会在下次安装时被覆盖（覆盖前会备份成 `.bak.<时间戳>`），
要持久化改动请改介质包里的模板。

---

## 附录 B：配置项清单

`conf/cachecloud.env`：

| 变量 | 必填 | 默认 | 说明 |
|------|------|------|------|
| `CC_HOME` | 是 | `/opt/cachecloud` | 安装根目录 |
| `CC_USER` | 是 | `cachecloud` | 运行账号，不存在会自动创建 |
| `CC_TOMCAT_HOME` | 否 | `$CC_HOME/tomcat` | 已有 Tomcat 就填其路径 |
| `CC_HTTP_PORT` | 是 | `8080` | nginx 端口，浏览器访问 |
| `CC_BACKEND_PORT` | 是 | `8081` | Tomcat 端口，仅回环 |
| `CC_SERVER_DOMAIN` | 是 | `http://127.0.0.1:8080` | 浏览器实际访问地址，**含端口** |
| `CC_MYSQL_HOST` | 是 | `127.0.0.1` | |
| `CC_MYSQL_PORT` | 是 | `3306` | |
| `CC_MYSQL_DB` | 是 | `redis_manager` | 非默认值时 `install.sh` 会自动改写 `init.sql` |
| `CC_MYSQL_USER` | 是 | `root` | |
| `CC_MYSQL_PASSWORD` | 是 | — | |
| `CC_REDIS_HOST` | 是 | `127.0.0.1` | 平台自用缓存 |
| `CC_REDIS_PORT` | 是 | `6379` | |
| `CC_REDIS_PASSWORD` | 否 | 空 | Redis 未设 `requirepass` 时留空 |
| `CC_REDIS_SENTINEL_MASTER` | 否 | `cachecloud-master` | Sentinel 监控的逻辑主节点名；与节点列表同时留空时使用直连模式 |
| `CC_REDIS_SENTINEL_NODES` | 否 | `127.0.0.1:26379,...` | Sentinel 地址，逗号分隔；与主节点名必须同时配置 |
| `CC_REDIS_SENTINEL_PASSWORD` | 否 | 空 | Sentinel 自身启用认证时填写，不是 Redis 数据节点口令 |
| `CC_JAVA_XMS` | 否 | `512m` | |
| `CC_JAVA_XMX` | 否 | `1536m` | 不超过物理内存 1/4 |
| `CC_PROFILE` | 否 | `online` | 对应 `application-<profile>.yml` |
| `CC_TIMEZONE` | 否 | `Asia/Shanghai` | JVM 与 JDBC 时区，两边必须一致 |

后端还认这些环境变量，需要时加到 `conf/setenv.sh` 模板里：

| 变量 | 说明 |
|------|------|
| `CACHECLOUD_CORS_ALLOWED_ORIGINS` | 跨域白名单。同源部署留空，**不要填 `*`**（已开 allowCredentials） |
| `CACHECLOUD_ALERT_ENABLED` | 报警与巡检作业开关，留空按 profile 判定 |
| `CACHECLOUD_AI_ENABLED` / `DEEPSEEK_API_KEY` | AI 辅助分析，默认关闭 |
