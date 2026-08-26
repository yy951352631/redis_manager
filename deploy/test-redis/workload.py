#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
测试 Redis 流量发生器：持续、低速地模拟真实业务命令流。

为什么不直接用 redis-benchmark：benchmark 压的是单一命令的极限 QPS，产出一条平直的高压曲线，
CacheCloud 上几乎所有维度（命中率、TTL 分布、大 key、内存增长率、集群倾斜、命令耗时基线）
都拿不到有意义的输入。这里反过来——刻意压低速率，用多种业务场景混合出「形状」：

  * 12 类场景覆盖 string / hash / list / set / zset / bitmap / hyperloglog / geo / stream
  * 读多写少，并固定掺入约 10% 的未命中，命中率才不会恒等于 100%
  * 一部分 key 带 TTL 且长短不一，TTL 分布与 expired_keys 才有数据；另一部分刻意不设过期
  * 归档场景只增不删，给「内存增长率」评估提供可拟合的斜率
  * 按 hash tag 把一部分 key 钉在同一个 slot 上，制造可被「集群倾斜」检出的不均衡
  * QPS 按正弦波动，监控图上是波形而不是直线

必须跑在 redis-test 容器网络里：cluster 节点 cluster-announce-ip 宣告的是 172.30.0.x，
从宿主机连过去 MOVED 跳转会指向不可达地址。
"""

import argparse
import math
import os
import random
import signal
import sys
import threading
import time
from collections import Counter

try:
    import redis
    from redis.cluster import ClusterNode, RedisCluster
    from redis.sentinel import Sentinel
except ImportError:  # pragma: no cover
    sys.exit("需要 redis-py，请先执行：pip install 'redis>=5,<6'")

CLUSTER_NODES = [("172.30.0.11", 6379), ("172.30.0.12", 6379), ("172.30.0.13", 6379),
                 ("172.30.0.14", 6379), ("172.30.0.15", 6379), ("172.30.0.16", 6379)]
SENTINEL_NODES = [("172.30.0.31", 26379), ("172.30.0.32", 26379), ("172.30.0.33", 26379)]
MASTER_NAME = "mymaster"

CITIES = [("beijing", 116.40, 39.90), ("shanghai", 121.47, 31.23),
          ("shenzhen", 114.06, 22.55), ("chengdu", 104.07, 30.66)]
METRICS = ["pv", "order", "login", "pay", "share"]
TOPICS = ["sms", "push", "mail", "audit"]
BOARDS = ["daily", "weekly", "wealth"]

# 权重之和不必为 100，按相对值抽样。读远多于写，贴近缓存的真实读写比。
SCENARIOS = [
    ("session_read", 28), ("session_write", 10), ("profile_hash", 12),
    ("queue_list", 10), ("online_set", 8), ("rank_zset", 10),
    ("counter_incr", 8), ("bitmap_sign", 3), ("hll_uv", 3),
    ("geo_driver", 3), ("stream_event", 3), ("archive_growth", 2),
]

ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"


def payload(rnd, size):
    """随机串而不是重复字符——重复字符会被 RDB 压缩掉，键值大小分布会失真。"""
    return "".join(rnd.choice(ALPHABET) for _ in range(size))


def value_size(rnd):
    """长尾分布：多数是几百字节的小值，偶尔几 KB，罕见几十 KB。"""
    roll = rnd.random()
    if roll < 0.80:
        return rnd.randint(80, 600)
    if roll < 0.97:
        return rnd.randint(1024, 8192)
    return rnd.randint(32 * 1024, 96 * 1024)


class Traffic:
    """一套集群的流量。writer/reader 分开，sentinel 下可以把读打到从节点。"""

    def __init__(self, name, writer, reader, cfg, stop):
        self.name = name
        self.writer = writer
        self.reader = reader
        self.cfg = cfg
        self.stop = stop
        self.rnd = random.Random(hash(name) & 0xFFFF)
        self.ops = Counter()
        self.hits = 0
        self.misses = 0
        self.errors = 0
        self.errors_by = Counter()
        self.last_error = ""
        self.started = time.time()

    # ---------------------------------------------------------------- key 构造
    def key(self, prefix, ident):
        """
        按 ident 决定是否加 hash tag：必须是确定性的，否则同一个 id 写进去、读的时候
        算成另一个 key，命中率会被压成 0，测出来的东西就没意义了。
        """
        if self.cfg.skew_percent > 0 and ident % 100 < self.cfg.skew_percent:
            return "{hot}:%s:%d" % (prefix, ident)
        return "%s:%d" % (prefix, ident)

    def uid(self):
        """
        真实缓存的命中率能到 90%+，靠的是访问高度倾斜而不是把整个 key 空间焐热。
        均匀随机取 id 会让稳态命中率停在个位数——那不是「缓存」，是「带 TTL 的数据库」。
        """
        if self.rnd.random() < self.cfg.hot_ratio:
            hot = max(1, self.cfg.key_space * self.cfg.hot_percent // 100)
            return self.rnd.randrange(hot)
        return self.rnd.randrange(self.cfg.key_space)

    def miss_uid(self):
        """越界 id 从来没被写过，用来稳定地制造未命中。"""
        return self.cfg.key_space + self.rnd.randrange(self.cfg.key_space)

    def today(self):
        return time.strftime("%Y%m%d")

    # ---------------------------------------------------------------- 场景
    def session_read(self):
        miss = self.rnd.random() < self.cfg.miss_ratio
        uid = self.miss_uid() if miss else self.uid()
        got = self.reader.get(self.key("session", uid))
        if got is None:
            self.misses += 1
            if not miss:
                # 真实的缓存未命中：回源后回填，这正是 TTL 到期后的典型行为
                self.writer.setex(self.key("session", uid),
                                  self.rnd.choice([300, 1800, 7200, 7200]),
                                  payload(self.rnd, value_size(self.rnd)))
        else:
            self.hits += 1

    def session_write(self):
        uid = self.uid()
        # 短 TTL 占一部分，expired_keys 才会持续增长
        ttl = self.rnd.choice([60, 300, 1800, 1800, 7200, 7200, 86400])
        self.writer.setex(self.key("session", uid), ttl, payload(self.rnd, value_size(self.rnd)))

    def profile_hash(self):
        """用户资料：永不过期，给「key 未设置过期」这一维度留下真实样本。"""
        uid = self.uid()
        k = self.key("user:profile", uid)
        roll = self.rnd.random()
        if roll < 0.45:
            self.reader.hgetall(k)
        elif roll < 0.65:
            self.reader.hget(k, self.rnd.choice(["name", "city", "level", "phone"]))
        elif roll < 0.90:
            self.writer.hset(k, mapping={
                "name": payload(self.rnd, 12),
                "city": self.rnd.choice(CITIES)[0],
                "level": self.rnd.randint(1, 9),
                "phone": payload(self.rnd, 11),
                "bio": payload(self.rnd, self.rnd.randint(40, 400)),
            })
        else:
            self.writer.hincrby(k, "login_count", 1)

    def queue_list(self):
        k = "queue:%s" % self.rnd.choice(TOPICS)
        roll = self.rnd.random()
        if roll < 0.40:
            self.writer.lpush(k, payload(self.rnd, self.rnd.randint(60, 400)))
            # 不 LTRIM 的话队列会无限涨，几天后整个实例的内存都被它吃掉
            self.writer.ltrim(k, 0, self.cfg.queue_max - 1)
        elif roll < 0.75:
            self.writer.rpop(k)
        elif roll < 0.90:
            self.reader.llen(k)
        else:
            self.reader.lrange(k, 0, 19)

    def online_set(self):
        k = "online:%s:%s" % (self.today(), self.rnd.randrange(8))
        roll = self.rnd.random()
        if roll < 0.35:
            self.writer.sadd(k, self.uid())
            self.writer.expire(k, 3 * 86400)
        elif roll < 0.55:
            self.writer.srem(k, self.uid())
        elif roll < 0.80:
            self.reader.sismember(k, self.uid())
        elif roll < 0.92:
            self.reader.scard(k)
        else:
            self.reader.srandmember(k, 5)

    def rank_zset(self):
        k = "rank:%s:%s" % (self.rnd.choice(BOARDS), self.today())
        roll = self.rnd.random()
        if roll < 0.30:
            self.writer.zadd(k, {"u%d" % self.uid(): self.rnd.random() * 10000})
            self.writer.expire(k, 7 * 86400)
        elif roll < 0.50:
            self.writer.zincrby(k, self.rnd.randint(1, 50), "u%d" % self.uid())
        elif roll < 0.75:
            self.reader.zrevrange(k, 0, 9, withscores=True)
        elif roll < 0.90:
            self.reader.zscore(k, "u%d" % self.uid())
        else:
            self.reader.zcard(k)

    def counter_incr(self):
        k = "counter:%s:%s" % (self.rnd.choice(METRICS), self.today())
        if self.rnd.random() < 0.75:
            self.writer.incrby(k, self.rnd.randint(1, 20))
            self.writer.expire(k, 2 * 86400)
        else:
            self.reader.get(k)

    def bitmap_sign(self):
        k = "sign:%s" % self.today()
        if self.rnd.random() < 0.6:
            self.writer.setbit(k, self.uid(), 1)
            self.writer.expire(k, 30 * 86400)
        elif self.rnd.random() < 0.5:
            self.reader.getbit(k, self.uid())
        else:
            self.reader.bitcount(k)

    def hll_uv(self):
        k = "uv:%s" % self.today()
        if self.rnd.random() < 0.7:
            self.writer.pfadd(k, "u%d" % self.uid())
            self.writer.expire(k, 30 * 86400)
        else:
            self.reader.pfcount(k)

    def geo_driver(self):
        city, lon, lat = self.rnd.choice(CITIES)
        k = "geo:driver:%s" % city
        if self.rnd.random() < 0.5:
            self.writer.geoadd(k, [lon + self.rnd.uniform(-0.2, 0.2),
                                   lat + self.rnd.uniform(-0.2, 0.2),
                                   "d%d" % self.uid()])
        else:
            # 两个坑：GEOSEARCH 是 6.2 才有的，兼容 5.0.14 只能用 GEORADIUS；
            # 而 GEORADIUS 带 STORE 能力，Redis 把它归为写命令，打到从节点会被拒，故走 writer。
            self.writer.georadius(k, lon, lat, 5, unit="km", count=10)

    def stream_event(self):
        k = "stream:events"
        roll = self.rnd.random()
        if roll < 0.65:
            self.writer.xadd(k, {"uid": self.uid(), "act": self.rnd.choice(METRICS),
                                 "ts": int(time.time()), "ext": payload(self.rnd, 60)},
                             maxlen=self.cfg.stream_max, approximate=True)
        elif roll < 0.85:
            self.reader.xlen(k)
        else:
            self.reader.xrange(k, count=10)

    def archive_growth(self):
        """
        只增不删的归档。「内存增长率」评估需要至少 72h 的单调趋势，
        而上面所有场景要么带 TTL 要么被 TRIM 截断，内存是平的，拟合出来斜率恒为 0。
        """
        k = "archive:events"
        member = "%d:%s" % (int(time.time() * 1000), payload(self.rnd, 200))
        self.writer.zadd(k, {member: time.time()})
        if self.rnd.random() < 0.01:
            size = self.writer.zcard(k)
            if size > self.cfg.archive_max:
                self.writer.zremrangebyrank(k, 0, size - self.cfg.archive_max - 1)

    # ---------------------------------------------------------------- 慢查询 / 危险命令
    def slow_command(self):
        """
        真实系统里总有人写出全量扫描。这类命令是慢日志和「危险命令」维度的唯一来源，
        频率由 --slow-ratio 控制，默认很低。
        """
        roll = self.rnd.random()
        if roll < 0.4:
            self.reader.lrange("queue:%s" % self.rnd.choice(TOPICS), 0, -1)
        elif roll < 0.7:
            self.reader.hgetall("bigkey:hash")
        elif roll < 0.9:
            self.reader.zrange("archive:events", 0, 2000)
        else:
            self.writer.keys("session:1*")

    # ---------------------------------------------------------------- 大 key 播种
    def seed_bigkeys(self):
        """大 key 只播一次，之后不再变动——持续写大 key 会让内存曲线被它主导。"""
        try:
            if not self.writer.exists("bigkey:blob"):
                self.writer.set("bigkey:blob", payload(self.rnd, 300 * 1024))
            if self.writer.hlen("bigkey:hash") < 60000:
                for base in range(0, 60000, 2000):
                    self.writer.hset("bigkey:hash", mapping={
                        "f%d" % (base + i): payload(self.rnd, 16) for i in range(2000)})
            if self.writer.llen("bigkey:list") < 60000:
                for _ in range(60):
                    self.writer.rpush("bigkey:list", *[payload(self.rnd, 24) for _ in range(1000)])
        except redis.RedisError as exc:
            self.log("播种大 key 失败（不影响其余流量）：%s" % exc)

    # ---------------------------------------------------------------- 主循环
    def log(self, msg):
        print("[%s][%s] %s" % (time.strftime("%H:%M:%S"), self.name, msg), flush=True)

    def qps_now(self):
        """
        正弦波动，让监控图上是波形而不是直线。恒定 QPS 的曲线看不出任何异常检测能力，
        也无法判断图表本身画得对不对。
        """
        if self.cfg.cycle_seconds <= 0:
            return self.cfg.qps
        phase = 2 * math.pi * ((time.time() - self.started) % self.cfg.cycle_seconds) / self.cfg.cycle_seconds
        return max(0.2, self.cfg.qps * (1 + self.cfg.wave * math.sin(phase)))

    def run(self):
        names = [s[0] for s in SCENARIOS]
        weights = [s[1] for s in SCENARIOS]
        if self.cfg.seed_bigkeys:
            self.seed_bigkeys()
        self.log("开始发压：qps≈%.1f 波动±%d%% key空间=%d 倾斜=%d%%"
                 % (self.cfg.qps, int(self.cfg.wave * 100), self.cfg.key_space, self.cfg.skew_percent))

        next_at = time.time()
        last_log = time.time()
        deadline = self.started + self.cfg.duration if self.cfg.duration > 0 else None
        while not self.stop.is_set():
            if deadline and time.time() >= deadline:
                break
            if self.cfg.max_ops and sum(self.ops.values()) >= self.cfg.max_ops:
                break

            name = self.rnd.choices(names, weights=weights, k=1)[0]
            if self.rnd.random() < self.cfg.slow_ratio:
                name = "slow_command"
            try:
                getattr(self, name)()
                self.ops[name] += 1
            except redis.RedisError as exc:
                self.errors += 1
                self.errors_by[name] += 1
                self.last_error = "%s: %s" % (name, exc)
                # 单次失败不退出：故障切换演练期间连接必然中断，退出反而丢掉了最该观察的时段
                self.stop.wait(0.5)
            except Exception as exc:  # noqa: BLE001
                self.errors += 1
                self.errors_by[name] += 1
                self.last_error = "%s: %r" % (name, exc)
                self.stop.wait(0.5)

            if time.time() - last_log >= self.cfg.log_every:
                self.report()
                last_log = time.time()

            next_at += 1.0 / self.qps_now()
            delay = next_at - time.time()
            if delay > 0:
                self.stop.wait(delay)
            else:
                next_at = time.time()  # 落后了就重新对表，不要追赶式补发
        self.report(final=True)

    def report(self, final=False):
        total = sum(self.ops.values())
        looked = self.hits + self.misses
        rate = (self.hits / looked * 100) if looked else 0.0
        top = ", ".join("%s=%d" % (k, v) for k, v in self.ops.most_common(5))
        bad = ", ".join("%s=%d" % (k, v) for k, v in self.errors_by.most_common(3))
        self.log("%s总计=%d 命中率=%.1f%% 错误=%d | %s%s"
                 % ("结束，" if final else "", total, rate, self.errors, top,
                    (" | 出错场景: %s（最近: %s）" % (bad, self.last_error)) if self.errors else ""))


def build_cluster(cfg):
    nodes = [ClusterNode(h, p) for h, p in cfg.cluster_nodes]
    common = dict(startup_nodes=nodes, decode_responses=True,
                  socket_timeout=3, socket_connect_timeout=3)
    try:
        # 5.3 起 read_from_replicas 被 load_balancing_strategy 取代，旧版没有这个参数
        from redis.cluster import LoadBalancingStrategy
        client = RedisCluster(load_balancing_strategy=LoadBalancingStrategy.ROUND_ROBIN, **common)
    except ImportError:
        client = RedisCluster(read_from_replicas=True, **common)
    client.ping()
    return client, client


def build_sentinel(cfg):
    sentinel = Sentinel(cfg.sentinel_nodes, socket_timeout=3, decode_responses=True)
    master = sentinel.master_for(cfg.master_name, socket_timeout=3, decode_responses=True)
    # 读写分离：从节点也要有 commandstats，否则平台上从节点永远是零流量
    replica = sentinel.slave_for(cfg.master_name, socket_timeout=3, decode_responses=True)
    master.ping()
    return master, replica


def parse_nodes(text, default_port):
    nodes = []
    for item in text.split(","):
        item = item.strip()
        if not item:
            continue
        if ":" in item:
            host, port = item.rsplit(":", 1)
            nodes.append((host, int(port)))
        else:
            nodes.append((item, default_port))
    return nodes


def main():
    parser = argparse.ArgumentParser(description="CacheCloud 测试 Redis 流量发生器")
    parser.add_argument("--targets", default="cluster,sentinel", help="cluster / sentinel，逗号分隔")
    parser.add_argument("--qps", type=float, default=float(os.environ.get("WORKLOAD_QPS", 6)),
                        help="每套集群的平均 QPS，默认 6（刻意压低）")
    parser.add_argument("--wave", type=float, default=0.6, help="QPS 正弦波动幅度，0 表示恒定")
    parser.add_argument("--cycle-seconds", type=int, default=3600, help="波动周期，默认 1 小时")
    parser.add_argument("--key-space", type=int, default=3000, help="活跃用户数，决定 key 基数")
    parser.add_argument("--miss-ratio", type=float, default=0.10, help="强制未命中的比例")
    parser.add_argument("--hot-percent", type=int, default=10, help="热点 key 占 key 空间的比例")
    parser.add_argument("--hot-ratio", type=float, default=0.8, help="落在热点 key 上的访问比例")
    parser.add_argument("--skew-percent", type=int, default=15,
                        help="用 hash tag 钉在同一 slot 的 key 占比，用于制造集群倾斜")
    parser.add_argument("--slow-ratio", type=float, default=0.004, help="全量扫描类慢命令的比例")
    parser.add_argument("--queue-max", type=int, default=2000, help="LIST 队列保留长度")
    parser.add_argument("--stream-max", type=int, default=5000, help="STREAM 保留条数")
    parser.add_argument("--archive-max", type=int, default=2000000, help="归档 ZSET 上限")
    parser.add_argument("--duration", type=int, default=0, help="运行秒数，0 表示一直跑")
    parser.add_argument("--max-ops", type=int, default=0, help="发够多少次命令后退出，用于冒烟验证")
    parser.add_argument("--log-every", type=int, default=60, help="统计输出间隔（秒）")
    parser.add_argument("--no-seed-bigkeys", dest="seed_bigkeys", action="store_false",
                        help="跳过大 key 播种")
    parser.add_argument("--cluster-nodes", default=",".join("%s:%d" % n for n in CLUSTER_NODES))
    parser.add_argument("--sentinel-nodes", default=",".join("%s:%d" % n for n in SENTINEL_NODES))
    parser.add_argument("--master-name", default=MASTER_NAME)
    cfg = parser.parse_args()
    cfg.cluster_nodes = parse_nodes(cfg.cluster_nodes, 6379)
    cfg.sentinel_nodes = parse_nodes(cfg.sentinel_nodes, 26379)

    stop = threading.Event()
    signal.signal(signal.SIGTERM, lambda *_: stop.set())
    signal.signal(signal.SIGINT, lambda *_: stop.set())

    builders = {"cluster": build_cluster, "sentinel": build_sentinel}
    traffics = []
    for target in [t.strip() for t in cfg.targets.split(",") if t.strip()]:
        if target not in builders:
            sys.exit("未知 target：%s（可选 cluster / sentinel）" % target)
        try:
            writer, reader = builders[target](cfg)
        except Exception as exc:  # noqa: BLE001
            print("[%s] 连接失败，跳过：%r" % (target, exc), flush=True)
            continue
        traffics.append(Traffic(target, writer, reader, cfg, stop))

    if not traffics:
        sys.exit("没有任何目标可用，请确认脚本跑在 redis-test 网络内")

    threads = [threading.Thread(target=t.run, name=t.name, daemon=True) for t in traffics]
    for thread in threads:
        thread.start()
    try:
        while any(thread.is_alive() for thread in threads):
            for thread in threads:
                thread.join(timeout=1)
    except KeyboardInterrupt:
        stop.set()
    stop.set()
    for thread in threads:
        thread.join(timeout=5)


if __name__ == "__main__":
    main()
