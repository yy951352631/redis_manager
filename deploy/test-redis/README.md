# 纳管测试用 Redis

供 CacheCloud 平台「节点管理 → 新增纳管」测试用的两套集群。

| 拓扑 | 版本 | 规模 | 宿主机端口 |
|------|------|------|-----------|
| Cluster | 6.2.13 | 3 主 3 从 | 7001-7006 |
| Sentinel | 5.0.14 | 1 主 2 从 + 3 哨兵 | 7101-7103 / 27101-27103 |

两套均**无密码**，纳管时密码栏留空。

## 启停

```bash
docker compose -f deploy/test-redis/compose.yaml up -d
# 让平台后端能连到这些节点（只需执行一次，重建后端容器后要重做）
docker network connect redis-test cachecloud-web
```

```bash
# 停止但保留数据
docker compose -f deploy/test-redis/compose.yaml down
# 连数据一起清掉
docker compose -f deploy/test-redis/compose.yaml down -v
```

## 纳管时填的「节点详情」

固定子网 172.30.0.0/16 + 静态 IP，重启后不变，直接复制粘贴。

**Cluster（集群类型选 Redis-cluster）**

```
172.30.0.11:6379
172.30.0.12:6379
172.30.0.13:6379
172.30.0.14:6379
172.30.0.15:6379
172.30.0.16:6379
```

**Sentinel（集群类型选 Redis-sentinel，master name 为 `mymaster`）**

```
172.30.0.21:6379:0
172.30.0.22:6379:0
172.30.0.23:6379:0
172.30.0.31:26379:mymaster
172.30.0.32:26379:mymaster
172.30.0.33:26379:mymaster
```

## 从宿主机连

```bash
redis-cli -p 7001            # cluster 节点 1（单节点命令可用）
redis-cli -p 7101            # sentinel master
redis-cli -p 27101 -h 127.0.0.1 sentinel master mymaster
```

注意：cluster 节点用 `cluster-announce-ip` 宣告的是容器 IP（平台后端从容器网络访问，必须如此），
所以宿主机上 `redis-cli -c` 的 MOVED 跳转会指向 172.30.0.x 而连不通。宿主机只适合做单节点排查，
需要完整集群语义时进容器执行：

```bash
docker exec -it redis-cluster-1 redis-cli -c -p 6379
```

## 持续流量（workload）

`workload.py` 持续、低速地向两套集群发送贴近真实业务的命令流，供平台的监控图表、
键值分析、风险评估等功能有真实数据可用。默认不随集群一起启动。

```bash
docker compose -f deploy/test-redis/compose.yaml --profile workload up -d
docker logs -f redis-workload          # 每 60s 一行统计
docker compose -f deploy/test-redis/compose.yaml stop workload
```

**它和 redis-benchmark 的区别**：benchmark 压的是单命令极限 QPS，产出一条平直的高压曲线，
平台上的命中率 / TTL 分布 / 大 key / 内存增长率 / 集群倾斜 / 命令耗时基线全都拿不到有效输入。
这个脚本反过来，刻意压到 **6 QPS** 左右，靠场景组合造出「形状」：

| 场景 | 数据类型 | 喂给平台的维度 |
|------|---------|--------------|
| `session_read/write` | STRING（带 TTL） | 命中率、TTL 分布、expired_keys |
| `profile_hash` | HASH（不过期） | 「key 未设置过期」 |
| `queue_list` | LIST（LTRIM 截断） | 队列长度、LRANGE 耗时 |
| `online_set` | SET | 集合基数 |
| `rank_zset` | ZSET | ZADD/ZREVRANGE 耗时 |
| `counter_incr` | STRING 计数 | 写放大、INCR 耗时 |
| `bitmap_sign` | BITMAP | SETBIT/BITCOUNT |
| `hll_uv` | HyperLogLog | PFADD/PFCOUNT |
| `geo_driver` | GEO（底层 zset） | GEOADD/GEORADIUS |
| `stream_event` | STREAM | XADD/XRANGE |
| `archive_growth` | ZSET（只增不删） | **内存增长率**——其余场景要么带 TTL 要么被截断，内存是平的 |
| `slow_command` | 全量扫描 | 慢日志、危险命令（默认 0.4% 频率） |

几个刻意为之的设计：

- **访问倾斜**：80% 的访问落在 10% 的热点 key 上。均匀随机取 key 会让稳态命中率停在个位数，
  那不是「缓存」是「带 TTL 的数据库」；倾斜后命中率会收敛到 85% 左右。
- **强制未命中**：固定 10% 的读打在从未写过的 id 上，命中率才不会是恒定的 100%。
- **slot 倾斜**：15% 的 key 带 `{hot}` hash tag 钉在同一个 slot，用来验证「集群倾斜」判定。
  实测三个主节点 key 数 288 / 294 / **429**。
- **大 key 播种**：启动时写一次 300KB string、6 万字段 hash、6 万元素 list，之后不再变动
  （持续写大 key 会让内存曲线被它主导）。
- **QPS 正弦波动**：默认 1 小时一个周期、±60%，监控图上是波形而不是直线。
- **读写分离**：sentinel 侧读走从节点，cluster 侧开 replica 读，否则平台上从节点永远零流量。

常用参数（`command` 里追加即可）：

```bash
--qps 20                # 提高速率
--wave 0                # 关掉波动，恒定 QPS
--key-space 20000       # 扩大 key 基数（内存涨得更快）
--miss-ratio 0.4        # 压低命中率，验证告警
--slow-ratio 0.05       # 大量慢查询，验证慢日志与危险命令
--targets sentinel      # 只压一套
--duration 3600         # 跑 1 小时后退出
--max-ops 500           # 冒烟验证
```

脚本只能跑在 `redis-test` 网络内：cluster 用 `cluster-announce-ip` 宣告的是 172.30.0.x，
从宿主机连过去 MOVED 跳转会指向不可达地址。

故障切换演练期间连接必然中断，脚本不会退出——单次命令失败只计数并继续，
那段时间恰恰是最该观察的。

## 演练故障切换

```bash
docker stop redis-sentinel-master        # 约 5s 后哨兵选出新主
docker exec redis-sentinel-1 redis-cli -p 26379 sentinel master mymaster
docker start redis-sentinel-master       # 原主回来后作为从节点加入
```
