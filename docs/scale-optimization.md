# CacheCloud 大规模治理优化清单

> **目标规模（已确认）：约 500 集群 / 约 3000 节点**  
> 现状更适合「几十～一两百集群 / 几百～一两千节点」；按该目标，下列项为必做或强烈建议。

### 目标负载粗算（改造前现状参数）

| 项 | 估算（3000 节点） |
|----|-------------------|
| 每分钟 `INFO` | ~3000 次（约 50 次/秒） |
| 每 10 秒探活一轮 | ~300 次/秒 |
| 在线集群全量聚合 | ~500 次 `getAppDetail` 量级（大盘 / 服务端统计） |
| MySQL 分钟写入 | ~3000 行/分钟量级（`standard_statistics`） |

**对该目标的判断：**

- 仅靠调大线程池 / 连接池：**不够**，列表和大盘会先超时，采集会积压。
- 做完「去全量列表 + 分片降频 + 连接/线程池」：可冲击 **3000 节点**，但采集与 API 同进程时仍容易互相拖累。
- 要 **稳定** 跑 500 / 3000：建议把 **独立 collector（优化点 1）** 和 **时序外置或至少减负（优化点 6）** 纳入必做，而不是放到「5000 节点再说」。

---

## 一、现状瓶颈摘要

| 优先级 | 瓶颈 | 说明 |
|--------|------|------|
| P0 | 监控采集 | 每节点约 1 分钟 `INFO` + 约 10 秒探活，全在 `cachecloud-web` 内执行 |
| P0 | 全量扫描 | `getAllInsts()` / `getOnlineApps()` 被多处定时任务与大盘接口使用 |
| P1 | 聚合接口 | `getOnlineAppDetails()` 对在线集群逐个拉详情 |
| P1 | 假分页 | 机器列表、服务端统计页一次拉全量，前端再 `slice` |
| P2 | 连接与线程池 | Hikari 默认约 50；采集/探活线程池在数千节点下易排队、拒绝 |
| P2 | 时序落库 | `standard_statistics` 写 MySQL，仅保留约 10 分钟，写放大明显 |

---

## 二、优化点清单

### 1. 采集与 API 进程拆分

| 项 | 内容 |
|----|------|
| **问题** | Quartz、探活、`INFO` 采集、告警扫描与 HTTP API 同进程，互相抢 CPU / 连接 / 线程 |
| **改造** | 拆出独立 collector（或独立部署的采集进程）；`cachecloud-web` 只做管控与查询 |
| **涉及** | `spring-quartz.xml`、`BrevitySchedulerImpl`、`HostInspectHandler`、各类 `*Job` |
| **收益** | API 不被采集拖垮；采集可单独扩容 |
| **优先级** | P0 |

---

### 2. 全量实例扫描改为分片 / 游标

| 项 | 内容 |
|----|------|
| **问题** | 多处一次加载全部实例：`instanceDao.getAllInsts()` |
| **改造** | 按 `app_id` / 机器 IP / `id` 区间分片；多实例各处理一段；禁止单次全表进内存 |
| **涉及** | `AbstractInspectHandler`、`HostInspectHandler`、`InstanceAlertConfigServiceImpl`、`AppClientServiceImpl`、`DashboardService`、`TotalManageController` |
| **收益** | 内存与单次任务耗时随节点数线性可控 |
| **优先级** | P0 |

---

### 3. 探活降频与分级策略

| 项 | 内容 |
|----|------|
| **问题** | `hostInspectorTrigger` 约每 10 秒对活跃节点做 `isRun()`；5000 节点约等于每秒数百次探活 |
| **改造** | 默认 30s～60s；正常节点降频，异常 / 刚变更节点加密；探活结果写缓存，告警读缓存 |
| **涉及** | `HostInspectHandler`、`InstanceRunInspector`、`spring-inspector.xml` / Quartz 配置 |
| **收益** | 显著降低 Redis 连接与线程池压力 |
| **优先级** | P0 |

---

### 4. Redis INFO 采集降频与分级

| 项 | 内容 |
|----|------|
| **问题** | `BrevityScheduler` 对节点约每分钟 `INFO ALL`，写 `standard_statistics` + `instance_statistics` |
| **改造** | 核心集群 1 分钟，普通 2～5 分钟；可配置；采集批次与并发可配 |
| **涉及** | `BrevitySchedulerImpl`、`RedisCenterImpl.collectRedisInfo`、`brevity_schedule_resources` |
| **收益** | 采集量可按业务重要性裁剪 |
| **优先级** | P0 |

---

### 5. 告警扫描与采集解耦

| 项 | 内容 |
|----|------|
| **问题** | `monitorLastMinuteAllInstanceInfo()` 每分钟 `getAllInsts()` 再嵌套配置扫描 |
| **改造** | 告警只消费已落库的最新指标 / 时序查询结果，不再全量直连 Redis 扫一遍 |
| **涉及** | `InstanceAlertConfigServiceImpl`、`instanceAlertValueTrigger` |
| **收益** | 去掉与采集重复的一轮全站探测 |
| **优先级** | P0 |

---

### 6. 分钟级时序外置

| 项 | 内容 |
|----|------|
| **问题** | 分钟指标写 MySQL `standard_statistics`，节点多时写放大；清理任务仅保留约 10 分钟 |
| **改造** | 分钟 / 秒级指标进 Prometheus、VictoriaMetrics 或 Influx；MySQL 只保留元数据 + 最新快照（`instance_statistics`） |
| **涉及** | `RedisCenterImpl.collectRedisInfo`、`CleanupMinuteDimensionalityJob`、`StandardStatsDao`；代码注释亦提到需时序库 |
| **收益** | MySQL 压力下降，历史曲线可长期保留 |
| **优先级** | P0（冲 5000 节点几乎必需） |

---

### 7. 消除 `getOnlineAppDetails()` 全站聚合

| 项 | 内容 |
|----|------|
| **问题** | 对每个在线 app 调用 `getAppDetail`，复杂度约 O(集群数 × 实例查询) |
| **改造** | 批量 SQL 聚合（实例数、内存、命中率等）；或维护 `app_statistics` 预聚合表，定时刷新；接口侧强缓存 |
| **涉及** | `AppStatsCenterImpl.getOnlineAppDetails`、`DashboardService`、`AppStatApiService`、`AppStatController`、`TotalManageController` |
| **收益** | 大盘 / 服务端统计首屏从「分钟级」降到「秒级」 |
| **优先级** | P1 |

---

### 8. Dashboard / 服务端统计改为服务端分页与筛选

| 项 | 内容 |
|----|------|
| **问题** | 一次加载全部在线集群详情；前端再过滤分页 |
| **改造** | API 支持 `pageNo` / `pageSize` / 关键字 / 状态筛选；汇总数字走独立轻量接口 |
| **涉及** | `AppStatApiService.listServerStats`、`cachecloud-ui/.../stats/server/index.vue`、`DashboardService` |
| **收益** | 几百集群时页面可打开、可搜索 |
| **优先级** | P1 |

---

### 9. 机器列表服务端分页

| 项 | 内容 |
|----|------|
| **问题** | `MachineApiService.listMachines` 返回全量；前端 `machines.slice(...)` 假分页；后端还有 `parallelStream` 拉机器统计 |
| **改造** | 服务端分页 + 条件过滤；列表字段与详情字段分离，避免列表接口算重统计 |
| **涉及** | `MachineApiController`、`MachineApiService`、`MachineCenterImpl`、`cachecloud-ui/.../machine/index.vue` |
| **收益** | 机器规模上来后列表不再拖垮浏览器与 API |
| **优先级** | P1 |

---

### 10. 应用列表去掉 N+1 查询

| 项 | 内容 |
|----|------|
| **问题** | `AppManageApiService.listApps` 虽有分页，但每行再查 `getInstListByAppId` + `getAppDetail` |
| **改造** | 当前页 `app_id` 批量查实例数 / 内存 / 状态；一次或少数几次 SQL 填齐列表字段 |
| **涉及** | `AppManageApiService`、`AppDao` / `InstanceDao`、`AppStatsCenter` |
| **收益** | 列表页稳定，pageSize 放大也不易超时 |
| **优先级** | P1 |

---

### 11. 非管理员应用列表补充分页

| 项 | 内容 |
|----|------|
| **问题** | 非管理员路径 `getAppDescList(userId)` 无 SQL `LIMIT`，用户关联集群多时一次返回过多 |
| **改造** | 与管理员列表统一服务端分页 |
| **涉及** | `AppServiceImpl.getAppDescList`、`AppDao` |
| **收益** | 多租户 / 大客户账号下列表可控 |
| **优先级** | P1 |

---

### 12. 数据库连接池与读写分离

| 项 | 内容 |
|----|------|
| **问题** | `cachecloud.primary.maxPoolSize` 默认约 50，采集 + API + N+1 并发易耗尽 |
| **改造** | API 与采集分连接池（或分库账号）；评估只读副本给大盘 / 统计查询；监控连接等待与活跃数 |
| **涉及** | `application-*.yml`、`spring-mybatis.xml`、Hikari 配置 |
| **收益** | 降低「采集把 API 连接打满」的概率 |
| **优先级** | P2 |

---

### 13. 线程池与队列可观测、可配置

| 项 | 内容 |
|----|------|
| **问题** | `BREVITY_SCHEDULER`（10～100）、`DEFAULT_ASYNC`（256）等写死或偏固定；拒绝策略仅计数 |
| **改造** | 按节点规模配置核心/最大线程与队列；暴露队列深度、拒绝次数、任务耗时；积压告警 |
| **涉及** | `AsyncThreadPoolFactory`、`BrevitySchedulerImpl`、Inspector 相关线程池 |
| **收益** | 规模上来时能先看见问题，再调参，而不是静默丢任务 |
| **优先级** | P2 |

---

### 14. Quartz 多实例分片锁

| 项 | 内容 |
|----|------|
| **问题** | 可多实例部署，但若任务未真正分片，会重复打同一批节点或争抢 |
| **改造** | 采集 / 探活 / 告警任务明确分片键 + 分布式锁（或基于 `brevity_schedule_resources` 的租约）；保证一节点同一时刻只被一个 worker 采集 |
| **涉及** | `spring-quartz.xml`（JDBC cluster）、`BrevitySchedulerImpl`、各类 Job |
| **收益** | 水平扩展采集而不翻倍打 Redis |
| **优先级** | P2 |

---

### 15. 单集群拓扑 / 运维探测收敛

| 项 | 内容 |
|----|------|
| **问题** | 打开拓扑时可能对每个在线节点做 `isMaster` / `getMaster` 等实时探测 |
| **改造** | 优先用已采集角色信息；实时探测限流、超时、可开关；大集群（如 50+ 节点）异步加载 |
| **涉及** | `AppDetailTabApiService.getTopology`、`AppOpsApiService`、实例运维页 |
| **收益** | 大集群运维页打开更快、更稳 |
| **优先级** | P2 |

---

### 16. 批量运维任务化

| 项 | 内容 |
|----|------|
| **问题** | 规模上来后，滚动重启、配置下发、扩缩容若同步打节点，易超时、难追踪 |
| **改造** | 统一任务队列：进度、重试、暂停、失败节点列表；并发度可配 |
| **涉及** | 现有 Task / Restart / Migrate 相关 API 与执行器（`TASK_EXECUTE` 线程池等） |
| **收益** | 几千节点场景下运维可预期 |
| **优先级** | P3 |

---

### 17. 多租户 / 业务线视图隔离

| 项 | 内容 |
|----|------|
| **问题** | 管理员视角易一次看到全站；几百集群时信息过载，也放大聚合接口压力 |
| **改造** | 按业务线 / 项目组默认过滤；全局大盘改为汇总指标，明细必须带范围 |
| **涉及** | 应用列表、Dashboard、权限模型 |
| **收益** | 降低误操作面，顺带减轻全站查询 |
| **优先级** | P3 |

---

### 18. 平台自身可观测

| 项 | 内容 |
|----|------|
| **问题** | 缺少「采集是否跟上」的一等公民指标 |
| **改造** | 监控：采集延迟、漏采率、探活失败率、线程池拒绝、DB 慢查询、API P99 |
| **涉及** | 采集链路埋点、运维看板（可复用现有告警通道） |
| **收益** | 扩容前后能量化效果，出问题可定位 |
| **优先级** | P3 |

---

## 三、建议落地顺序（对齐 500 集群 / 3000 节点）

| 阶段 | 周期（参考） | 优化点 | 目标 |
|------|--------------|--------|------|
| 第 1 步 | 1～2 周 | 7、8、9、10、11、12 | **必做**：500 集群下列表 / 大盘可开；连接池不被打满 |
| 第 2 步 | 2～4 周 | 2、3、4、5、13、14 | **必做**：3000 节点采集 / 探活可跟上（分片 + 降频） |
| 第 3 步 | 1～2 月 | 1、6 | **强烈建议（稳态必做）**：API 与采集隔离；分钟指标减负或外置 |
| 第 4 步 | 持续 | 15、16、17、18 | 运维体验、多租户、平台自监控 |

### 针对 500 / 3000 的参数建议（第 2 步可先落地）

| 参数方向 | 建议 |
|----------|------|
| 探活周期 | 10s → **30s～60s**（异常节点可加密） |
| INFO 周期 | 全量 1 分钟 → 核心 1 分钟 / 普通 **2～3 分钟** |
| 采集并发 | 按分片扩；单机不宜无脑拉满打 Redis |
| Hikari | API 与采集分池；API 池建议明显高于现状 50（按压测定） |
| 大盘 | 禁止每次请求遍历 500 集群实时 `getAppDetail`；预聚合 + 缓存 |

---

## 四、规模与改造对应关系

| 目标规模 | 最低需要完成的优化 |
|----------|-------------------|
| ~200 集群 / ~2000 节点 | 第 1 步 + 第 2 步 |
| **~500 集群 / ~3000 节点（当前目标）** | 第 1～2 步 **必做**；第 3 步（**独立 collector + 时序减负/外置**）为稳态必做 |
| ~500 集群 / ~5000 节点 | 上述全部做实，采集水平扩展 + 时序外置不可省 |

---

## 五、关键代码索引（便于开工）

| 模块 | 路径 / 类 |
|------|-----------|
| 采集调度 | `BrevitySchedulerImpl`、`AsyncThreadPoolFactory` |
| 探活 | `HostInspectHandler`、`AbstractInspectHandler` |
| 告警扫描 | `InstanceAlertConfigServiceImpl` |
| 全站聚合 | `AppStatsCenterImpl#getOnlineAppDetails` |
| 大盘 | `DashboardService`、`AppStatApiService` |
| 应用列表 | `AppManageApiService#listApps` |
| 机器列表 | `MachineApiService#listMachines` |
| 前端假分页 | `cachecloud-ui/src/pages/cachecloud/machine/index.vue`、`.../stats/server/index.vue` |
| 定时任务 | `cachecloud-web/src/main/resources/spring/spring-quartz.xml` |
| 连接池 | `application-online.yml` → `cachecloud.primary.maxPoolSize` |

---

## 六、验收建议（对齐 500 / 3000）

1. **采集**：3000 节点下 INFO 任务积压持续为 0 或可接受；漏采率 < 1%。  
2. **探活**：在设定周期（如 30～60s）内能覆盖全部活跃节点，无长时间线程池拒绝。  
3. **列表**：应用 / 机器 / 服务端统计接口 P99 < 2s；响应体随 pageSize 增长，不随 500 集群 / 全站机器数线性膨胀。  
4. **大盘**：500 集群首屏不依赖「遍历全部集群实时 `getAppDetail`」。  
5. **DB**：MySQL 连接等待可控；分钟写入水位可接受（已外置时序则看时序库）。  
6. **隔离**：采集高峰时，管控 API（应用列表、单集群运维）仍可用。
