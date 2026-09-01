package com.shcj.cache.benchmark;

import com.alibaba.fastjson.JSON;
import com.shcj.cache.dao.BenchmarkDao;
import com.shcj.cache.entity.AppDesc;
import com.shcj.cache.entity.InstanceInfo;
import com.shcj.cache.exception.BizException;
import com.shcj.cache.redis.RedisCenter;
import com.shcj.cache.util.TypeUtil;
import com.shcj.cache.web.service.AppService;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import redis.clients.jedis.Jedis;

import javax.annotation.PreDestroy;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 压测任务的生命周期管理。
 *
 * <p>压测在后台线程里跑，界面每秒轮询进度。刻意不阻塞 HTTP 请求：一次压测短则几十秒、
 * 长则十分钟，挂在请求线程上会把连接和线程都占死。
 */
@Service
public class BenchmarkService {

    private static final Logger LOGGER = LoggerFactory.getLogger(BenchmarkService.class);

    /** 每次压测最多为多少个 key 做清理，超出部分靠 TTL 兜底 */
    private static final int MAX_CLEANUP_KEYS = 200000;

    private static final int CLEANUP_BATCH = 500;

    /**
     * 压测连接的超时。
     *
     * <p>比平台采集用的 1 秒宽松：压测本来就是要把实例压到响应变慢，
     * 用 1 秒会在压力上来时把正常的慢响应误判成连接异常。</p>
     */
    private static final int SOCKET_TIMEOUT_MS = 5000;

    /** 承压节点 CPU 的采样间隔。太密会让采样自身成为负载，太疏则短压测取不到几个点 */
    private static final long TARGET_CPU_SAMPLE_INTERVAL_MS = 3000L;

    @Autowired
    private AppService appService;

    @Autowired
    private RedisCenter redisCenter;

    @Autowired
    private BenchmarkDao benchmarkDao;

    private final ExecutorService runner = Executors.newCachedThreadPool(r -> {
        Thread thread = new Thread(r, "benchmark-runner");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        return thread;
    });

    /** 运行中的任务，key 是任务 id */
    private final Map<Long, RunningTask> running = new ConcurrentHashMap<>();

    private static final class RunningTask {
        private final BenchmarkOptions options;
        /** 快捷压测每一档换一份统计，所以不能是 final */
        private volatile BenchmarkStats stats = new BenchmarkStats();
        private volatile int currentConcurrency;
        private volatile String rampMessage;
        private final List<Map<String, Object>> rampSteps =
                java.util.Collections.synchronizedList(new ArrayList<Map<String, Object>>());
        private final AtomicBoolean stopped = new AtomicBoolean(false);
        private final long startMillis = System.currentTimeMillis();
        private volatile long lastSampleMillis = System.currentTimeMillis();
        private volatile long lastSampleCount = 0L;
        private volatile long currentQps = 0L;
        private volatile String status = "RUNNING";
        private volatile double clientCpuPercent = 0D;
        /** 承压节点最近一次采样的 CPU（各节点取最忙者，按单核计） */
        private volatile double targetCpuPercent = 0D;
        /** 全程累计，用于算平均——单看瞬时值判断不了整轮压力水平 */
        private final java.util.concurrent.atomic.AtomicLong targetCpuSumTenth =
                new java.util.concurrent.atomic.AtomicLong();
        private final java.util.concurrent.atomic.AtomicInteger targetCpuSamples =
                new java.util.concurrent.atomic.AtomicInteger();
        /** 全程峰值——均值会被低压阶段拉低，判断「有没有被压满过」要看这个 */
        private volatile double peakTargetCpuPercent = 0D;

        private double avgTargetCpuPercent() {
            int count = targetCpuSamples.get();
            return count <= 0 ? 0D : targetCpuSumTenth.get() / 10.0D / count;
        }

        private RunningTask(BenchmarkOptions options) {
            this.options = options;
        }
    }

    @PreDestroy
    public void shutdown() {
        for (RunningTask task : running.values()) {
            task.stopped.set(true);
        }
        runner.shutdownNow();
    }

    /** 命令目录，供界面渲染勾选框 */
    public Map<String, List<Map<String, String>>> commandCatalog() {
        return BenchmarkCommand.catalog();
    }

    /**
     * 列出可作为定向目标的 master 节点及其槽位范围。
     *
     * <p>只列 master：从节点上写命令必定报错，而默认命令集里就含写命令。</p>
     */
    public List<Map<String, Object>> listTargets(long appId) {
        AppDesc appDesc = requireApp(appId);
        List<Map<String, Object>> targets = new ArrayList<>();
        if (!TypeUtil.isRedisCluster(appDesc.getType())) {
            return targets;
        }
        Map<Integer, String> slotMap = loadSlotMap(appDesc);
        Map<String, List<Integer>> byNode = new LinkedHashMap<>();
        for (Map.Entry<Integer, String> entry : slotMap.entrySet()) {
            byNode.computeIfAbsent(entry.getValue(), k -> new ArrayList<>()).add(entry.getKey());
        }
        for (Map.Entry<String, List<Integer>> entry : byNode.entrySet()) {
            List<Integer> slots = entry.getValue();
            slots.sort(null);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("hostPort", entry.getKey());
            item.put("slotCount", slots.size());
            item.put("slotRange", slots.isEmpty() ? "" : slots.get(0) + "-" + slots.get(slots.size() - 1));
            targets.add(item);
        }
        return targets;
    }

    /**
     * 启动一次压测。
     *
     * @return 任务 id
     */
    public long start(BenchmarkOptions options, String userName) {
        AppDesc appDesc = requireApp(options.getAppId());
        if (options.getCommands() == null || options.getCommands().isEmpty()) {
            throw new BizException("请至少勾选一个命令");
        }
        if (options.getDurationSeconds() <= 0 && options.getTotalRequests() <= 0) {
            throw new BizException("压测时长与总请求数至少填一个");
        }

        List<String> hashTags = resolveHashTags(appDesc, options.getTargetNodes());
        BenchmarkTask task = new BenchmarkTask();
        task.setAppId(appDesc.getAppId());
        task.setAppName(appDesc.getName());
        task.setTargetDesc(options.getTargetNodes() == null || options.getTargetNodes().isEmpty()
                ? "整集群" : StringUtils.join(options.getTargetNodes(), ", "));
        task.setOptionsJson(JSON.toJSONString(options));
        task.setStatus("RUNNING");
        task.setUserName(StringUtils.defaultString(userName));
        task.setStartTime(new Date());
        benchmarkDao.save(task);

        RunningTask runningTask = new RunningTask(options);
        running.put(task.getId(), runningTask);
        runner.submit(() -> execute(task, appDesc, options, hashTags, runningTask));
        return task.getId();
    }

    /**
     * 快捷压测：用默认参数逐档加并发，直到目标节点 CPU 打满或 QPS 出现拐点。
     *
     * <p>解决的是「我该填多少并发」这个问题——手工压测得来回试参数，而容量结论恰恰
     * 取决于把并发加到压不动为止。</p>
     */
    public long startQuick(long appId, List<String> targetNodes, String userName) {
        BenchmarkOptions options = new BenchmarkOptions();
        options.setAppId(appId);
        options.setTargetNodes(targetNodes == null ? new ArrayList<>() : targetNodes);
        options.setCommands(Arrays.asList("GET", "SET"));
        options.setQuickMode(true);
        // 并发由爬坡逐档决定，这里给的是首档
        options.setConcurrency(BenchmarkRampPlan.CONCURRENCY_STEPS[0]);
        options.setDurationSeconds(BenchmarkRampPlan.STEP_SECONDS * BenchmarkRampPlan.CONCURRENCY_STEPS.length);
        options.setTotalRequests(0L);
        return start(options, userName);
    }

    public void stop(long taskId) {
        RunningTask task = running.get(taskId);
        if (task == null) {
            throw new BizException("任务不在运行中");
        }
        task.status = "STOPPED";
        task.stopped.set(true);
    }

    /** 运行期进度；任务已结束时返回落库的汇总 */
    public BenchmarkProgress progress(long taskId) {
        RunningTask task = running.get(taskId);
        BenchmarkProgress progress = new BenchmarkProgress();
        progress.setTaskId(taskId);
        if (task == null) {
            BenchmarkTask stored = benchmarkDao.get(taskId);
            if (stored == null) {
                throw new BizException("压测任务不存在");
            }
            progress.setStatus(stored.getStatus());
            progress.setTotalRequests(stored.getTotalRequests());
            progress.setErrorCount(stored.getErrorCount());
            progress.setAvgQps(stored.getQps());
            progress.setP50Ms(stored.getP50Ms());
            progress.setP95Ms(stored.getP95Ms());
            progress.setP99Ms(stored.getP99Ms());
            progress.setMaxMs(stored.getMaxMs());
            progress.setClientCpuPercent(stored.getClientCpuPercent());
            progress.setAvgTargetCpuPercent(stored.getAvgTargetCpuPercent());
            progress.setPeakTargetCpuPercent(stored.getPeakTargetCpuPercent());
            progress.setRampMessage(stored.getRampMessage());
            if (StringUtils.isNotBlank(stored.getRampJson())) {
                try {
                    progress.setRampSteps(JSON.parseObject(stored.getRampJson(),
                            new com.alibaba.fastjson.TypeReference<List<Map<String, Object>>>() { }));
                } catch (Exception ignored) {
                    // 解析不了就不给爬坡明细，不影响其余字段
                }
            }
            return progress;
        }
        long elapsedMs = System.currentTimeMillis() - task.startMillis;
        long total = task.stats.getTotalCount();
        // 最近一秒的瞬时 QPS：全程平均画不出波动，看不出「跑到第 3 分钟开始掉速」
        long now = System.currentTimeMillis();
        long deltaMs = now - task.lastSampleMillis;
        if (deltaMs >= 900) {
            task.currentQps = (total - task.lastSampleCount) * 1000L / Math.max(1, deltaMs);
            task.lastSampleMillis = now;
            task.lastSampleCount = total;
            task.clientCpuPercent = sampleProcessCpu();
        }
        progress.setStatus(task.status);
        progress.setElapsedSeconds(elapsedMs / 1000);
        progress.setTotalRequests(total);
        progress.setErrorCount(task.stats.getErrorCount());
        progress.setCurrentQps(task.currentQps);
        progress.setAvgQps(elapsedMs <= 0 ? 0 : total * 1000L / elapsedMs);
        progress.setP50Ms(task.stats.percentileUs(50) / 1000.0);
        progress.setP95Ms(task.stats.percentileUs(95) / 1000.0);
        progress.setP99Ms(task.stats.percentileUs(99) / 1000.0);
        progress.setMaxMs(task.stats.getMaxLatencyUs() / 1000.0);
        progress.setClientCpuPercent(task.clientCpuPercent);
        progress.setTargetCpuPercent(task.targetCpuPercent);
        progress.setAvgTargetCpuPercent(Math.round(task.avgTargetCpuPercent() * 10.0) / 10.0);
        progress.setPeakTargetCpuPercent(task.peakTargetCpuPercent);
        progress.setErrorTypes(task.stats.errorTypes());
        progress.setCurrentConcurrency(task.currentConcurrency);
        progress.setRampSteps(new ArrayList<>(task.rampSteps));
        progress.setRampMessage(task.rampMessage);
        return progress;
    }

    public BenchmarkTask get(long taskId) {
        BenchmarkTask task = benchmarkDao.get(taskId);
        if (task == null) {
            throw new BizException("压测任务不存在");
        }
        return task;
    }

    /** 删除一条历史记录；运行中的任务不允许删 */
    public void delete(long taskId) {
        if (running.containsKey(taskId)) {
            throw new BizException("压测进行中，请先停止再删除");
        }
        BenchmarkTask task = benchmarkDao.get(taskId);
        if (task == null) {
            throw new BizException("压测记录不存在");
        }
        benchmarkDao.delete(taskId);
    }

    public List<BenchmarkTask> list(Long appId, int pageNo, int pageSize) {
        int safePageNo = Math.max(1, pageNo);
        int safePageSize = pageSize <= 0 || pageSize > 100 ? 20 : pageSize;
        return benchmarkDao.search(appId, (safePageNo - 1) * safePageSize, safePageSize);
    }

    public int count(Long appId) {
        return benchmarkDao.count(appId);
    }

    // ------------------------------------------------------------------ 执行

    private void execute(BenchmarkTask task, AppDesc appDesc, BenchmarkOptions options,
                         List<String> hashTags, RunningTask runningTask) {
        // 每个目标节点一套 (连接, key 规划器)：worker 只生成自己所连节点的 key，
        // 从根上避免 Cluster 的 MOVED 重定向
        List<NodePlan> plans = buildNodePlans(task.getId(), appDesc, options, hashTags);
        String errorMsg = null;
        Thread sampler = null;
        try {
            BenchmarkExecutor executor = new BenchmarkExecutor(options,
                    index -> plans.get(index % plans.size()).planner,
                    runningTask.stats, runningTask.stopped,
                    index -> openJedis(appDesc, plans.get(index % plans.size()).hostPort));
            if (!executor.hasCommands()) {
                throw new BizException("勾选的命令无法识别");
            }
            sampler = startTargetCpuSampler(appDesc, plans, runningTask);
            if (options.isQuickMode()) {
                runRamp(appDesc, options, plans, runningTask);
            } else {
                long deadline = options.getDurationSeconds() > 0
                        ? runningTask.startMillis + options.getDurationSeconds() * 1000L
                        : Long.MAX_VALUE;
                executor.run(deadline);
            }
        } catch (Exception e) {
            errorMsg = StringUtils.abbreviate(e.getClass().getSimpleName() + ": " + e.getMessage(), 1000);
            runningTask.status = "FAILED";
            LOGGER.error("benchmark task {} failed: {}", task.getId(), e.getMessage(), e);
        } finally {
            if (sampler != null) {
                sampler.interrupt();
            }
            finish(task, options, plans, runningTask, errorMsg, appDesc);
            running.remove(task.getId());
        }
    }

    /**
     * 后台采样承压节点的 CPU。
     *
     * <p>单开一条线程而不是搭在进度轮询上：进度接口跑在 HTTP 线程里，在那里发 INFO
     * 会让页面刷新等着 Redis 返回；而且轮询频率由前端决定，采样间隔就不可控了。
     *
     * <p>连接建一次复用到底——每 5 秒重建连接，采样本身就成了压测的一部分。</p>
     */
    private Thread startTargetCpuSampler(AppDesc appDesc, List<NodePlan> plans, RunningTask runningTask) {
        Thread sampler = new Thread(() -> {
            Map<String, Jedis> connections = new LinkedHashMap<>();
            try {
                for (NodePlan plan : plans) {
                    String key = plan.hostPort == null ? "self" : plan.hostPort;
                    try {
                        connections.put(key, openJedis(appDesc, plan.hostPort));
                    } catch (Exception e) {
                        LOGGER.warn("cpu sampler connect failed node={}: {}", plan.hostPort, e.getMessage());
                    }
                }
                Map<String, Double> previous = readCpuSeconds(connections);
                long previousMillis = System.currentTimeMillis();
                while (!Thread.currentThread().isInterrupted() && !runningTask.stopped.get()) {
                    Thread.sleep(TARGET_CPU_SAMPLE_INTERVAL_MS);
                    Map<String, Double> current = readCpuSeconds(connections);
                    long now = System.currentTimeMillis();
                    double cpu = maxCpuPercent(previous, current, now - previousMillis);
                    previous = current;
                    previousMillis = now;
                    if (cpu <= 0) {
                        continue;
                    }
                    double rounded = Math.round(cpu * 10.0) / 10.0;
                    runningTask.targetCpuPercent = rounded;
                    if (rounded > runningTask.peakTargetCpuPercent) {
                        runningTask.peakTargetCpuPercent = rounded;
                    }
                    runningTask.targetCpuSumTenth.addAndGet(Math.round(cpu * 10.0));
                    runningTask.targetCpuSamples.incrementAndGet();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                LOGGER.warn("target cpu sampler stopped: {}", e.getMessage());
            } finally {
                for (Jedis jedis : connections.values()) {
                    try {
                        jedis.close();
                    } catch (Exception ignored) {
                    }
                }
            }
        }, "benchmark-cpu-sampler");
        sampler.setDaemon(true);
        // 与压测线程同为最低优先级：采样不该跟平台自身的作业抢 CPU
        sampler.setPriority(Thread.MIN_PRIORITY);
        sampler.start();
        return sampler;
    }

    private Map<String, Double> readCpuSeconds(Map<String, Jedis> connections) {
        Map<String, Double> result = new LinkedHashMap<>();
        for (Map.Entry<String, Jedis> entry : connections.entrySet()) {
            try {
                String info = entry.getValue().info("cpu");
                result.put(entry.getKey(),
                        parseInfoDouble(info, "used_cpu_sys") + parseInfoDouble(info, "used_cpu_user"));
            } catch (Exception e) {
                LOGGER.debug("read cpu failed node={}: {}", entry.getKey(), e.getMessage());
            }
        }
        return result;
    }

    /**
     * 逐档加压。每档跑固定时长，用该档独立的统计判断是否继续。
     *
     * <p>每档单独统计而不是累加：累加会把低并发档的数据混进来，把高并发档的真实
     * QPS 稀释掉，拐点就看不出来了。</p>
     */
    private void runRamp(AppDesc appDesc, BenchmarkOptions options, List<NodePlan> plans,
                         RunningTask runningTask) {
        long previousQps = 0L;
        long peakQps = 0L;
        int peakConcurrency = 0;
        String stopReason = null;

        for (int concurrency : BenchmarkRampPlan.CONCURRENCY_STEPS) {
            if (runningTask.stopped.get()) {
                stopReason = "已手动停止";
                break;
            }
            BenchmarkOptions stepOptions = copyWithConcurrency(options, concurrency);
            BenchmarkStats stepStats = new BenchmarkStats();
            runningTask.stats = stepStats;
            runningTask.currentConcurrency = concurrency;
            runningTask.rampMessage = "并发 " + concurrency + " 压测中";

            Map<String, Double> cpuBefore = sampleNodeCpuSeconds(appDesc, plans);
            long stepStart = System.currentTimeMillis();
            BenchmarkExecutor executor = new BenchmarkExecutor(stepOptions,
                    index -> plans.get(index % plans.size()).planner,
                    stepStats, runningTask.stopped,
                    index -> openJedis(appDesc, plans.get(index % plans.size()).hostPort));
            executor.run(stepStart + BenchmarkRampPlan.STEP_SECONDS * 1000L);
            long elapsedMs = Math.max(1, System.currentTimeMillis() - stepStart);
            Map<String, Double> cpuAfter = sampleNodeCpuSeconds(appDesc, plans);

            long qps = stepStats.getTotalCount() * 1000L / elapsedMs;
            double cpu = maxCpuPercent(cpuBefore, cpuAfter, elapsedMs);

            Map<String, Object> step = new LinkedHashMap<>();
            step.put("concurrency", concurrency);
            step.put("qps", qps);
            step.put("p99Ms", stepStats.percentileUs(99) / 1000.0);
            step.put("p95Ms", stepStats.percentileUs(95) / 1000.0);
            step.put("targetCpuPercent", Math.round(cpu * 10.0) / 10.0);
            step.put("errorCount", stepStats.getErrorCount());
            step.put("totalRequests", stepStats.getTotalCount());
            runningTask.rampSteps.add(step);

            if (qps > peakQps) {
                peakQps = qps;
                peakConcurrency = concurrency;
            }
            stopReason = BenchmarkRampPlan.stopReason(cpu, qps, previousQps,
                    stepStats.getErrorCount(), stepStats.getTotalCount());
            if (stopReason != null) {
                break;
            }
            previousQps = qps;
        }
        if (stopReason == null) {
            stopReason = "已加压到最高档 " + BenchmarkRampPlan.CONCURRENCY_STEPS[
                    BenchmarkRampPlan.CONCURRENCY_STEPS.length - 1] + " 并发仍未见拐点";
        }
        runningTask.rampMessage = String.format("峰值 QPS %d（并发 %d）—— %s",
                peakQps, peakConcurrency, stopReason);
    }

    /**
     * 快捷压测的汇总取峰值那一档，而不是最后一档。
     *
     * <p>最后一档往往正是压过头的那一档——CPU 打满、延迟劣化、吞吐反而回落。
     * 拿它当结论会低估集群容量，这里要记录的是「能承载的峰值」。</p>
     */
    private void applyPeakStep(BenchmarkTask task, RunningTask runningTask) {
        Map<String, Object> peak = null;
        long peakQps = -1;
        long totalRequests = 0L;
        long totalErrors = 0L;
        for (Map<String, Object> step : runningTask.rampSteps) {
            long qps = ((Number) step.getOrDefault("qps", 0)).longValue();
            totalRequests += ((Number) step.getOrDefault("totalRequests", 0)).longValue();
            totalErrors += ((Number) step.getOrDefault("errorCount", 0)).longValue();
            if (qps > peakQps) {
                peakQps = qps;
                peak = step;
            }
        }
        task.setTotalRequests(totalRequests);
        task.setErrorCount(totalErrors);
        if (peak == null) {
            return;
        }
        task.setQps(peakQps);
        task.setP95Ms(((Number) peak.getOrDefault("p95Ms", 0)).doubleValue());
        task.setP99Ms(((Number) peak.getOrDefault("p99Ms", 0)).doubleValue());
        task.setTargetCpuPercent(((Number) peak.getOrDefault("targetCpuPercent", 0)).doubleValue());
        task.setPeakConcurrency(((Number) peak.getOrDefault("concurrency", 0)).intValue());
    }

    private BenchmarkOptions copyWithConcurrency(BenchmarkOptions source, int concurrency) {
        BenchmarkOptions copy = new BenchmarkOptions();
        copy.setAppId(source.getAppId());
        copy.setTargetNodes(source.getTargetNodes());
        copy.setCommands(source.getCommands());
        copy.setConcurrency(concurrency);
        copy.setKeySpace(source.getKeySpace());
        copy.setValueSize(source.getValueSize());
        copy.setTtlSeconds(source.getTtlSeconds());
        copy.setPipeline(source.getPipeline());
        copy.setHotspot(source.isHotspot());
        copy.setReadWeight(source.getReadWeight());
        copy.setWriteWeight(source.getWriteWeight());
        // 每档自己控制时长，这里不能带上总时长，否则第一档就会一直跑到底
        copy.setDurationSeconds(0);
        copy.setTotalRequests(0L);
        return copy;
    }

    /**
     * 采样各目标节点的累计 CPU 秒数。
     *
     * <p>不用平台每分钟的采集：爬坡每档只有 20 秒，分钟级采集根本来不及给出这一档的
     * CPU。直接向节点要 INFO，按前后差值算。</p>
     */
    private Map<String, Double> sampleNodeCpuSeconds(AppDesc appDesc, List<NodePlan> plans) {
        Map<String, Double> result = new LinkedHashMap<>();
        for (NodePlan plan : plans) {
            String hostPort = plan.hostPort;
            try (Jedis jedis = openJedis(appDesc, hostPort)) {
                String info = jedis.info("cpu");
                double sys = parseInfoDouble(info, "used_cpu_sys");
                double user = parseInfoDouble(info, "used_cpu_user");
                result.put(hostPort == null ? "self" : hostPort, sys + user);
            } catch (Exception e) {
                LOGGER.warn("sample cpu failed node={}: {}", hostPort, e.getMessage());
            }
        }
        return result;
    }

    /**
     * 取各节点中最高的 CPU 使用率。
     *
     * <p>整集群压测时瓶颈由最忙的那个节点决定，取平均会把它藏起来。
     * 百分比按单核算：Redis 执行命令是单线程的，一个核就是它的天花板。</p>
     */
    private double maxCpuPercent(Map<String, Double> before, Map<String, Double> after, long elapsedMs) {
        double max = 0D;
        for (Map.Entry<String, Double> entry : after.entrySet()) {
            Double start = before.get(entry.getKey());
            if (start == null) {
                continue;
            }
            double delta = entry.getValue() - start;
            if (delta < 0) {
                continue;
            }
            max = Math.max(max, delta * 100.0D * 1000.0D / elapsedMs);
        }
        return max;
    }

    private double parseInfoDouble(String info, String field) {
        if (info == null) {
            return 0D;
        }
        for (String line : info.split("\r?\n")) {
            int idx = line.indexOf(':');
            if (idx > 0 && line.substring(0, idx).trim().equals(field)) {
                try {
                    return Double.parseDouble(line.substring(idx + 1).trim());
                } catch (NumberFormatException e) {
                    return 0D;
                }
            }
        }
        return 0D;
    }

    private void finish(BenchmarkTask task, BenchmarkOptions options, List<NodePlan> plans,
                        RunningTask runningTask, String errorMsg, AppDesc appDesc) {
        long elapsedMs = Math.max(1, System.currentTimeMillis() - runningTask.startMillis);
        BenchmarkStats stats = runningTask.stats;
        task.setStatus(errorMsg != null ? "FAILED" : ("STOPPED".equals(runningTask.status) ? "STOPPED" : "FINISHED"));
        task.setTotalRequests(stats.getTotalCount());
        task.setErrorCount(stats.getErrorCount());
        task.setQps(stats.getTotalCount() * 1000L / elapsedMs);
        task.setP50Ms(stats.percentileUs(50) / 1000.0);
        task.setP95Ms(stats.percentileUs(95) / 1000.0);
        task.setP99Ms(stats.percentileUs(99) / 1000.0);
        task.setMaxMs(stats.getMaxLatencyUs() / 1000.0);
        task.setAvgMs(stats.getAvgLatencyUs() / 1000.0);
        task.setClientCpuPercent(runningTask.clientCpuPercent);
        task.setAvgTargetCpuPercent(Math.round(runningTask.avgTargetCpuPercent() * 10.0) / 10.0);
        task.setPeakTargetCpuPercent(runningTask.peakTargetCpuPercent);
        Map<String, Object> commandStats = new LinkedHashMap<>();
        commandStats.put("counts", stats.commandCounts());
        commandStats.put("avgMs", stats.commandAvgLatencyUs());
        task.setCommandStatsJson(JSON.toJSONString(commandStats));
        task.setErrorStatsJson(JSON.toJSONString(stats.errorTypes()));
        task.setErrorMsg(errorMsg);
        task.setEndTime(new Date());
        if (options.isQuickMode()) {
            task.setRampJson(JSON.toJSONString(runningTask.rampSteps));
            task.setRampMessage(runningTask.rampMessage);
            applyPeakStep(task, runningTask);
        }
        try {
            benchmarkDao.update(task);
        } catch (Exception e) {
            LOGGER.error("save benchmark result failed id={}: {}", task.getId(), e.getMessage(), e);
        }
        if (options.isCleanup()) {
            for (NodePlan plan : plans) {
                cleanup(appDesc, plan);
            }
        }
    }

    /**
     * 清理压测写入的数据。
     *
     * <p>不用 SCAN 匹配前缀：SCAN 要遍历整个键空间，在大实例上比压测本身还重。
     * key 是按固定规则生成的，直接重放这些名字去删即可。</p>
     */
    private void cleanup(AppDesc appDesc, NodePlan plan) {
        BenchmarkKeyPlanner planner = plan.planner;
        int limit = Math.min(planner.getKeySpace(), MAX_CLEANUP_KEYS);
        try (Jedis jedis = openJedis(appDesc, plan.hostPort)) {
            List<String> batch = new ArrayList<>(CLEANUP_BATCH);
            Random sequential = null;
            for (int i = 0; i < limit; i++) {
                String base = planner.keyAt(i, sequential);
                batch.add(base);
                batch.add(base + ":n");
                batch.add(base + ":h");
                batch.add(base + ":l");
                batch.add(base + ":s");
                batch.add(base + ":z");
                if (batch.size() >= CLEANUP_BATCH) {
                    unlink(jedis, batch);
                    batch.clear();
                }
            }
            if (!batch.isEmpty()) {
                unlink(jedis, batch);
            }
        } catch (Exception e) {
            // 清理失败不算压测失败：强制 TTL 是第二道保险，数据最终会自己过期
            LOGGER.warn("benchmark cleanup failed node={}: {}", plan.hostPort, e.getMessage());
        }
    }

    private void unlink(Jedis jedis, List<String> keys) {
        try {
            jedis.unlink(keys.toArray(new String[0]));
        } catch (Exception e) {
            // 集群下跨 slot 的批量删会被拒绝，退回逐个删
            for (String key : keys) {
                try {
                    jedis.unlink(key);
                } catch (Exception ignored) {
                }
            }
        }
    }

    // ------------------------------------------------------------------ 目标解析

    /**
     * 为每个选中节点求一个 hashtag；整集群压测返回空表。
     */
    private List<String> resolveHashTags(AppDesc appDesc, List<String> targetNodes) {
        List<String> tags = new ArrayList<>();
        if (targetNodes == null || targetNodes.isEmpty()) {
            return tags;
        }
        if (!TypeUtil.isRedisCluster(appDesc.getType())) {
            throw new BizException("只有 Cluster 集群支持指定节点压测");
        }
        Map<Integer, String> slotMap = loadSlotMap(appDesc);
        for (String node : targetNodes) {
            Set<Integer> slots = new LinkedHashSet<>();
            for (Map.Entry<Integer, String> entry : slotMap.entrySet()) {
                if (entry.getValue().equals(node)) {
                    slots.add(entry.getKey());
                }
            }
            if (slots.isEmpty()) {
                throw new BizException("节点 " + node + " 未持有任何槽位，无法定向压测");
            }
            String tag = BenchmarkKeyPlanner.findHashTag(slots);
            if (tag == null) {
                throw new BizException("未能为节点 " + node + " 生成定向标签");
            }
            tags.add(tag);
        }
        return tags;
    }

    private Map<Integer, String> loadSlotMap(AppDesc appDesc) {
        for (InstanceInfo instance : onlineDataInstances(appDesc)) {
            try {
                Map<Integer, String> map = redisCenter.getSlotHostPortMap(
                        appDesc.getAppId(), instance.getIp(), instance.getPort());
                if (map != null && !map.isEmpty()) {
                    return map;
                }
            } catch (Exception e) {
                LOGGER.warn("load slot map from {}:{} failed: {}", instance.getIp(), instance.getPort(), e.getMessage());
            }
        }
        throw new BizException("无法获取集群槽位分布，请检查节点连通性");
    }

    private List<InstanceInfo> onlineDataInstances(AppDesc appDesc) {
        List<InstanceInfo> all = appService.getAppOnlineInstanceInfo(appDesc.getAppId());
        List<InstanceInfo> result = new ArrayList<>();
        if (all == null) {
            return result;
        }
        for (InstanceInfo instance : all) {
            if (instance == null || instance.isOffline() || TypeUtil.isRedisSentinel(instance.getType())) {
                continue;
            }
            result.add(instance);
        }
        return result;
    }

    /**
     * 建立压测连接。
     *
     * <p>定向压测时仍然连任一节点即可：hashtag 保证 key 落在目标节点，
     * 集群客户端会自动路由过去。</p>
     */
    /** 连指定节点；hostPort 为空时连集群里第一个可用数据节点 */
    private Jedis openJedis(AppDesc appDesc, String hostPort) throws Exception {
        if (StringUtils.isNotBlank(hostPort)) {
            String[] parts = StringUtils.split(hostPort, ':');
            if (parts != null && parts.length == 2) {
                return redisCenter.getJedis(appDesc.getAppId(), parts[0],
                        Integer.parseInt(parts[1]), SOCKET_TIMEOUT_MS, SOCKET_TIMEOUT_MS);
            }
        }
        List<InstanceInfo> instances = onlineDataInstances(appDesc);
        if (instances.isEmpty()) {
            throw new BizException("集群没有可用的数据节点");
        }
        InstanceInfo first = instances.get(0);
        return redisCenter.getJedis(appDesc.getAppId(), first.getIp(), first.getPort(),
                SOCKET_TIMEOUT_MS, SOCKET_TIMEOUT_MS);
    }

    /**
     * 把压测目标展开成「节点 + 该节点专属 key 规划器」。
     *
     * <p>Cluster 整集群压测也按 master 逐个展开：每个 master 配一个落在它槽位内的 hashtag，
     * worker 轮流绑定。这样压力仍然平摊到所有 master，但每个请求都直达归属节点，
     * 不产生 MOVED——此前整集群模式因为只连一个节点，实测三分之二的请求都在重定向上报错。</p>
     */
    private List<NodePlan> buildNodePlans(long taskId, AppDesc appDesc, BenchmarkOptions options,
                                          List<String> hashTags) {
        List<NodePlan> plans = new ArrayList<>();
        if (!TypeUtil.isRedisCluster(appDesc.getType())) {
            // 非 Cluster：直连主节点，不需要 hashtag
            plans.add(new NodePlan(null, new BenchmarkKeyPlanner(
                    taskId, java.util.Collections.<String>emptyList(), options.getKeySpace(), options.isHotspot())));
            return plans;
        }
        List<String> nodes = options.getTargetNodes();
        if (nodes != null && !nodes.isEmpty()) {
            for (int i = 0; i < nodes.size(); i++) {
                plans.add(new NodePlan(nodes.get(i), new BenchmarkKeyPlanner(taskId,
                        java.util.Collections.singletonList(hashTags.get(i)),
                        options.getKeySpace(), options.isHotspot())));
            }
            return plans;
        }
        Map<Integer, String> slotMap = loadSlotMap(appDesc);
        Map<String, Set<Integer>> byNode = new LinkedHashMap<>();
        for (Map.Entry<Integer, String> entry : slotMap.entrySet()) {
            byNode.computeIfAbsent(entry.getValue(), k -> new LinkedHashSet<>()).add(entry.getKey());
        }
        for (Map.Entry<String, Set<Integer>> entry : byNode.entrySet()) {
            String tag = BenchmarkKeyPlanner.findHashTag(entry.getValue());
            if (tag == null) {
                continue;
            }
            plans.add(new NodePlan(entry.getKey(), new BenchmarkKeyPlanner(taskId,
                    java.util.Collections.singletonList(tag), options.getKeySpace(), options.isHotspot())));
        }
        if (plans.isEmpty()) {
            throw new BizException("未能为集群节点生成压测计划");
        }
        return plans;
    }

    private static final class NodePlan {
        private final String hostPort;
        private final BenchmarkKeyPlanner planner;

        private NodePlan(String hostPort, BenchmarkKeyPlanner planner) {
            this.hostPort = hostPort;
            this.planner = planner;
        }
    }

    private AppDesc requireApp(long appId) {
        AppDesc appDesc = appService.getByAppId(appId);
        if (appDesc == null) {
            throw new BizException("集群不存在");
        }
        return appDesc;
    }

    /** 平台自身进程的 CPU 占用（百分比，可超过 100 表示多核） */
    private double sampleProcessCpu() {
        try {
            java.lang.management.OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
            if (os instanceof com.sun.management.OperatingSystemMXBean) {
                double load = ((com.sun.management.OperatingSystemMXBean) os).getProcessCpuLoad();
                return load < 0 ? 0D : Math.round(load * 1000D) / 10.0D;
            }
        } catch (Exception ignored) {
        }
        return 0D;
    }
}
