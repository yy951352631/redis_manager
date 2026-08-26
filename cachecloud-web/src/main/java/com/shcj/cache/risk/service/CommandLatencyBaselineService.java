package com.shcj.cache.risk.service;

import com.shcj.cache.entity.InstanceRuntimeProfile;
import com.shcj.cache.dao.InstanceRuntimeProfileDao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 命令平均耗时基线：用平台自身实例的历史数据算出「同类实例上这条命令通常要多久」。
 *
 * <p>基线按 <b>架构 × Redis 大版本</b> 分组。同一条 GET 在 ARM 与 X86、在 5.x 与 7.x 上的
 * 基准耗时并不可比，混在一起平均只会得到一个谁都不像的数，用它判定必然误报。
 *
 * <p>取数走小时归档表而非分钟表：分钟样本抖动大，基线要的是稳定的中心值；
 * 小时表已经做过差分聚合，正好合适。
 *
 * <p>分组样本不足时逐级回退——先按架构+大版本，再按架构，最后全平台。
 * 回退层级会带回给调用方，让报告能说清这个基线的可信度。
 */
@Service
public class CommandLatencyBaselineService {

    private static final Logger LOGGER = LoggerFactory.getLogger(CommandLatencyBaselineService.class);

    private static final SimpleDateFormat HOUR_FORMAT = new SimpleDateFormat("yyyyMMddHH");

    /** 基线取最近多少天的小时归档 */
    private static final int BASELINE_DAYS = 7;

    /** 某个分组内、某条命令至少要有这么多次调用，基线才有意义 */
    private static final long MIN_CALLS = 10000L;

    /** 基线至少要由这么多个实例贡献，否则等于拿单个实例自己跟自己比 */
    private static final int MIN_INSTANCES = 2;

    /** 基线缓存有效期，避免每次评估都全表扫一遍 */
    private static final long CACHE_TTL_MS = 30 * 60 * 1000L;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private InstanceRuntimeProfileDao instanceRuntimeProfileDao;

    private volatile Map<String, Baseline> cache = Collections.emptyMap();
    private volatile long cacheAt;

    /** 一个分组的基线 */
    public static final class Baseline {
        /** command -> 平均耗时（微秒） */
        private final Map<String, Double> avgUsec = new HashMap<String, Double>();
        /** 贡献样本的实例数 */
        private int instanceCount;
        /** 分组标识，如 "ARM/6.2"、"ARM"、"ALL" */
        private String scope;

        public Map<String, Double> getAvgUsec() {
            return avgUsec;
        }

        public int getInstanceCount() {
            return instanceCount;
        }

        public String getScope() {
            return scope;
        }
    }

    /**
     * 取某个实例适用的基线，按 架构+大版本 → 架构 → 全平台 逐级回退。
     *
     * @return 找不到任何可用基线时返回 null
     */
    public Baseline resolveFor(long instanceId) {
        Map<String, Baseline> all = load();
        if (all.isEmpty()) {
            return null;
        }
        InstanceRuntimeProfile profile = profileOf(instanceId);
        if (profile != null) {
            String arch = normalize(profile.getOsArch());
            String major = normalize(profile.getMajorVersion());
            Baseline exact = all.get(arch + "/" + major);
            if (usable(exact)) {
                return exact;
            }
            Baseline byArch = all.get(arch);
            if (usable(byArch)) {
                return byArch;
            }
        }
        Baseline global = all.get("ALL");
        return usable(global) ? global : null;
    }

    private boolean usable(Baseline baseline) {
        return baseline != null && baseline.instanceCount >= MIN_INSTANCES && !baseline.avgUsec.isEmpty();
    }

    private InstanceRuntimeProfile profileOf(long instanceId) {
        try {
            for (InstanceRuntimeProfile profile : instanceRuntimeProfileDao.listAll()) {
                if (profile.getInstanceId() == instanceId) {
                    return profile;
                }
            }
        } catch (Exception e) {
            LOGGER.warn("load instance runtime profile failed: {}", e.getMessage());
        }
        return null;
    }

    private String normalize(String value) {
        return value == null || value.trim().isEmpty() ? "UNKNOWN" : value.trim();
    }

    private synchronized Map<String, Baseline> load() {
        if (System.currentTimeMillis() - cacheAt < CACHE_TTL_MS && !cache.isEmpty()) {
            return cache;
        }
        Map<String, Baseline> result = new HashMap<String, Baseline>();
        try {
            long since = Long.parseLong(HOUR_FORMAT.format(
                    new Date(System.currentTimeMillis() - BASELINE_DAYS * 24L * 3600 * 1000)));
            // 按 架构/大版本、架构、全平台三个粒度各算一份，一次扫表全部产出
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "select p.os_arch as arch, p.major_version as major, h.command as cmd, "
                            + "sum(h.calls) as calls, sum(h.usec) as usec, "
                            + "count(distinct h.instance_id) as instances "
                            + "from instance_command_latency_hour h "
                            + "join instance_runtime_profile p on p.instance_id = h.instance_id "
                            + "where h.collect_time >= ? group by p.os_arch, p.major_version, h.command",
                    since);
            for (Map<String, Object> row : rows) {
                String arch = normalize(str(row.get("arch")));
                String major = normalize(str(row.get("major")));
                String command = str(row.get("cmd"));
                long calls = num(row.get("calls"));
                long usec = num(row.get("usec"));
                int instances = (int) num(row.get("instances"));
                if (command == null || calls < MIN_CALLS) {
                    continue;
                }
                double avg = usec * 1.0D / calls;
                accumulate(result, arch + "/" + major, command, avg, instances);
                accumulate(result, arch, command, avg, instances);
                accumulate(result, "ALL", command, avg, instances);
            }
        } catch (Exception e) {
            LOGGER.warn("build command latency baseline failed: {}", e.getMessage());
        }
        cache = result;
        cacheAt = System.currentTimeMillis();
        return result;
    }

    private void accumulate(Map<String, Baseline> result, String scope, String command, double avg, int instances) {
        Baseline baseline = result.get(scope);
        if (baseline == null) {
            baseline = new Baseline();
            baseline.scope = scope;
            result.put(scope, baseline);
        }
        Double existing = baseline.avgUsec.get(command);
        // 同一分组内多行（不同 major/arch 汇总到上层时）取均值
        baseline.avgUsec.put(command, existing == null ? avg : (existing + avg) / 2);
        baseline.instanceCount = Math.max(baseline.instanceCount, instances);
    }

    private String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private long num(Object value) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        try {
            return value == null ? 0L : Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
