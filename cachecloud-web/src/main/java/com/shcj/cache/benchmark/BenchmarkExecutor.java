package com.shcj.cache.benchmark;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.Pipeline;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 压测执行体：一个线程一条连接，循环发命令并记录耗时。
 *
 * <p>连接不走连接池：压测要的就是「N 条并发连接持续打」，从池里借还反而引入了
 * 池本身的竞争，测出来的会是池的性能而不是 Redis 的。
 */
public class BenchmarkExecutor {

    private static final Logger LOGGER = LoggerFactory.getLogger(BenchmarkExecutor.class);

    private final BenchmarkOptions options;
    /** 每个 worker 用哪个 planner：定向模式下各自只生成所属节点的 key */
    private final java.util.function.IntFunction<BenchmarkKeyPlanner> plannerForWorker;
    private final BenchmarkStats stats;
    private final AtomicBoolean stopped;
    private final AtomicLong issued = new AtomicLong();
    private final JedisSupplier jedisSupplier;
    private final String payload;
    private final List<BenchmarkCommand> readCommands = new ArrayList<>();
    private final List<BenchmarkCommand> writeCommands = new ArrayList<>();

    /**
     * 由调用方决定每个 worker 连哪个节点。
     *
     * <p>必须按 worker 分配而不是共用一个连接：Cluster 下 key 归哪个节点是由 slot 决定的，
     * 连着 A 节点去写属于 B 节点的 key 只会换来一串 MOVED。让每个 worker 绑定一个节点、
     * 并只生成属于该节点的 key，就从根上避免了重定向。</p>
     */
    public interface JedisSupplier {
        Jedis get(int workerIndex) throws Exception;
    }

    public BenchmarkExecutor(BenchmarkOptions options,
                             java.util.function.IntFunction<BenchmarkKeyPlanner> plannerForWorker,
                             BenchmarkStats stats,
                             AtomicBoolean stopped, JedisSupplier jedisSupplier) {
        this.options = options;
        this.plannerForWorker = plannerForWorker;
        this.stats = stats;
        this.stopped = stopped;
        this.jedisSupplier = jedisSupplier;
        this.payload = StringUtils.repeat('x', Math.max(1, options.getValueSize()));
        for (String name : options.getCommands()) {
            BenchmarkCommand command = BenchmarkCommand.of(name);
            if (command == null) {
                continue;
            }
            if (command.isWrite()) {
                writeCommands.add(command);
            } else {
                readCommands.add(command);
            }
        }
    }

    public boolean hasCommands() {
        return !readCommands.isEmpty() || !writeCommands.isEmpty();
    }

    /**
     * 跑满一轮压测，直到时长到点、请求数打满或被要求停止。
     */
    public void run(long deadlineMillis) {
        int concurrency = Math.max(1, options.getConcurrency());
        CountDownLatch done = new CountDownLatch(concurrency);
        List<Thread> workers = new ArrayList<>(concurrency);
        for (int i = 0; i < concurrency; i++) {
            final int workerIndex = i;
            Thread worker = new Thread(() -> {
                try {
                    runWorker(workerIndex, deadlineMillis);
                } finally {
                    done.countDown();
                }
            }, "benchmark-worker-" + workerIndex);
            worker.setDaemon(true);
            // 压测线程压到最低优先级：平台自己就是压测机，4 核机器上 20 个满负荷线程
            // 会把 Quartz 调度线程饿死——实测一次 25 秒的压测让全平台采集断了 4 分钟。
            // 降优先级不限制压测规模，只是在 CPU 争抢时让平台自身的作业先跑。
            worker.setPriority(Thread.MIN_PRIORITY);
            workers.add(worker);
            worker.start();
        }
        try {
            done.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            stopped.set(true);
            for (Thread worker : workers) {
                worker.interrupt();
            }
        }
    }

    private void runWorker(int workerIndex, long deadlineMillis) {
        Random random = new Random(System.nanoTime() + workerIndex);
        BenchmarkKeyPlanner planner = plannerForWorker.apply(workerIndex);
        Jedis jedis = null;
        try {
            jedis = jedisSupplier.get(workerIndex);
            int pipelineDepth = Math.max(1, options.getPipeline());
            while (!stopped.get() && System.currentTimeMillis() < deadlineMillis && !reachedRequestLimit()) {
                if (pipelineDepth == 1) {
                    executeSingle(jedis, planner, random);
                } else {
                    executePipelined(jedis, planner, random, pipelineDepth);
                }
            }
        } catch (Exception e) {
            stats.recordError(e.getClass().getSimpleName());
            LOGGER.warn("benchmark worker {} aborted: {}", workerIndex, e.getMessage());
        } finally {
            if (jedis != null) {
                try {
                    jedis.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private boolean reachedRequestLimit() {
        long limit = options.getTotalRequests();
        return limit > 0 && issued.get() >= limit;
    }

    private void executeSingle(Jedis jedis, BenchmarkKeyPlanner planner, Random random) {
        BenchmarkCommand command = pickCommand(random);
        if (command == null) {
            stopped.set(true);
            return;
        }
        String key = planner.nextKey(random);
        long start = System.nanoTime();
        try {
            apply(jedis, command, key, random);
            issued.incrementAndGet();
            stats.record(command.getName(), (System.nanoTime() - start) / 1000L);
        } catch (Exception e) {
            issued.incrementAndGet();
            stats.recordError(e.getClass().getSimpleName());
        }
    }

    /**
     * 管线批量：一次攒够 depth 条再收响应。
     *
     * <p>耗时按整批平摊到每条命令——管线下单条命令本来就没有独立的往返，
     * 强行拆开会得到一个不存在的「单条延迟」。</p>
     */
    private void executePipelined(Jedis jedis, BenchmarkKeyPlanner planner, Random random, int depth) {
        List<BenchmarkCommand> batch = new ArrayList<>(depth);
        long start = System.nanoTime();
        try {
            Pipeline pipeline = jedis.pipelined();
            for (int i = 0; i < depth; i++) {
                BenchmarkCommand command = pickCommand(random);
                if (command == null) {
                    stopped.set(true);
                    break;
                }
                batch.add(command);
                applyPipelined(pipeline, command, planner.nextKey(random), random);
            }
            pipeline.sync();
            long perCommandUs = batch.isEmpty() ? 0 : (System.nanoTime() - start) / 1000L / batch.size();
            for (BenchmarkCommand command : batch) {
                issued.incrementAndGet();
                stats.record(command.getName(), perCommandUs);
            }
        } catch (Exception e) {
            for (int i = 0; i < Math.max(1, batch.size()); i++) {
                issued.incrementAndGet();
                stats.recordError(e.getClass().getSimpleName());
            }
        }
    }

    /** 按读写权重挑命令；某一侧没有勾选命令时权重自动作废 */
    private BenchmarkCommand pickCommand(Random random) {
        boolean hasRead = !readCommands.isEmpty();
        boolean hasWrite = !writeCommands.isEmpty();
        if (!hasRead && !hasWrite) {
            return null;
        }
        boolean write;
        if (!hasRead) {
            write = true;
        } else if (!hasWrite) {
            write = false;
        } else {
            int readWeight = Math.max(0, options.getReadWeight());
            int writeWeight = Math.max(0, options.getWriteWeight());
            int total = readWeight + writeWeight;
            write = total <= 0 ? random.nextBoolean() : random.nextInt(total) >= readWeight;
        }
        List<BenchmarkCommand> pool = write ? writeCommands : readCommands;
        return pool.get(random.nextInt(pool.size()));
    }

    private void apply(Jedis jedis, BenchmarkCommand command, String key, Random random) {
        String field = "f" + random.nextInt(16);
        String member = "m" + random.nextInt(64);
        switch (command) {
            case GET: jedis.get(key); break;
            case MGET: jedis.mget(key, key); break;
            case STRLEN: jedis.strlen(key); break;
            case EXISTS: jedis.exists(key); break;
            case TTL: jedis.ttl(key); break;
            case SET: jedis.setex(key, options.getTtlSeconds(), payload); break;
            case SETEX: jedis.setex(key, options.getTtlSeconds(), payload); break;
            case INCR: jedis.incr(key + ":n"); expire(jedis, key + ":n"); break;
            case APPEND: jedis.append(key, "x"); expire(jedis, key); break;
            case DEL: jedis.del(key); break;

            case HGET: jedis.hget(key + ":h", field); break;
            case HMGET: jedis.hmget(key + ":h", field, "f0"); break;
            case HGETALL: jedis.hgetAll(key + ":h"); break;
            case HLEN: jedis.hlen(key + ":h"); break;
            case HSET: jedis.hset(key + ":h", field, payload); expire(jedis, key + ":h"); break;
            case HDEL: jedis.hdel(key + ":h", field); break;
            case HINCRBY: jedis.hincrBy(key + ":h", field, 1); expire(jedis, key + ":h"); break;

            case LRANGE: jedis.lrange(key + ":l", 0, 99); break;
            case LLEN: jedis.llen(key + ":l"); break;
            case LPUSH: jedis.lpush(key + ":l", payload); expire(jedis, key + ":l"); break;
            case RPUSH: jedis.rpush(key + ":l", payload); expire(jedis, key + ":l"); break;
            case LPOP: jedis.lpop(key + ":l"); break;

            case SISMEMBER: jedis.sismember(key + ":s", member); break;
            case SCARD: jedis.scard(key + ":s"); break;
            case SADD: jedis.sadd(key + ":s", member); expire(jedis, key + ":s"); break;
            case SREM: jedis.srem(key + ":s", member); break;

            case ZSCORE: jedis.zscore(key + ":z", member); break;
            case ZRANGE: jedis.zrange(key + ":z", 0, 99); break;
            case ZCARD: jedis.zcard(key + ":z"); break;
            case ZADD: jedis.zadd(key + ":z", random.nextInt(1000), member); expire(jedis, key + ":z"); break;
            case ZINCRBY: jedis.zincrby(key + ":z", 1, member); expire(jedis, key + ":z"); break;
            default: break;
        }
    }

    private void applyPipelined(Pipeline pipeline, BenchmarkCommand command, String key, Random random) {
        String field = "f" + random.nextInt(16);
        String member = "m" + random.nextInt(64);
        switch (command) {
            case GET: pipeline.get(key); break;
            case MGET: pipeline.mget(key, key); break;
            case STRLEN: pipeline.strlen(key); break;
            case EXISTS: pipeline.exists(key); break;
            case TTL: pipeline.ttl(key); break;
            case SET: pipeline.setex(key, options.getTtlSeconds(), payload); break;
            case SETEX: pipeline.setex(key, options.getTtlSeconds(), payload); break;
            case INCR: pipeline.incr(key + ":n"); pipeline.expire(key + ":n", options.getTtlSeconds()); break;
            case APPEND: pipeline.append(key, "x"); pipeline.expire(key, options.getTtlSeconds()); break;
            case DEL: pipeline.del(key); break;

            case HGET: pipeline.hget(key + ":h", field); break;
            case HMGET: pipeline.hmget(key + ":h", field, "f0"); break;
            case HGETALL: pipeline.hgetAll(key + ":h"); break;
            case HLEN: pipeline.hlen(key + ":h"); break;
            case HSET: pipeline.hset(key + ":h", field, payload); pipeline.expire(key + ":h", options.getTtlSeconds()); break;
            case HDEL: pipeline.hdel(key + ":h", field); break;
            case HINCRBY: pipeline.hincrBy(key + ":h", field, 1); pipeline.expire(key + ":h", options.getTtlSeconds()); break;

            case LRANGE: pipeline.lrange(key + ":l", 0, 99); break;
            case LLEN: pipeline.llen(key + ":l"); break;
            case LPUSH: pipeline.lpush(key + ":l", payload); pipeline.expire(key + ":l", options.getTtlSeconds()); break;
            case RPUSH: pipeline.rpush(key + ":l", payload); pipeline.expire(key + ":l", options.getTtlSeconds()); break;
            case LPOP: pipeline.lpop(key + ":l"); break;

            case SISMEMBER: pipeline.sismember(key + ":s", member); break;
            case SCARD: pipeline.scard(key + ":s"); break;
            case SADD: pipeline.sadd(key + ":s", member); pipeline.expire(key + ":s", options.getTtlSeconds()); break;
            case SREM: pipeline.srem(key + ":s", member); break;

            case ZSCORE: pipeline.zscore(key + ":z", member); break;
            case ZRANGE: pipeline.zrange(key + ":z", 0, 99); break;
            case ZCARD: pipeline.zcard(key + ":z"); break;
            case ZADD: pipeline.zadd(key + ":z", random.nextInt(1000), member); pipeline.expire(key + ":z", options.getTtlSeconds()); break;
            case ZINCRBY: pipeline.zincrby(key + ":z", 1, member); pipeline.expire(key + ":z", options.getTtlSeconds()); break;
            default: break;
        }
    }

    /** 写命令一律补 TTL：压测数据必须能自己过期，否则一次压测就在库里留下永久垃圾 */
    private void expire(Jedis jedis, String key) {
        jedis.expire(key, options.getTtlSeconds());
    }

    public long getIssued() {
        return issued.get();
    }
}
