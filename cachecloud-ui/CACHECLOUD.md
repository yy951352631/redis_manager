# CacheCloud 全量前后端分离迁移计划

> 目标：**JSP 全部下线**，由 `cachecloud-ui`（Vue SPA）+ `cachecloud-web`（`/api/v1/*` REST）替代。  
> **现状（2026-08-20）：已完成。** `cachecloud-web` 中 244 个 JSP、`WEB-INF` 旧静态资源与 TLD、25 个纯视图 Controller 全部删除；
> `tomcat-embed-jasper` / `jstl` 依赖与 JSP 视图解析配置一并移除，war 内不再包含任何 `.jsp`。  
> **保留的非 `/api/v1` 遗留端点**（仅剩 SPA 仍在调用的 4 个，均为直出 JSON）：
> `/admin/app/redisConfig`、`/manage/instance/addInstanceConfigChange`、`/manage/ai/*`、`/manage/diagnostic/fault/report.json`。  
> **随本次下线一并移除的能力**：工单/审批流（应用申请、管理员审批、应用部署）此前仅存在于 JSP，SPA 未提供替代页面，
> 新应用改由「外部 Redis 纳管」入口接入。

> **本轮同时完成**：
> - 删除所有无 UI 调用的模块：客户端 SDK 接口（`/cache/client/*`、`/cachecloud/client/*`）、系统间接口 `/interface/*`、
>   运维接口 `/operation/*`、`/api/automation`、`/cache/triggers/*`、`manage/redis/upgrade/*`，以及 57 个随之失去引用的类。
> - 新增**审计日志**：所有 POST/PUT/DELETE/PATCH 请求由 `OperationAuditInterceptor` 落库 `operation_audit`，
>   SPA 页面 `/audit/list`，接口 `GET /api/v1/audits`。敏感字段（password/token/secret 等）落库前脱敏。
> - Spring Boot **2.2.9 → 2.7.18**（Java 8 上的 2.x 末版），移除 Spring Cloud、springfox、hystrix、jolokia、struts-taglib 依赖。

下文的 JSP → API 对照表保留为迁移期的历史索引，其中的 `.jsp` 文件均已不存在。

---

## 一、迁移范围（全量清单）

### 1.1 规模

| 维度 | 数量 |
|------|------|
| JSP 文件 | **0**（迁移前 244，已全部删除） |
| UI Controller | **0**（迁移前 35，已全部删除或裁剪为 JSON 端点） |
| 已有 SPA API Controller | **21** 个（`controller/api/*`） |
| Vue 管理端路由 | **19** 个（15 菜单 + 4 扩展页） |
| 需新建 Portal API | **约 3 个业务域**（后期） |

### 1.2 两套路由体系（必须都迁）

| 体系 | 用户 | 旧路径前缀 | JSP 数 | Vue 前缀建议 |
|------|------|-----------|--------|-------------|
| **管理端** | 管理员 | `/manage/*`、`/data/migrate/*` | **149 + 7** | `/manage/...` 或扁平 `/total`、`/app`… |
| **应用端** | 普通用户 | `/admin/*` | **49 + 8 + 3** | `/portal/...` |
| **公共** | 匿名/全员 | `/user/*`、`/wiki/*` | 1 + wiki | `/register`、`/wiki` |

管理端菜单（`left.jsp`）15 项 + 应用端「我的应用 / 我的工单」+ 应用/实例详情 Tab 页，**全部在范围内，无例外**。

---

## 二、管理端菜单 → 页面 → API 对照表（15 项全展开）

### M1 全局统计 `P0`

| 项 | 旧 JSP | 旧 Controller | 需新增 API | Vue 页面 |
|----|--------|---------------|-----------|----------|
| 首页大盘 | Vue SPA | - | `GET /api/v1/dashboard/overview` | `pages/cachecloud/dashboard/index.vue` |
| 拓扑异常 | 同上页内嵌 | `FaultController` | `GET /api/v1/dashboard/topology-alerts` | 同上，子区块 |
| 资源图表×4 | 同上 | 内部 Highcharts 数据 | `GET /api/v1/dashboard/charts?type=` | 复用 `ChartCard` 组件 |

**子任务：** 4 个统计卡片 + 4 个图表 + 异常列表表格 → **1 个页面，6 个 API**

---

### M2 server 统计 `P1`

| Tab | 旧 JSP | 旧路径 | API |
|-----|--------|--------|-----|
| 默认列表 | `manage/appStat/appStatListServer.jsp` | `/manage/app/stat/list/server` | `GET /api/v1/stats/server/apps` |
| 其他 tab | `manage/appStat/*.jsp` (4) | `tabId` 参数切换 | `GET /api/v1/stats/server/apps?tabId=` |

**Vue：** `pages/cachecloud/stats/server/index.vue`（Tab 容器）

---

### M3 集群管理 `P0`（最大模块）

| 功能 | 旧 JSP 数 | 旧 Controller 路由 | 需新增 API（示例） |
|------|----------|-------------------|-------------------|
| 应用列表 | 1 | `GET /manage/app/list` | `GET /api/v1/apps?status=` |
| 应用详情 Tab×6 | 7 | `/manage/app/index?tabTag=` | `GET /api/v1/apps/{id}` + 各 tab 子接口 |
| 用户视角详情 | 1 | `/manage/app/userDetail` | 复用 portal 组件 `mode=manage` |
| 创建应用 | 1 | `/manage/app/create` | `POST /api/v1/apps` |
| **工单审批** | **23** | `/manage/app/auditList` 等 | `GET/PUT /api/v1/audits/*` |
| 部署/扩缩容/配置变更 | 含在 audit | `initAppDeploy`, `handleHorizontalScale`… | `POST /api/v1/audits/{id}/deploy` 等 |
| 应用运维 Tab×6 | 7 | `/manage/appOps/*` | `GET /api/v1/apps/{id}/ops/*` |
| 滚动重启 | 1 | `/manage/app/restart/*` | `POST /api/v1/apps/{id}/restart/*` |
| 集群拓扑迁移 | 1 | `/manage/app/migrate/*` | `POST /api/v1/apps/{id}/topology-migrate/*` |

**Vue 目录：**
```
pages/cachecloud/app/
├── list/index.vue
├── detail/index.vue          # tabTag 路由
├── detail/tabs/              # ops/instance/machine/detail/code/tool/fault
├── audit/list.vue
├── audit/deploy/*.vue        # 23 个审批流页面按类型拆分
├── create/index.vue
└── components/               # 共用表格、实例拓扑
```

**预估：~40 个 API，~35 个 Vue 文件**

---

### M4 纳管节点 `P2`

| 旧 JSP | 旧路径 | API |
|--------|--------|-----|
| `manage/externalRedis/listContent.jsp` 等 4 个 | `/manage/external/redis/*` | `GET/POST/PUT /api/v1/external-redis` |

已有大量 `@ResponseBody`，**优先包装现有 Service，不 rewrite 业务逻辑**。

---

### M5 数据管理 `P2`

8 个 Tab（`manage/diagnosticTool/index.jsp`）：

| tabTag | 中文 | 旧 JSP | API |
|--------|-----|--------|-----|
| onlineVerify | 在线验证 | `diagnosticOnlineVerify.jsp` | `POST /api/v1/diagnostics/online-verify` |
| redis-cli | redis-cli | `diagnosticRedisCli.jsp` | `POST /api/v1/diagnostics/redis-cli` |
| scan | scan 查询 | `diagnosticScan.jsp` | `POST /api/v1/diagnostics/scan` |
| memoryUsed | memoryUsed | `diagnosticMemUsed.jsp` | `POST /api/v1/diagnostics/memory-used` |
| idlekey | idlekey | `diagnosticIdleKey.jsp` | `POST /api/v1/diagnostics/idle-key` |
| hotkey | hot/big/mem keys | `diagnosticHotKey.jsp` | `POST /api/v1/diagnostics/hot-key` |
| deleteKey | key 清理 | `diagnosticDelKey.jsp` | `POST /api/v1/diagnostics/delete-key` |
| slotAnalysis | slot 分析 | `diagnosticSlot.jsp` | `POST /api/v1/diagnostics/slot-analysis` |
| scanClean | 数据清理任务 | `diagnosticScanClean.jsp` | `POST /api/v1/diagnostics/scan-clean` |

**Vue：** `pages/cachecloud/diagnostics/index.vue` + `tabs/*.vue`

---

### M6 模版配置 `P3`

| 旧 JSP | 路径 | API |
|--------|------|-----|
| `manage/redisConfig/*.jsp` (4) | `/manage/redisConfig/*` | `GET/POST/PUT/DELETE /api/v1/redis-config/templates` |

---

### M7 用户管理 `P2`

| 旧 JSP | 路径 | API |
|--------|------|-----|
| `manage/user/*.jsp` (5) | `/manage/user/*` | `GET/POST/PUT/DELETE /api/v1/users` + `POST reset-password` |

---

### M8 实例运维 `P1`

| Tab | 旧 JSP | API |
|-----|--------|-----|
| 配置检查 | `instanceOps/instanceConfigCheckList.jsp` | `GET /api/v1/instance-ops/config-check` |
| 命令检查 | `instanceOps/instanceCommandCheckList.jsp` | `GET /api/v1/instance-ops/command-check` |

Controller：`InstanceOperationController`（**已有 JSON 方法，直接抽取**）

---

### M9 数据迁移 `P4`

两套系统，**不可合并**：

| 系统 | 旧路径 | JSP | API 前缀 |
|------|--------|-----|----------|
| 批量数据迁移 UI | `/data/migrate/*` | 7 | `/api/v1/data-migrate/*` |
| 集群拓扑迁移 | `/manage/app/migrate/*` | 3 | `/api/v1/apps/{id}/topology-migrate/*` |

---

### M10 机器管理 `P2`

| 功能 | 旧 JSP | API |
|------|--------|-----|
| 机器列表 | `manage/machine/*.jsp` (8) | `/api/v1/machines` |
| 机房 | 同上 | `/api/v1/machine-rooms` |
| Pod | `manage/pod/*.jsp` (2) | `/api/v1/pods` |

---

### M11 报警配置 `P3`

| 旧 JSP | API |
|--------|-----|
| `manage/instanceAlert/*.jsp` (2) | `/api/v1/alerts/instances` |

---

### M12 系统配置 `P3`

| 旧 JSP | API |
|--------|-----|
| `manage/config/*.jsp` (2) | `/api/v1/system-config` + `files/upload/download` |

---

### M13 资源管理 `P3`

| 旧 JSP | API |
|--------|-----|
| `manage/resource/*.jsp` (15) | `/api/v1/resources/redis`, `/module`, `/script` |

---

### M14 任务管理 `P3`

| 旧 JSP | API |
|--------|-----|
| `manage/task/*.jsp` (4) | `/api/v1/tasks` + `/api/v1/tasks/{id}/flow` |

已有 `flow/progress.json` → 正式化为 SSE 或轮询 API。

---

### M15 调度任务 `P3`

| 旧 JSP | API |
|--------|-----|
| `manage/quartz/*.jsp` (2) | `/api/v1/quartz/jobs` CRUD + pause/resume |

---

### 管理端「不在菜单但需迁移」

| 功能 | 旧路径 | JSP 数 | 优先级 |
|------|--------|--------|--------|
| 工单审批入口 | `/manage/app/auditList` | 含 M3 | P0 |
| 故障列表 | `/manage/fault/list` | 2 | P3 |
| 客户端异常 | `/manage/client/exception` | 4 | P4 |
| 服务器监控 | `/manage/server/index` | 2 | P4 |
| AI 助手 | `/manage/ai/*` | drawer | P5 |
| 应用导入（菜单已注释） | `/import/app/*` | 4 | 待定是否保留 |
| 系统通知（菜单已注释） | `/manage/notice/*` | 2 | 待定 |

---

## 三、应用端（Portal）全量清单

### P1 我的应用

| 页面 | 旧 JSP | 旧路径 |
|------|--------|--------|
| 应用列表 | `app/appList.jsp` | `/admin/app/list` |
| 应用详情 11 Tab | `app/*.jsp` (24) | `/admin/app/index?tabTag=` |

**11 个 Tab → 11 个子路由 + 11 组 API**（图表类可复用 `AppController` 现有 JSON：`/admin/app/getCommandStats` 等，先代理再正式化到 `/api/v1/portal/apps/{id}/stats/...`）

### P2 我的工单

| 页面 | 旧 JSP | 旧路径 |
|------|--------|--------|
| 工单列表 | `app/jobIndex/myJobs.jsp` | `/admin/app/jobs` |
| 申请类表单 | `app/jobIndex/*.jsp` (25) | `/admin/app/init`, `appScale`, `appDel`… |

每种工单类型 = 1 个 Vue 表单页 + `POST /api/v1/portal/jobs`（type 区分）

### P3 实例详情

| Tab | 旧 JSP | 旧路径 |
|-----|--------|--------|
| 8 个 Tab | `instance/*.jsp` (8) | `/admin/instance/index?tabTag=` |

**与 manage 实例页共用组件**，`mode: 'portal' | 'manage'`

### P4 键值分析

| 旧 JSP | API |
|--------|-----|
| `analysis/*.jsp` (3) | `/api/v1/key-analysis/*`（已有 `progress.json`） |

---

## 四、后端 API 工程规范

### 4.1 包结构（全量目标）

```
com.shcj.cache.web.controller.api/
├── AuthApiController.java              ✅
├── DashboardApiController.java         ✅ M1
├── AppStatApiController.java           ✅ M2
├── AppApiController.java               ✅ M3（列表/详情/创建/部署/键值分析等）
├── AppOpsApiController.java            ✅ M3 运维（含拓扑诊断、故障诊断、滚动重启）
├── AppTopologyMigrateApiController.java ✅ M3 集群拓扑迁移
├── ExternalRedisApiController.java     ✅ M4
├── DiagnosticsApiController.java       ✅ M5
├── RedisConfigApiController.java       ✅ M6
├── UserManageApiController.java        ✅ M7
├── InstanceOpsApiController.java       ✅ M8（配置检查 + 命令检查）
├── InstanceApiController.java          ✅ 实例详情（含命令曲线）
├── MigrateApiController.java           ✅ M9 数据迁移
├── MachineApiController.java           ✅ M10
├── InstanceAlertApiController.java     ✅ M11
├── SystemConfigApiController.java      ✅ M12
├── ResourceApiController.java          ✅ M13
├── TaskApiController.java              ✅ M14
├── QuartzApiController.java            ✅ M15
├── WikiApiController.java              ✅ 客户端接入文档
├── AppAuditApiController.java          ⛔ 审批（本期不做）
└── portal/*                            🔲 应用端（未开始）
```

### 4.2 响应格式（统一）

```json
{ "code": 0, "message": "ok", "data": {} }
```

分页：`{ "list": [], "total": 0, "page": 1, "pageSize": 20 }`

### 4.3 迁移 Controller 策略

| 类型 | 做法 |
|------|------|
| **Hybrid（已有 JSON）** | 新 ApiController 调用同一 Service，JSON 字段与旧接口保持一致，Vue 先对齐旧数据结构 |
| **JSP-only** | 读原 Controller 的 model 组装逻辑，抽成 Service 方法 + DTO |
| **禁止** | 业务逻辑重写；只换输出格式 |

---

## 五、前端工程规范

### 5.1 目录（全量目标）

```
src/
├── api/cachecloud/
│   ├── dashboard.ts
│   ├── app.ts
│   ├── audit.ts
│   ├── instance.ts
│   ├── machine.ts
│   ├── diagnostics.ts
│   ├── migrate.ts
│   ├── portal/app.ts
│   ├── portal/job.ts
│   └── types/
├── pages/
│   ├── cachecloud/          # 管理端
│   │   ├── dashboard/
│   │   ├── app/
│   │   ├── audit/
│   │   ├── stats/
│   │   ├── diagnostics/
│   │   ├── machine/
│   │   ├── migrate/
│   │   └── ...
│   └── portal/              # 应用端
│       ├── app/
│       ├── jobs/
│       └── instance/
├── components/cachecloud/   # 跨页复用
│   ├── ChartCard.vue        # 替代 Highcharts
│   ├── StatCard.vue         # rp-stat-card
│   ├── AppTabLayout.vue     # 应用详情 Tab 壳
│   └── InstanceTabLayout.vue
└── router/
    ├── manage-routes.ts     # 管理端（替换现有 cachecloud-routes）
    └── portal-routes.ts     # 应用端
```

### 5.2 每个页面交付标准（Definition of Done）

- [x] Vue 页面功能与对应 JSP **行为一致**（列表筛选、分页、表单校验、操作按钮）
- [ ] 对应 `/api/v1` 接口联调通过（**统一联调进行中**）
- [x] 权限：管理员会话与旧版 interceptor 行为一致
- [ ] 旧 JSP URL 在 Nginx 配置 301 到新路由（收尾阶段）
- [x] 从 `LegacyPlaceholder` 移除，路由指向新页面

---

## 六、执行顺序（全量，按依赖排序）

```
阶段 0 基础          ✅ 登录、布局、主题、菜单骨架
阶段 1 管理核心      ✅ M1 全局统计 → M3 集群全链路 → M8 实例运维 → M2 server统计
阶段 2 管理资源      ✅ M10 机器 → M4 节点 → M7 用户
阶段 3 管理工具      ✅ M5 数据管理 → M6 模版 → M11 报警 → M12 系统配置 → M13 资源 → M14 任务 → M15 调度
阶段 4 应用端        🔲 P1 我的应用 → P2 我的工单（审批排除）→ P3 实例详情 → P4 键值分析
阶段 5 复杂流程      ✅ M9 数据迁移 + 集群拓扑迁移
阶段 6 长尾          🔲 客户端统计、服务器监控、故障列表、Wiki 独立页、AI 助手
阶段 7 收尾          🔲 删除 JSP 转发、Nginx 301、只保留 /api/v1
```

**阶段 1–4 覆盖 90% 日常运维；阶段 5–6 覆盖剩余 JSP。**

---

## 七、工作量估算（人天，供排期）

| 阶段 | 模块 | API 数（约） | Vue 页（约） | 人天（约） |
|------|------|-------------|-------------|-----------|
| 1 | M1+M3 核心 | 45 | 35 | 25–35 |
| 1 | M2+M8 | 12 | 8 | 8–12 |
| 2 | M4+M7+M10 | 25 | 18 | 15–20 |
| 3 | M5–M6+M11–M15 | 40 | 30 | 25–30 |
| 4 | Portal P1–P4 | 50 | 40 | 30–40 |
| 5 | M9 迁移 | 20 | 12 | 15–20 |
| 6 | 长尾 | 15 | 12 | 10–15 |
| **合计** | | **~207** | **~155** | **~128–172 人天** |

按 2 名全职前后端各 1：**约 3–4 个月**完成全量（含联调与回归）。

---

## 八、当前进度追踪

### 管理端 15 项菜单

| 模块 | 名称 | 后端 API | 前端页面 | 状态 | 备注 |
|------|------|----------|----------|------|------|
| 0 | 认证 | ✅ | `login` | ✅ | login / logout / me |
| M1 | 全局统计 | ✅ | `dashboard/index` | ✅ | 概览 + 图表 + 异常 |
| M2 | server统计 | ✅ | `stats/server` | ✅ | 内存/碎片率/拓扑诊断 |
| M3 | 集群管理 | ✅ | `app/list` `detail` `create` `deploy` `ops` | ✅ | 含拓扑迁移、滚动重启 |
| M4 | 节点管理 | ✅ | `external/redis/*` | ✅ | 列表 + 纳管 |
| M5 | 数据管理 | ✅ | `diagnostics` 9 Tab | ✅ | 诊断工具全套 |
| M6 | 模版配置 | ✅ | `redis-config` | ✅ | 增删改查 |
| M7 | 用户管理 | ✅ | `user/list` | ✅ | |
| M8 | 实例运维 | ✅ | `instance/ops` | ✅ | 配置检查 + 命令检查 |
| M9 | 数据迁移 | ✅ | `migrate` | ✅ | 批量迁移向导 |
| M10 | 机器管理 | ✅ | `machine` | ✅ | 机器 + 机房 |
| M11 | 报警配置 | ✅ | `instance-alert` | ✅ | |
| M12 | 系统配置 | ✅ | `config` | ✅ | |
| M13 | 资源管理 | ✅ | `resource` 5 Tab | ✅ | 含脚本编辑/编译推送 |
| M14 | 任务管理 | ✅ | `task/list` | ✅ | 含任务流详情 |
| M15 | 调度任务 | ✅ | `quartz/list` | ✅ | |

### 管理端扩展页（非菜单）

| 功能 | 路由 | 状态 |
|------|------|------|
| 应用部署向导 | `/app/deploy/:appId` | ✅ |
| 集群拓扑迁移 | `/app/topology-migrate/:appId` | ✅ 8 步向导 |
| 实例详情 | `/instance/detail/:instanceId` | ✅ 含命令曲线 Tab |

### 近期完成（2026-07）

- 拓扑诊断：`TopologyExamDto` + `TopologyExamPanel`（替代 JSON 原文）
- 实例命令曲线：`command-analysis` + `command-chart` API + ECharts Tab
- 滚动重启 / 更新配置重启：`AppScrollRestartApiService`
- 实例命令检查：`instance-ops/command-checks`
- 集群拓扑迁移：独立 `AppTopologyMigrateApiController`

### 仍未完成

| 类别 | 项 | 优先级 |
|------|-----|--------|
| 明确排除 | 工单审批 ~23 JSP | — |
| 应用端 | Portal `/admin/*` 整套路由 | P4 |
| 长尾 | 故障列表、客户端异常、服务器监控、AI 助手 | P5 |
| 收尾 | JSP `@Deprecated`、Nginx 301、下线 238 JSP | P7 |
| 联调 | 各模块边界场景通测 | P0 |

> 每完成一个模块：更新上表；旧 JSP 标记 `@Deprecated`；确认无前端 `/manage/` 硬编码跳转。

---

## 九、本地开发

```bash
# 后端（profile: local，端口 8080）
cd cachecloud-web
MAVEN_OPTS=-Xmx1024m mvn spring-boot:run \
  -Dspring-boot.run.profiles=local \
  -Dspring-boot.run.jvmArguments="-Dserver.port=8080"

# 前端（端口 3333，代理 /api → 8080）
cd cachecloud-ui && npm install --legacy-peer-deps && npm run dev
```

- 前端：http://localhost:3333  
- 后端 API：http://localhost:8080/api/v1  
- 账号：`admin` / `admin%TGB7ygv`

---

## 十、下一步

**P0 — 统一联调**

1. 应用运维：拓扑诊断、滚动重启、集群/哨兵操作、拓扑迁移 8 步
2. 实例运维：配置检查、命令检查（bgsave/bgrewriteaof）
3. 实例详情：命令曲线、慢查询、只读命令
4. 数据迁移 / 应用部署：创建 → 部署 → 验证实例上线

**P4 — 应用端 Portal（可选，后期）**

- `/admin/app/*` 我的应用 + 实例详情（可复用 manage 组件 `mode=portal`）
- 不含工单审批

**P7 — 收尾**

- 238 个 JSP 标记 `@Deprecated` → Nginx 301 → 物理删除
- 下线 Vite 对 `/manage` 的兼容代理
