package com.shcj.cache.web.service.impl;

import com.alibaba.fastjson.JSON;
import com.shcj.cache.constant.InstanceStatusEnum;
import com.shcj.cache.dao.MachineDao;
import com.shcj.cache.dao.MachineStatsDao;
import com.shcj.cache.entity.AppDesc;
import com.shcj.cache.entity.ExternalRedis;
import com.shcj.cache.entity.InstanceInfo;
import com.shcj.cache.entity.MachineInfo;
import com.shcj.cache.entity.InstanceSlowLog;
import com.shcj.cache.entity.InstanceStats;
import com.shcj.cache.entity.MachineStats;
import com.shcj.cache.util.ConstUtils;
import com.shcj.cache.redis.AssistRedisService;
import com.shcj.cache.redis.RedisCenter;
import com.shcj.cache.redis.cli.RedisCliConfigHelper;
import com.shcj.cache.ssh.SSHService;
import com.shcj.cache.ssh.SSHTemplate;
import com.shcj.cache.stats.app.AppStatsCenter;
import com.shcj.cache.stats.instance.InstanceStatsCenter;
import com.shcj.cache.task.tasks.daily.TopologyExamTask;
import com.shcj.cache.util.TypeUtil;
import com.shcj.cache.web.enums.FaultDiagnosticLevelEnum;
import com.shcj.cache.web.service.AiChatService;
import com.shcj.cache.web.service.AppService;
import com.shcj.cache.web.service.FaultDiagnosticService;
import com.shcj.cache.web.vo.FaultDiagnosticCheckVO;
import com.shcj.cache.web.vo.FaultDiagnosticReportVO;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.time.DateUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service("faultDiagnosticService")
public class FaultDiagnosticServiceImpl implements FaultDiagnosticService {

    private static final Logger logger = LoggerFactory.getLogger(FaultDiagnosticServiceImpl.class);

    private static final String REPORT_KEY_PREFIX = "fault:diag:report:";
    private static final String HISTORY_KEY_PREFIX = "fault:diag:history:";
    private static final int REPORT_TTL_SEC = 7 * 24 * 3600;
    private static final int HISTORY_MAX = 20;
    private static final int REPL_CMD_TIMEOUT_MS = 15000;
    private static final double MEMORY_WARN_RATIO = 90.0;
    private static final long SLOWLOG_WARN_TOTAL = 50L;
    private static final long BACKLOG_WARN_MIN_BYTES = 10L * 1024 * 1024;
    private static final double OUTPUT_BUFFER_WARN_RATIO = 0.85;

    private static final double DISK_WARN_RATIO = 85.0;

    @Autowired
    private AppService appService;
    @Autowired
    private InstanceStatsCenter instanceStatsCenter;
    @Autowired
    private AppStatsCenter appStatsCenter;
    @Autowired
    private RedisCenter redisCenter;
    @Autowired
    private AssistRedisService assistRedisService;
    @Autowired
    private SSHService sshService;
    @Autowired
    private MachineStatsDao machineStatsDao;
    @Autowired
    private MachineDao machineDao;
    @Resource(name = "TopologyExamTask")
    private TopologyExamTask topologyExamTask;
    @Resource(name = "aiChatService")
    private AiChatService aiChatService;

    @Override
    public FaultDiagnosticReportVO run(long appId, String scenario) {
        if (appId <= 0) {
            throw new IllegalArgumentException("appId 无效");
        }
        if (StringUtils.isBlank(scenario)) {
            scenario = SCENARIO_REPLICATION_BREAK;
        }
        long start = System.currentTimeMillis();
        AppDesc appDesc = appService.getByAppId(appId);
        if (appDesc == null) {
            throw new IllegalArgumentException("集群不存在: " + appId);
        }

        List<InstanceInfo> instances = appService.getAppInstanceInfo(appId);
        if (instances == null) {
            instances = Collections.emptyList();
        }
        boolean externalManaged = isExternalManaged(instances);
        // 纳管实例 host_id=0，但若节点 IP 已在 machine_info 且配置了 SSH，仍可做主机层检查
        boolean sshCapable = hasSshForInstanceHosts(instances);

        List<FaultDiagnosticCheckVO> checks = new ArrayList<FaultDiagnosticCheckVO>();
        int order = 0;
        order = appendCheck(checks, checkPlatformHeartbeat(instances), order);
        order = appendCheck(checks, checkPlatformMemory(appId, instances), order);
        order = appendCheck(checks, checkPlatformSlowLog(appId), order);
        order = appendCheck(checks, checkRedisReplication(appId, instances), order);
        order = appendCheck(checks, checkRedisAuth(appId, instances), order);
        order = appendCheck(checks, checkRedisBacklog(appId, instances), order);
        order = appendCheck(checks, checkRedisOutputBuffer(appId, instances), order);
        order = appendCheck(checks, checkRedisMasterPressure(appId, instances), order);
        order = appendCheck(checks, checkTopologyStructure(appDesc), order);
        order = appendCheck(checks, checkHostSshCapability(externalManaged, sshCapable, instances), order);
        order = appendCheck(checks, checkNetworkTcp(appId, instances, sshCapable), order);
        order = appendCheck(checks, checkNetworkPing(instances, sshCapable), order);
        order = appendCheck(checks, checkHostProcess(instances, sshCapable), order);
        order = appendCheck(checks, checkHostOom(instances, sshCapable), order);
        order = appendCheck(checks, checkHostDisk(instances, sshCapable), order);
        order = appendCheck(checks, checkHostSystem(instances, sshCapable), order);
        order = appendCheck(checks, checkClusterAnnounce(appId, appDesc, instances), order);

        FaultDiagnosticReportVO report = new FaultDiagnosticReportVO();
        report.setReportId(UUID.randomUUID().toString().replace("-", ""));
        report.setAppId(appId);
        report.setAppName(appDesc.getName());
        report.setAppTypeDesc(appDesc.getTypeDesc());
        report.setScenario(scenario);
        report.setScenarioLabel(resolveScenarioLabel(scenario));
        report.setExternalManaged(externalManaged);
        report.setSshCapable(sshCapable);
        report.setDurationMs(System.currentTimeMillis() - start);
        report.setCreateTime(new Date());
        report.setChecks(checks);
        fillLevelCounts(report);
        saveReport(report);
        appendHistory(appId, report.getReportId());
        return report;
    }

    @Override
    public boolean isSshCapableForApp(long appId) {
        if (appId <= 0) {
            return false;
        }
        List<InstanceInfo> instances = appService.getAppInstanceInfo(appId);
        if (instances == null) {
            instances = Collections.emptyList();
        }
        return hasSshForInstanceHosts(instances);
    }

    @Override
    public boolean isExternalManagedForApp(long appId) {
        if (appId <= 0) {
            return false;
        }
        List<InstanceInfo> instances = appService.getAppInstanceInfo(appId);
        if (instances == null) {
            instances = Collections.emptyList();
        }
        return isExternalManaged(instances);
    }

    @Override
    public FaultDiagnosticReportVO getReport(String reportId) {
        if (StringUtils.isBlank(reportId)) {
            return null;
        }
        String key = REPORT_KEY_PREFIX + reportId;
        try {
            String json = assistRedisService.getWithNoSerialize(key);
            if (StringUtils.isNotBlank(json)) {
                return JSON.parseObject(json, FaultDiagnosticReportVO.class);
            }
        } catch (Exception e) {
            logger.warn("parse fault diag report json failed reportId={}: {}", reportId, e.getMessage());
        }
        try {
            return assistRedisService.get(key);
        } catch (Exception e) {
            logger.warn("load fault diag report legacy failed reportId={}: {}", reportId, e.getMessage());
            return null;
        }
    }

    @Override
    public FaultDiagnosticReportVO resolveReport(String reportId, String reportJson) {
        FaultDiagnosticReportVO report = getReport(reportId);
        if (report != null) {
            return report;
        }
        if (StringUtils.isBlank(reportJson)) {
            return null;
        }
        try {
            report = JSON.parseObject(reportJson, FaultDiagnosticReportVO.class);
        } catch (Exception e) {
            logger.warn("parse client fault diag report failed reportId={}: {}", reportId, e.getMessage());
            return null;
        }
        if (report == null || StringUtils.isBlank(report.getReportId())) {
            return null;
        }
        if (StringUtils.isNotBlank(reportId) && !reportId.equals(report.getReportId())) {
            logger.warn("client reportId mismatch expect={} actual={}", reportId, report.getReportId());
            return null;
        }
        saveReport(report);
        return report;
    }

    @Override
    public List<FaultDiagnosticReportVO> listHistory(long appId, int limit) {
        if (appId <= 0 || limit <= 0) {
            return Collections.emptyList();
        }
        List<String> ids = loadHistoryIds(HISTORY_KEY_PREFIX + appId);
        if (ids.isEmpty()) {
            return Collections.emptyList();
        }
        List<FaultDiagnosticReportVO> list = new ArrayList<FaultDiagnosticReportVO>();
        int end = Math.min(ids.size(), limit);
        for (int i = 0; i < end; i++) {
            FaultDiagnosticReportVO report = getReport(ids.get(i));
            if (report != null) {
                list.add(report);
            }
        }
        return list;
    }

    @Override
    public String generateAiSummary(String reportId) {
        FaultDiagnosticReportVO report = getReport(reportId);
        if (report == null) {
            throw new IllegalArgumentException("诊断报告不存在或已过期");
        }
        return generateAiSummaryForReport(report);
    }

    @Override
    public String generateAiSummaryForReport(FaultDiagnosticReportVO report) {
        if (report == null) {
            throw new IllegalArgumentException("诊断报告不存在或已过期");
        }
        if (StringUtils.isNotBlank(report.getAiSummary())) {
            return report.getAiSummary();
        }
        if (!aiChatService.isEnabled()) {
            throw new IllegalStateException("AI 助手未启用，请配置 cachecloud.ai");
        }

        StringBuilder prompt = new StringBuilder();
        prompt.append("【任务】你是 Redis 运维专家。请基于下方「规则诊断结果」写 Layer② 根因分析报告。\n");
        prompt.append("【要求】\n");
        prompt.append("1. 必须引用检查项 code，不要编造未出现的指标\n");
        prompt.append("2. 根因最多 3 条，按优先级排序，标注置信度（高/中/低）\n");
        prompt.append("3. 修复建议分步骤，标注「可自动」「需人工」\n");
        prompt.append("4. 明确列出 SKIP 项及影响\n");
        prompt.append("5. 有风险操作必须警告\n");
        prompt.append("6. 使用 Markdown，含：## 根因分析、## 修复建议、## 风险提示\n\n");
        prompt.append("【场景】").append(report.getScenarioLabel()).append('\n');
        prompt.append("【应用】appId=").append(report.getAppId())
                .append(" 名称=").append(report.getAppName())
                .append(" 类型=").append(report.getAppTypeDesc()).append('\n');
        if (report.isExternalManaged() && report.isSshCapable()) {
            prompt.append("【纳管模式】外部纳管 Redis + 节点 IP 已关联 machine_info（可 SSH 主机检查）\n");
        } else if (report.isExternalManaged()) {
            prompt.append("【纳管模式】外部纳管（仅 Redis 协议层检查，无 SSH）\n");
        } else {
            prompt.append("【纳管模式】平台托管\n");
        }
        prompt.append("【统计】PASS=").append(report.getPassCount())
                .append(" WARN=").append(report.getWarnCount())
                .append(" FAIL=").append(report.getFailCount())
                .append(" SKIP=").append(report.getSkipCount()).append("\n\n");
        prompt.append("【检查结果 JSON】\n");
        prompt.append(JSON.toJSONString(report.getChecks()));

        List<Map<String, String>> messages = new ArrayList<Map<String, String>>();
        Map<String, String> userMsg = new HashMap<String, String>();
        userMsg.put("role", "user");
        userMsg.put("content", prompt.toString());
        messages.add(userMsg);

        String summary = aiChatService.chat(messages);
        report.setAiSummary(summary);
        saveReport(report);
        return summary;
    }

    private int appendCheck(List<FaultDiagnosticCheckVO> checks, FaultDiagnosticCheckVO item, int order) {
        if (item == null) {
            return order;
        }
        item.setOrder(++order);
        checks.add(item);
        return order;
    }

    private FaultDiagnosticCheckVO checkPlatformHeartbeat(List<InstanceInfo> instances) {
        FaultDiagnosticCheckVO vo = baseCheck("platform_heartbeat", "节点心跳 / 在线状态", "平台快照");
        List<String> down = new ArrayList<String>();
        for (InstanceInfo inst : instances) {
            if (inst == null || TypeUtil.isRedisSentinel(inst.getType())) {
                continue;
            }
            if (inst.getStatus() == InstanceStatusEnum.ERROR_STATUS.getStatus()) {
                down.add(inst.getIp() + ":" + inst.getPort() + "(id=" + inst.getId() + ")");
            }
        }
        if (down.isEmpty()) {
            vo.setLevel(FaultDiagnosticLevelEnum.PASS.getCode());
            vo.setSummary("数据节点均无异常");
        } else {
            vo.setLevel(FaultDiagnosticLevelEnum.FAIL.getCode());
            vo.setSummary("异常 " + down.size() + " 个");
            vo.setDetail(StringUtils.join(down, "；"));
        }
        return vo;
    }

    private FaultDiagnosticCheckVO checkPlatformMemory(long appId, List<InstanceInfo> instances) {
        FaultDiagnosticCheckVO vo = baseCheck("platform_memory", "内存使用率", "平台快照");
        List<String> high = new ArrayList<String>();
        for (InstanceInfo inst : instances) {
            if (inst == null || TypeUtil.isRedisSentinel(inst.getType())) {
                continue;
            }
            InstanceStats stats = instanceStatsCenter.getInstanceStats(inst.getId());
            if (stats == null) {
                continue;
            }
            if (stats.getMemUsePercent() >= MEMORY_WARN_RATIO) {
                high.add(inst.getIp() + ":" + inst.getPort() + " " + stats.getMemUsePercent() + "%");
            }
        }
        if (high.isEmpty()) {
            vo.setLevel(FaultDiagnosticLevelEnum.PASS.getCode());
            vo.setSummary("内存使用率正常（< " + (int) MEMORY_WARN_RATIO + "%）");
        } else {
            vo.setLevel(FaultDiagnosticLevelEnum.WARN.getCode());
            vo.setSummary("接近 maxmemory " + high.size() + " 个节点");
            vo.setDetail(StringUtils.join(high, "；"));
        }
        return vo;
    }

    private FaultDiagnosticCheckVO checkPlatformSlowLog(long appId) {
        FaultDiagnosticCheckVO vo = baseCheck("platform_slowlog", "近 24 小时慢查询", "平台快照");
        try {
            Date end = new Date();
            Date start = DateUtils.addDays(end, -1);
            Map<String, Long> countMap = appStatsCenter.getInstanceSlowLogCountMapByAppId(appId, start, end);
            long total = 0;
            if (countMap != null) {
                for (Long c : countMap.values()) {
                    if (c != null) {
                        total += c;
                    }
                }
            }
            if (total <= SLOWLOG_WARN_TOTAL) {
                vo.setLevel(FaultDiagnosticLevelEnum.PASS.getCode());
                vo.setSummary("慢查询共 " + total + " 条");
            } else {
                vo.setLevel(FaultDiagnosticLevelEnum.WARN.getCode());
                vo.setSummary("慢查询偏多：" + total + " 条");
                List<InstanceSlowLog> logs = appStatsCenter.getInstanceSlowLogByAppId(appId, start, end);
                if (logs != null && !logs.isEmpty()) {
                    InstanceSlowLog sample = logs.get(0);
                    vo.setDetail("样例 " + sample.getIp() + ":" + sample.getPort()
                            + " 耗时 " + sample.getCostTime() + "us");
                }
            }
        } catch (Exception e) {
            vo.setLevel(FaultDiagnosticLevelEnum.WARN.getCode());
            vo.setSummary("慢查询数据读取失败");
            vo.setDetail(e.getMessage());
        }
        return vo;
    }

    private FaultDiagnosticCheckVO checkRedisReplication(long appId, List<InstanceInfo> instances) {
        FaultDiagnosticCheckVO vo = baseCheck("redis_replication", "复制链路 INFO replication", "Redis 复制");
        List<String> lines = new ArrayList<String>();
        StringBuilder raw = new StringBuilder();
        boolean anyFail = false;
        boolean anyWarn = false;
        int probed = 0;

        for (InstanceInfo inst : instances) {
            if (inst == null || TypeUtil.isRedisSentinel(inst.getType())) {
                continue;
            }
            probed++;
            String result = safeInfoReplication(appId, inst);
            if (StringUtils.isBlank(result) || result.startsWith("命令执行失败")) {
                anyFail = true;
                lines.add(inst.getIp() + ":" + inst.getPort() + " 无法获取 INFO replication");
                continue;
            }
            raw.append("# ").append(inst.getIp()).append(':').append(inst.getPort()).append('\n');
            raw.append(abbreviate(result, 800)).append("\n\n");

            String role = pickInfoField(result, "role");
            if ("slave".equalsIgnoreCase(role)) {
                String link = pickInfoField(result, "master_link_status");
                if (!"up".equalsIgnoreCase(link)) {
                    anyFail = true;
                    lines.add(inst.getIp() + ":" + inst.getPort() + " master_link_status=" + link);
                } else {
                    String lag = pickInfoField(result, "master_last_io_seconds_ago");
                    lines.add(inst.getIp() + ":" + inst.getPort() + " 从节点复制正常 lag_io=" + lag);
                }
            } else if ("master".equalsIgnoreCase(role)) {
                String slaves = pickInfoField(result, "connected_slaves");
                if ("0".equals(slaves)) {
                    anyWarn = true;
                    lines.add(inst.getIp() + ":" + inst.getPort() + " connected_slaves=0");
                } else {
                    lines.add(inst.getIp() + ":" + inst.getPort() + " 主节点 connected_slaves=" + slaves);
                }
            }
        }

        if (probed == 0) {
            vo.setLevel(FaultDiagnosticLevelEnum.SKIP.getCode());
            vo.setSkipped(true);
            vo.setSkipReason("无 Redis 数据节点");
            vo.setSummary("跳过");
            return vo;
        }
        if (anyFail) {
            vo.setLevel(FaultDiagnosticLevelEnum.FAIL.getCode());
            vo.setSummary("复制链路异常");
        } else if (anyWarn) {
            vo.setLevel(FaultDiagnosticLevelEnum.WARN.getCode());
            vo.setSummary("主从连接需关注");
        } else {
            vo.setLevel(FaultDiagnosticLevelEnum.PASS.getCode());
            vo.setSummary("复制链路正常");
        }
        vo.setDetail(StringUtils.join(lines, "；"));
        vo.setRaw(raw.toString().trim());
        return vo;
    }

    private FaultDiagnosticCheckVO checkRedisAuth(long appId, List<InstanceInfo> instances) {
        FaultDiagnosticCheckVO vo = baseCheck("redis_auth", "认证 / ACL", "Redis 配置");
        List<InstanceInfo> dataNodes = filterDataNodes(instances);
        if (dataNodes.isEmpty()) {
            return skipCheck(vo, "无 Redis 数据节点");
        }

        InstanceInfo master = findNodeByRole(dataNodes, "master");
        List<InstanceInfo> slaves = findNodesByRole(dataNodes, "slave");
        List<String> lines = new ArrayList<String>();
        StringBuilder raw = new StringBuilder();
        boolean anyFail = false;
        boolean anyWarn = false;

        String masterRequirepass = null;
        if (master != null) {
            Map<String, String> cfg = configGet(appId, master, "requirepass");
            masterRequirepass = cfg.get("requirepass");
            raw.append("# master ").append(endpoint(master)).append(" requirepass=")
                    .append(maskSecret(masterRequirepass)).append('\n');
            lines.add(endpoint(master) + " requirepass=" + maskSecret(masterRequirepass));
        }

        for (InstanceInfo slave : slaves) {
            Map<String, String> authCfg = configGet(appId, slave, "masterauth");
            Map<String, String> userCfg = configGet(appId, slave, "masteruser");
            String masterauth = authCfg.get("masterauth");
            String masteruser = userCfg.get("masteruser");
            raw.append("# slave ").append(endpoint(slave))
                    .append(" masteruser=").append(StringUtils.defaultString(masteruser, "(default)"))
                    .append(" masterauth=").append(maskSecret(masterauth)).append('\n');

            boolean masterProtected = StringUtils.isNotBlank(masterRequirepass);
            if (masterProtected && StringUtils.isBlank(masterauth)) {
                anyFail = true;
                lines.add(endpoint(slave) + " 未配置 masterauth（主节点已设 requirepass）");
            } else {
                lines.add(endpoint(slave) + " masterauth=" + maskSecret(masterauth));
            }
        }

        InstanceInfo aclNode = master != null ? master : dataNodes.get(0);
        String aclResult = safeAdminCommand(appId, aclNode, "acl list");
        if (StringUtils.isNotBlank(aclResult) && !isCommandFailed(aclResult)) {
            if (aclResult.toLowerCase(Locale.ROOT).contains("err")) {
                lines.add("ACL LIST 不可用（可能 Redis < 6）");
            } else {
                raw.append("# ACL LIST (").append(endpoint(aclNode)).append(")\n");
                raw.append(abbreviate(aclResult, 600)).append('\n');
                if (!aclResult.contains("+psync") && !aclResult.contains("allcommands")
                        && aclResult.contains("user ")) {
                    anyWarn = true;
                    lines.add("ACL 已启用，请确认复制用户含 +psync/+replconf 权限");
                }
            }
        }

        if (anyFail) {
            vo.setLevel(FaultDiagnosticLevelEnum.FAIL.getCode());
            vo.setSummary("主从认证配置不一致");
        } else if (anyWarn) {
            vo.setLevel(FaultDiagnosticLevelEnum.WARN.getCode());
            vo.setSummary("认证配置需关注");
        } else {
            vo.setLevel(FaultDiagnosticLevelEnum.PASS.getCode());
            vo.setSummary("主从认证配置一致");
        }
        vo.setDetail(StringUtils.join(lines, "；"));
        if (raw.length() > 0) {
            vo.setRaw(raw.toString().trim());
        }
        return vo;
    }

    private FaultDiagnosticCheckVO checkRedisBacklog(long appId, List<InstanceInfo> instances) {
        FaultDiagnosticCheckVO vo = baseCheck("redis_backlog", "复制积压缓冲区", "Redis 复制");
        List<InstanceInfo> dataNodes = filterDataNodes(instances);
        InstanceInfo master = findNodeByRole(dataNodes, "master");
        if (master == null) {
            master = dataNodes.isEmpty() ? null : dataNodes.get(0);
        }
        if (master == null) {
            return skipCheck(vo, "无可用 master 节点");
        }

        Map<String, String> backlogCfg = configGet(appId, master, "repl-backlog-size");
        String backlogRaw = backlogCfg.get("repl-backlog-size");
        long backlogBytes = parseMemorySize(backlogRaw);

        String replInfo = safeInfoReplication(appId, master);
        if (isCommandFailed(replInfo)) {
            vo.setLevel(FaultDiagnosticLevelEnum.WARN.getCode());
            vo.setSummary("无法读取 master 复制信息");
            vo.setDetail(replInfo);
            return vo;
        }

        long masterOffset = parseLongSafe(pickInfoField(replInfo, "master_repl_offset"));
        List<String> lines = new ArrayList<String>();
        lines.add(endpoint(master) + " repl-backlog-size=" + formatBytes(backlogBytes)
                + " master_repl_offset=" + masterOffset);

        boolean anyWarn = false;
        boolean anyFail = false;
        StringBuilder raw = new StringBuilder();
        raw.append("repl-backlog-size=").append(backlogRaw).append('\n');
        raw.append(abbreviate(replInfo, 500)).append("\n\n");

        if (backlogBytes > 0 && backlogBytes < BACKLOG_WARN_MIN_BYTES) {
            anyWarn = true;
            lines.add("backlog 偏小（< 10MB），断连后易触发全量同步");
        }

        for (InstanceInfo inst : dataNodes) {
            if (!"slave".equalsIgnoreCase(StringUtils.defaultString(inst.getRoleDesc()))) {
                continue;
            }
            String slaveRepl = safeInfoReplication(appId, inst);
            if (isCommandFailed(slaveRepl)) {
                continue;
            }
            long slaveOffset = parseLongSafe(pickInfoField(slaveRepl, "slave_repl_offset"));
            long gap = masterOffset - slaveOffset;
            if (gap < 0) {
                gap = 0;
            }
            raw.append("# slave ").append(endpoint(inst)).append(" gap=").append(gap).append('\n');
            if (backlogBytes > 0 && gap > backlogBytes) {
                anyFail = true;
                lines.add(endpoint(inst) + " offset 差值 " + formatBytes(gap) + " > backlog");
            } else if (backlogBytes > 0 && gap > backlogBytes * 0.8) {
                anyWarn = true;
                lines.add(endpoint(inst) + " offset 差值 " + formatBytes(gap) + " 接近 backlog 上限");
            } else {
                lines.add(endpoint(inst) + " offset 差值 " + formatBytes(gap));
            }
        }

        if (anyFail) {
            vo.setLevel(FaultDiagnosticLevelEnum.FAIL.getCode());
            vo.setSummary("复制 offset 差值超过 backlog");
        } else if (anyWarn) {
            vo.setLevel(FaultDiagnosticLevelEnum.WARN.getCode());
            vo.setSummary("backlog 容量需关注");
        } else {
            vo.setLevel(FaultDiagnosticLevelEnum.PASS.getCode());
            vo.setSummary("backlog 与 offset 差值正常");
        }
        vo.setDetail(StringUtils.join(lines, "；"));
        vo.setRaw(raw.toString().trim());
        return vo;
    }

    private FaultDiagnosticCheckVO checkRedisOutputBuffer(long appId, List<InstanceInfo> instances) {
        FaultDiagnosticCheckVO vo = baseCheck("redis_output_buffer", "输出缓冲区（复制连接）", "Redis 复制");
        List<InstanceInfo> dataNodes = filterDataNodes(instances);
        InstanceInfo master = findNodeByRole(dataNodes, "master");
        if (master == null) {
            return skipCheck(vo, "未识别 master 节点");
        }

        Map<String, String> bufCfg = configGet(appId, master, "client-output-buffer-limit");
        String limitRaw = bufCfg.get("client-output-buffer-limit");
        long slaveHardLimit = parseSlaveOutputBufferHardLimit(limitRaw);

        String clientList = safeAdminCommand(appId, master, "client list");
        if (isCommandFailed(clientList)) {
            vo.setLevel(FaultDiagnosticLevelEnum.WARN.getCode());
            vo.setSummary("无法获取 CLIENT LIST");
            vo.setDetail(clientList);
            return vo;
        }

        List<String> lines = new ArrayList<String>();
        if (StringUtils.isNotBlank(limitRaw)) {
            lines.add("client-output-buffer-limit: " + abbreviate(limitRaw, 120));
        }
        boolean anyWarn = false;
        boolean anyFail = false;
        StringBuilder raw = new StringBuilder();
        raw.append("client-output-buffer-limit=").append(limitRaw).append("\n\n");

        for (String line : clientList.split("\\r?\\n")) {
            if (StringUtils.isBlank(line) || (!line.contains("flags=S") && !line.contains(",slave,"))) {
                continue;
            }
            long omem = parseClientFieldLong(line, "omem");
            if (omem <= 0) {
                continue;
            }
            raw.append(line).append('\n');
            String addr = parseClientField(line, "addr");
            if (slaveHardLimit > 0 && omem >= slaveHardLimit) {
                anyFail = true;
                lines.add("复制连接 " + addr + " omem=" + formatBytes(omem) + " 已达硬限制");
            } else if (slaveHardLimit > 0 && omem >= slaveHardLimit * OUTPUT_BUFFER_WARN_RATIO) {
                anyWarn = true;
                lines.add("复制连接 " + addr + " omem=" + formatBytes(omem)
                        + " (" + (omem * 100 / slaveHardLimit) + "% 硬限制)");
            } else {
                lines.add("复制连接 " + addr + " omem=" + formatBytes(omem));
            }
        }

        if (lines.size() <= (StringUtils.isNotBlank(limitRaw) ? 1 : 0)) {
            lines.add("当前无活跃复制连接或 omem=0");
        }

        if (anyFail) {
            vo.setLevel(FaultDiagnosticLevelEnum.FAIL.getCode());
            vo.setSummary("复制输出缓冲区溢出");
        } else if (anyWarn) {
            vo.setLevel(FaultDiagnosticLevelEnum.WARN.getCode());
            vo.setSummary("复制输出缓冲区接近上限");
        } else {
            vo.setLevel(FaultDiagnosticLevelEnum.PASS.getCode());
            vo.setSummary("复制输出缓冲区正常");
        }
        vo.setDetail(StringUtils.join(lines, "；"));
        vo.setRaw(raw.toString().trim());
        return vo;
    }

    private FaultDiagnosticCheckVO checkNetworkTcp(long appId, List<InstanceInfo> instances, boolean sshCapable) {
        FaultDiagnosticCheckVO vo = baseCheck("network_tcp", "TCP 端口连通（主→从）", "网络层");
        FaultDiagnosticCheckVO skip = skipIfNoSsh(vo, sshCapable);
        if (skip != null) {
            return skip;
        }

        InstanceInfo master = findNodeByRole(filterDataNodes(instances), "master");
        List<InstanceInfo> slaves = findNodesByRole(filterDataNodes(instances), "slave");
        if (master == null || slaves.isEmpty()) {
            return skipCheck(vo, "无 master/slave 成对节点");
        }

        String masterHost = master.getIp();
        List<String> lines = new ArrayList<String>();
        boolean anyFail = false;
        for (InstanceInfo slave : slaves) {
            String cmd = String.format(Locale.ROOT,
                    "timeout 3 bash -c '</dev/tcp/%s/%d' && echo TCP_OK || echo TCP_FAIL",
                    slave.getIp(), slave.getPort());
            String result = executeShellQuiet(masterHost, cmd);
            boolean ok = result != null && result.contains("TCP_OK");
            if (!ok) {
                anyFail = true;
                lines.add(masterHost + " → " + endpoint(slave) + " TCP_FAIL");
            } else {
                lines.add(masterHost + " → " + endpoint(slave) + " TCP_OK");
            }
        }

        if (anyFail) {
            vo.setLevel(FaultDiagnosticLevelEnum.FAIL.getCode());
            vo.setSummary("主节点到从节点 TCP 不通");
        } else {
            vo.setLevel(FaultDiagnosticLevelEnum.PASS.getCode());
            vo.setSummary("主→从 TCP 连通正常");
        }
        vo.setDetail(StringUtils.join(lines, "；"));
        return vo;
    }

    private FaultDiagnosticCheckVO checkNetworkPing(List<InstanceInfo> instances, boolean sshCapable) {
        FaultDiagnosticCheckVO vo = baseCheck("network_ping", "Ping / 丢包", "网络层");
        FaultDiagnosticCheckVO skip = skipIfNoSsh(vo, sshCapable);
        if (skip != null) {
            return skip;
        }
        InstanceInfo master = findNodeByRole(filterDataNodes(instances), "master");
        List<InstanceInfo> slaves = findNodesByRole(filterDataNodes(instances), "slave");
        if (master == null || slaves.isEmpty()) {
            return skipCheck(vo, "无 master/slave 成对节点");
        }
        String masterHost = master.getIp();
        List<String> lines = new ArrayList<String>();
        boolean anyFail = false;
        boolean anyWarn = false;
        for (InstanceInfo slave : slaves) {
            String cmd = String.format(Locale.ROOT,
                    "ping -c 4 -W 2 %s 2>&1 | grep -E 'packet loss|rtt min'", slave.getIp());
            String result = executeShellQuiet(masterHost, cmd);
            if (StringUtils.isBlank(result)) {
                anyFail = true;
                lines.add(masterHost + " → " + slave.getIp() + " 无 ping 结果");
                continue;
            }
            int loss = parsePacketLossPercent(result);
            if (loss < 0) {
                anyFail = true;
                lines.add(masterHost + " → " + slave.getIp() + " ping 失败");
            } else if (loss >= 100) {
                anyFail = true;
                lines.add(masterHost + " → " + slave.getIp() + " 丢包 " + loss + "%");
            } else if (loss > 0) {
                anyWarn = true;
                lines.add(masterHost + " → " + slave.getIp() + " 丢包 " + loss + "%");
            } else {
                lines.add(masterHost + " → " + slave.getIp() + " 丢包 0%");
            }
        }
        setLevelByFlags(vo, anyFail, anyWarn, "Ping 不可达", "存在丢包", "Ping 正常");
        vo.setDetail(StringUtils.join(lines, "；"));
        return vo;
    }

    private FaultDiagnosticCheckVO checkHostProcess(List<InstanceInfo> instances, boolean sshCapable) {
        FaultDiagnosticCheckVO vo = baseCheck("host_process", "Redis 进程", "主机");
        FaultDiagnosticCheckVO skip = skipIfNoSsh(vo, sshCapable);
        if (skip != null) {
            return skip;
        }
        List<InstanceInfo> dataNodes = filterDataNodes(instances);
        if (dataNodes.isEmpty()) {
            return skipCheck(vo, "无 Redis 数据节点");
        }
        List<String> lines = new ArrayList<String>();
        boolean anyFail = false;
        StringBuilder raw = new StringBuilder();
        for (InstanceInfo inst : dataNodes) {
            String cmd = String.format(Locale.ROOT,
                    "ps -ef | grep '[r]edis-server' | grep -E '[:\\s]%d' || echo PROCESS_NOT_FOUND",
                    inst.getPort());
            String result = executeShellQuiet(inst.getIp(), cmd);
            raw.append("# ").append(endpoint(inst)).append('\n').append(result).append("\n\n");
            if (StringUtils.isBlank(result) || result.contains("PROCESS_NOT_FOUND")) {
                anyFail = true;
                lines.add(endpoint(inst) + " 未找到 redis-server 进程");
            } else {
                lines.add(endpoint(inst) + " 进程存在");
            }
        }
        if (anyFail) {
            vo.setLevel(FaultDiagnosticLevelEnum.FAIL.getCode());
            vo.setSummary("部分节点 Redis 进程不存在");
        } else {
            vo.setLevel(FaultDiagnosticLevelEnum.PASS.getCode());
            vo.setSummary("各节点 Redis 进程正常");
        }
        vo.setDetail(StringUtils.join(lines, "；"));
        vo.setRaw(raw.toString().trim());
        return vo;
    }

    private FaultDiagnosticCheckVO checkHostOom(List<InstanceInfo> instances, boolean sshCapable) {
        FaultDiagnosticCheckVO vo = baseCheck("host_oom", "OOM 检查", "主机");
        FaultDiagnosticCheckVO skip = skipIfNoSsh(vo, sshCapable);
        if (skip != null) {
            return skip;
        }
        Set<String> hosts = uniqueHostIps(filterDataNodes(instances));
        if (hosts.isEmpty()) {
            return skipCheck(vo, "无可用主机");
        }
        List<String> lines = new ArrayList<String>();
        boolean anyWarn = false;
        StringBuilder raw = new StringBuilder();
        for (String host : hosts) {
            String cmd = "dmesg -T 2>/dev/null | grep -iE 'out of memory|killed process' | grep -i redis | tail -5";
            String result = executeShellQuiet(host, cmd);
            raw.append("# ").append(host).append('\n');
            if (StringUtils.isNotBlank(result)) {
                raw.append(result).append('\n');
                anyWarn = true;
                lines.add(host + " 近期有 OOM/Kill Redis 记录");
            } else {
                lines.add(host + " 无近期 Redis OOM 记录");
            }
        }
        if (anyWarn) {
            vo.setLevel(FaultDiagnosticLevelEnum.WARN.getCode());
            vo.setSummary("存在 OOM 杀进程记录");
        } else {
            vo.setLevel(FaultDiagnosticLevelEnum.PASS.getCode());
            vo.setSummary("未发现近期 Redis OOM");
        }
        vo.setDetail(StringUtils.join(lines, "；"));
        vo.setRaw(raw.toString().trim());
        return vo;
    }

    private FaultDiagnosticCheckVO checkHostDisk(List<InstanceInfo> instances, boolean sshCapable) {
        FaultDiagnosticCheckVO vo = baseCheck("host_disk", "磁盘空间", "主机");
        Set<String> hosts = uniqueHostIps(filterDataNodes(instances));
        if (hosts.isEmpty()) {
            return skipCheck(vo, "无可用主机");
        }
        List<String> lines = new ArrayList<String>();
        boolean anyFail = false;
        boolean anyWarn = false;
        StringBuilder raw = new StringBuilder();
        for (String host : hosts) {
            MachineStats stats = machineStatsDao.getMachineStatsByIp(host);
            if (stats != null && stats.getDiskUsageMap() != null && !stats.getDiskUsageMap().isEmpty()) {
                for (Map.Entry<String, String> entry : stats.getDiskUsageMap().entrySet()) {
                    int pct = parsePercent(entry.getValue());
                    if (pct >= 95) {
                        anyFail = true;
                    } else if (pct >= DISK_WARN_RATIO) {
                        anyWarn = true;
                    }
                    lines.add(host + " " + entry.getKey() + " 平台采集 " + entry.getValue());
                }
            }
            if (sshCapable) {
                String df = executeShellQuiet(host, "df -hP / 2>/dev/null | awk 'NR==2{print $5\" \"$6}'");
                if (StringUtils.isNotBlank(df)) {
                    raw.append("# ").append(host).append(" df: ").append(df).append('\n');
                    int pct = parsePercent(df);
                    if (pct >= 95) {
                        anyFail = true;
                        lines.add(host + " 根分区 " + pct + "%");
                    } else if (pct >= DISK_WARN_RATIO) {
                        anyWarn = true;
                        lines.add(host + " 根分区 " + pct + "%");
                    }
                }
            }
        }
        if (lines.isEmpty() && !sshCapable) {
            return skipCheck(vo, "节点 IP 未在 machine_info 登记，且无平台磁盘采集");
        }
        setLevelByFlags(vo, anyFail, anyWarn, "磁盘空间不足", "磁盘使用率偏高", "磁盘空间正常");
        if (lines.isEmpty()) {
            vo.setLevel(FaultDiagnosticLevelEnum.WARN.getCode());
            vo.setSummary("无磁盘采集数据");
        } else {
            vo.setDetail(StringUtils.join(lines, "；"));
        }
        if (raw.length() > 0) {
            vo.setRaw(raw.toString().trim());
        }
        return vo;
    }

    private FaultDiagnosticCheckVO checkHostSystem(List<InstanceInfo> instances, boolean sshCapable) {
        FaultDiagnosticCheckVO vo = baseCheck("host_system", "THP / overcommit", "主机");
        FaultDiagnosticCheckVO skip = skipIfNoSsh(vo, sshCapable);
        if (skip != null) {
            return skip;
        }
        Set<String> hosts = uniqueHostIps(filterDataNodes(instances));
        if (hosts.isEmpty()) {
            return skipCheck(vo, "无可用主机");
        }
        List<String> lines = new ArrayList<String>();
        boolean anyWarn = false;
        StringBuilder raw = new StringBuilder();
        for (String host : hosts) {
            String thp = executeShellQuiet(host,
                    "cat /sys/kernel/mm/transparent_hugepage/enabled 2>/dev/null");
            String over = executeShellQuiet(host, "cat /proc/sys/vm/overcommit_memory 2>/dev/null");
            raw.append("# ").append(host).append(" THP=").append(thp)
                    .append(" overcommit=").append(over).append('\n');
            if (StringUtils.isNotBlank(thp) && !thp.contains("[never]")) {
                anyWarn = true;
                lines.add(host + " THP 非 never：" + abbreviate(thp, 40));
            }
            if (StringUtils.isNotBlank(over) && !"1".equals(over.trim())) {
                anyWarn = true;
                lines.add(host + " overcommit_memory=" + over.trim() + "（建议 1）");
            }
            if (StringUtils.isBlank(thp) && StringUtils.isBlank(over)) {
                lines.add(host + " 无法读取系统参数");
            }
        }
        if (anyWarn) {
            vo.setLevel(FaultDiagnosticLevelEnum.WARN.getCode());
            vo.setSummary("系统内核参数需优化");
        } else {
            vo.setLevel(FaultDiagnosticLevelEnum.PASS.getCode());
            vo.setSummary("THP / overcommit 正常");
        }
        vo.setDetail(StringUtils.join(lines, "；"));
        vo.setRaw(raw.toString().trim());
        return vo;
    }

    private FaultDiagnosticCheckVO checkClusterAnnounce(long appId, AppDesc appDesc,
                                                        List<InstanceInfo> instances) {
        FaultDiagnosticCheckVO vo = baseCheck("cluster_announce", "Cluster announce / 节点 IP", "Cluster");
        if (appDesc == null || appDesc.getType() != ConstUtils.CACHE_TYPE_REDIS_CLUSTER) {
            return skipCheck(vo, "非 Cluster 应用");
        }
        List<InstanceInfo> dataNodes = filterDataNodes(instances);
        if (dataNodes.isEmpty()) {
            return skipCheck(vo, "无 Cluster 数据节点");
        }
        InstanceInfo probe = dataNodes.get(0);
        List<String> lines = new ArrayList<String>();
        boolean anyFail = false;
        boolean anyWarn = false;
        StringBuilder raw = new StringBuilder();

        String nodes = safeAdminCommand(appId, probe, "cluster nodes");
        if (!isCommandFailed(nodes)) {
            raw.append(nodes).append("\n\n");
            for (String line : nodes.split("\\r?\\n")) {
                if (StringUtils.isBlank(line)) {
                    continue;
                }
                if (line.contains("127.0.0.1") || line.contains("?:") || line.contains(" 0.0.0.0:")) {
                    anyFail = true;
                    lines.add("异常节点行: " + abbreviate(line, 80));
                }
            }
        } else {
            anyWarn = true;
            lines.add("CLUSTER NODES 不可用");
        }

        Map<String, String> announceCfg = configGet(appId, probe, "cluster-announce-ip");
        String announceIp = announceCfg.get("cluster-announce-ip");
        if (StringUtils.isNotBlank(announceIp) && !"(nil)".equalsIgnoreCase(announceIp)) {
            lines.add("cluster-announce-ip=" + announceIp);
        } else {
            anyWarn = true;
            lines.add("未配置 cluster-announce-ip（NAT/Docker 环境建议配置）");
        }

        String infoServer = safeAdminCommand(appId, probe, "info server");
        if (!isCommandFailed(infoServer)) {
            String version = pickInfoField(infoServer, "redis_version");
            lines.add("redis_version=" + version);
            if (StringUtils.isNotBlank(version) && compareVersion(version, "7.0.5") < 0) {
                anyWarn = true;
                lines.add("版本 < 7.0.5，可能命中已知 Cluster BUG");
            }
        }

        setLevelByFlags(vo, anyFail, anyWarn, "Cluster 节点 IP 异常", "Cluster 配置需关注", "Cluster announce 正常");
        vo.setDetail(StringUtils.join(lines, "；"));
        if (raw.length() > 0) {
            vo.setRaw(raw.toString().trim());
        }
        return vo;
    }

    private FaultDiagnosticCheckVO skipIfNoSsh(FaultDiagnosticCheckVO vo, boolean sshCapable) {
        if (!sshCapable) {
            return skipCheck(vo, "节点 IP 未在 machine_info 登记或未配置 SSH");
        }
        return null;
    }

    private Set<String> uniqueHostIps(List<InstanceInfo> dataNodes) {
        Set<String> hosts = new LinkedHashSet<String>();
        if (dataNodes != null) {
            for (InstanceInfo inst : dataNodes) {
                if (inst != null && StringUtils.isNotBlank(inst.getIp())) {
                    hosts.add(inst.getIp());
                }
            }
        }
        return hosts;
    }

    private void setLevelByFlags(FaultDiagnosticCheckVO vo, boolean anyFail, boolean anyWarn,
                                 String failSummary, String warnSummary, String passSummary) {
        if (anyFail) {
            vo.setLevel(FaultDiagnosticLevelEnum.FAIL.getCode());
            vo.setSummary(failSummary);
        } else if (anyWarn) {
            vo.setLevel(FaultDiagnosticLevelEnum.WARN.getCode());
            vo.setSummary(warnSummary);
        } else {
            vo.setLevel(FaultDiagnosticLevelEnum.PASS.getCode());
            vo.setSummary(passSummary);
        }
    }

    private static int parsePacketLossPercent(String pingOutput) {
        if (pingOutput == null) {
            return -1;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(\\d+)%\\s*packet loss").matcher(pingOutput);
        if (m.find()) {
            return Integer.parseInt(m.group(1));
        }
        if (pingOutput.toLowerCase(Locale.ROOT).contains("100% packet loss")) {
            return 100;
        }
        return -1;
    }

    private static int parsePercent(String text) {
        if (StringUtils.isBlank(text)) {
            return 0;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)").matcher(text);
        if (m.find()) {
            return Integer.parseInt(m.group(1));
        }
        return 0;
    }

    private static int compareVersion(String v1, String v2) {
        String[] a = v1.split("\\.");
        String[] b = v2.split("\\.");
        int len = Math.max(a.length, b.length);
        for (int i = 0; i < len; i++) {
            int x = i < a.length ? org.apache.commons.lang.math.NumberUtils.toInt(a[i], 0) : 0;
            int y = i < b.length ? org.apache.commons.lang.math.NumberUtils.toInt(b[i], 0) : 0;
            if (x != y) {
                return x - y;
            }
        }
        return 0;
    }

    private FaultDiagnosticCheckVO skipCheck(FaultDiagnosticCheckVO vo, String reason) {
        vo.setLevel(FaultDiagnosticLevelEnum.SKIP.getCode());
        vo.setSkipped(true);
        vo.setSkipReason(reason);
        vo.setSummary("跳过");
        return vo;
    }

    private List<InstanceInfo> filterDataNodes(List<InstanceInfo> instances) {
        List<InstanceInfo> list = new ArrayList<InstanceInfo>();
        if (instances == null) {
            return list;
        }
        for (InstanceInfo inst : instances) {
            if (inst != null && !TypeUtil.isRedisSentinel(inst.getType())) {
                list.add(inst);
            }
        }
        return list;
    }

    private InstanceInfo findNodeByRole(List<InstanceInfo> instances, String role) {
        if (instances == null || role == null) {
            return null;
        }
        for (InstanceInfo inst : instances) {
            if (role.equalsIgnoreCase(StringUtils.defaultString(inst.getRoleDesc()))) {
                return inst;
            }
        }
        return null;
    }

    private List<InstanceInfo> findNodesByRole(List<InstanceInfo> instances, String role) {
        List<InstanceInfo> list = new ArrayList<InstanceInfo>();
        if (instances == null || role == null) {
            return list;
        }
        for (InstanceInfo inst : instances) {
            if (role.equalsIgnoreCase(StringUtils.defaultString(inst.getRoleDesc()))) {
                list.add(inst);
            }
        }
        return list;
    }

    private String endpoint(InstanceInfo inst) {
        return inst.getIp() + ":" + inst.getPort();
    }

    private Map<String, String> configGet(long appId, InstanceInfo inst, String key) {
        String output = safeAdminCommand(appId, inst, "config get " + key);
        if (isCommandFailed(output)) {
            return Collections.emptyMap();
        }
        return RedisCliConfigHelper.parseConfigGet(output);
    }

    private String safeAdminCommand(long appId, InstanceInfo inst, String command) {
        try {
            return redisCenter.executeAdminCommand(appId, inst.getIp(), inst.getPort(),
                    command, REPL_CMD_TIMEOUT_MS);
        } catch (Exception e) {
            logger.warn("admin command failed {}:{} cmd={} {}", inst.getIp(), inst.getPort(), command, e.getMessage());
            return null;
        }
    }

    private boolean isCommandFailed(String result) {
        return StringUtils.isBlank(result) || result.startsWith("命令执行失败") || result.startsWith("not exist");
    }

    private String executeShellQuiet(String hostIp, String command) {
        try {
            SSHTemplate.Result result = sshService.executeWithResult(hostIp, command);
            return result != null && result.isSuccess() ? result.getResult() : null;
        } catch (Exception e) {
            logger.warn("ssh exec failed host={} cmd={}: {}", hostIp, command, e.getMessage());
            return null;
        }
    }

    private static String maskSecret(String value) {
        if (StringUtils.isBlank(value) || "(nil)".equalsIgnoreCase(value)) {
            return "未设置";
        }
        return "已设置(***)";
    }

    private static long parseMemorySize(String raw) {
        if (StringUtils.isBlank(raw) || "(nil)".equalsIgnoreCase(raw)) {
            return 0L;
        }
        String v = raw.trim().toLowerCase(Locale.ROOT);
        try {
            if (v.endsWith("gb")) {
                return (long) (Double.parseDouble(v.substring(0, v.length() - 2).trim()) * 1024 * 1024 * 1024);
            }
            if (v.endsWith("mb")) {
                return (long) (Double.parseDouble(v.substring(0, v.length() - 2).trim()) * 1024 * 1024);
            }
            if (v.endsWith("kb")) {
                return (long) (Double.parseDouble(v.substring(0, v.length() - 2).trim()) * 1024);
            }
            if (v.endsWith("b")) {
                return Long.parseLong(v.substring(0, v.length() - 1).trim());
            }
            return Long.parseLong(v.replaceAll("[^0-9]", ""));
        } catch (Exception e) {
            return org.apache.commons.lang.math.NumberUtils.toLong(v.replaceAll("[^0-9]", ""), 0L);
        }
    }

    private static long parseSlaveOutputBufferHardLimit(String configValue) {
        if (StringUtils.isBlank(configValue)) {
            return 0L;
        }
        String lower = configValue.toLowerCase(Locale.ROOT);
        int idx = lower.indexOf("slave ");
        if (idx < 0) {
            return 0L;
        }
        String tail = configValue.substring(idx + 6).trim();
        String[] parts = tail.split("\\s+");
        if (parts.length == 0) {
            return 0L;
        }
        return parseMemorySize(parts[0]);
    }

    private static long parseClientFieldLong(String clientLine, String field) {
        String val = parseClientField(clientLine, field);
        return org.apache.commons.lang.math.NumberUtils.toLong(val, 0L);
    }

    private static String parseClientField(String clientLine, String field) {
        if (clientLine == null || field == null) {
            return null;
        }
        String prefix = field + "=";
        for (String part : clientLine.split("\\s+")) {
            if (part.startsWith(prefix)) {
                return part.substring(prefix.length());
            }
        }
        return null;
    }

    private static long parseLongSafe(String val) {
        return org.apache.commons.lang.math.NumberUtils.toLong(StringUtils.trimToEmpty(val), 0L);
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + "B";
        }
        if (bytes < 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1fKB", bytes / 1024.0);
        }
        if (bytes < 1024L * 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1fMB", bytes / 1024.0 / 1024.0);
        }
        return String.format(Locale.ROOT, "%.2fGB", bytes / 1024.0 / 1024.0 / 1024.0);
    }

    private FaultDiagnosticCheckVO checkRedisMasterPressure(long appId, List<InstanceInfo> instances) {
        FaultDiagnosticCheckVO vo = baseCheck("redis_master_pressure", "主节点 QPS（instantaneous_ops）", "Redis 压力");
        List<String> lines = new ArrayList<String>();
        boolean anyHigh = false;
        for (InstanceInfo inst : instances) {
            if (inst == null || TypeUtil.isRedisSentinel(inst.getType())) {
                continue;
            }
            String roleDesc = inst.getRoleDesc();
            if (roleDesc == null || !"master".equalsIgnoreCase(roleDesc)) {
                continue;
            }
            String result = safeInfoStats(appId, inst);
            if (StringUtils.isBlank(result)) {
                continue;
            }
            String ops = pickInfoField(result, "instantaneous_ops_per_sec");
            lines.add(inst.getIp() + ":" + inst.getPort() + " ops=" + ops);
            if (StringUtils.isNotBlank(ops) && org.apache.commons.lang.math.NumberUtils.toLong(ops, 0) > 50000) {
                anyHigh = true;
            }
        }
        if (lines.isEmpty()) {
            vo.setLevel(FaultDiagnosticLevelEnum.SKIP.getCode());
            vo.setSkipped(true);
            vo.setSkipReason("未识别到 master 节点或 INFO stats 不可用");
            vo.setSummary("跳过");
            return vo;
        }
        vo.setDetail(StringUtils.join(lines, "；"));
        if (anyHigh) {
            vo.setLevel(FaultDiagnosticLevelEnum.WARN.getCode());
            vo.setSummary("主节点 QPS 偏高，可能影响复制");
        } else {
            vo.setLevel(FaultDiagnosticLevelEnum.PASS.getCode());
            vo.setSummary("主节点 QPS 正常");
        }
        return vo;
    }

    private FaultDiagnosticCheckVO checkTopologyStructure(AppDesc appDesc) {
        FaultDiagnosticCheckVO vo = baseCheck("topology_structure", "拓扑结构（引用拓扑诊断）", "拓扑结构");
        try {
            List<AppDesc> list = new ArrayList<AppDesc>();
            list.add(appDesc);
            Map<String, Object> info = topologyExamTask.check(list);
            List tips = info != null ? (List) info.get("tips") : null;
            int tipSize = tips != null ? tips.size() : 0;
            if (tipSize == 0) {
                vo.setLevel(FaultDiagnosticLevelEnum.PASS.getCode());
                vo.setSummary("拓扑结构检查通过");
            } else {
                vo.setLevel(FaultDiagnosticLevelEnum.WARN.getCode());
                vo.setSummary("拓扑结构问题 " + tipSize + " 项");
                vo.setDetail("详见「集群拓扑诊断」Tab；此处仅引用问题数量");
            }
        } catch (Exception e) {
            vo.setLevel(FaultDiagnosticLevelEnum.WARN.getCode());
            vo.setSummary("拓扑结构检查失败");
            vo.setDetail(e.getMessage());
        }
        return vo;
    }

    private FaultDiagnosticCheckVO checkHostSshCapability(boolean externalManaged, boolean sshCapable,
                                                          List<InstanceInfo> instances) {
        FaultDiagnosticCheckVO vo = baseCheck("host_ssh", "主机 SSH 能力", "主机");
        if (sshCapable) {
            vo.setLevel(FaultDiagnosticLevelEnum.PASS.getCode());
            if (externalManaged) {
                vo.setSummary("外部纳管 Redis，节点 IP 已关联 machine_info，可执行主机 SSH 检查");
                vo.setDetail(describeSshLinkedHosts(instances));
            } else {
                vo.setSummary("已关联平台托管主机，可执行网络/磁盘/进程检查");
            }
        } else {
            vo.setLevel(FaultDiagnosticLevelEnum.SKIP.getCode());
            vo.setSkipped(true);
            vo.setSkipReason("节点 IP 未在 machine_info 登记或未配置 SSH");
            vo.setSummary("跳过（无 SSH）");
            if (externalManaged) {
                vo.setDetail("纳管 Redis 仍可通过 Jedis 做协议层检查；主机检查需在机器管理中添加相同 IP 并配置 SSH");
            }
        }
        return vo;
    }

    private FaultDiagnosticCheckVO baseCheck(String code, String name, String group) {
        FaultDiagnosticCheckVO vo = new FaultDiagnosticCheckVO();
        vo.setCode(code);
        vo.setName(name);
        vo.setGroup(group);
        return vo;
    }

    private String safeInfoReplication(long appId, InstanceInfo inst) {
        try {
            return redisCenter.executeAdminCommand(appId, inst.getIp(), inst.getPort(),
                    "info replication", REPL_CMD_TIMEOUT_MS);
        } catch (Exception e) {
            logger.warn("info replication failed {}:{} {}", inst.getIp(), inst.getPort(), e.getMessage());
            return null;
        }
    }

    private String safeInfoStats(long appId, InstanceInfo inst) {
        try {
            return redisCenter.executeAdminCommand(appId, inst.getIp(), inst.getPort(),
                    "info stats", REPL_CMD_TIMEOUT_MS);
        } catch (Exception e) {
            return null;
        }
    }

    private static String pickInfoField(String info, String key) {
        if (info == null || key == null) {
            return null;
        }
        String prefix = key + ":";
        for (String line : info.split("\\r?\\n")) {
            if (line.startsWith(prefix)) {
                return line.substring(prefix.length()).trim();
            }
        }
        return null;
    }

    private static String abbreviate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max) + "...";
    }

    private boolean isExternalManaged(List<InstanceInfo> instances) {
        if (instances == null || instances.isEmpty()) {
            return true;
        }
        for (InstanceInfo inst : instances) {
            if (inst != null && inst.getHostId() != ExternalRedis.EXTERNAL_HOST_ID) {
                return false;
            }
        }
        return true;
    }

    private boolean hasSshForInstanceHosts(List<InstanceInfo> instances) {
        Set<String> hosts = uniqueHostIps(filterDataNodes(instances));
        if (hosts.isEmpty()) {
            return false;
        }
        for (String ip : hosts) {
            if (!hasMachineSshByIp(ip)) {
                return false;
            }
        }
        return true;
    }

    private boolean hasMachineSshByIp(String ip) {
        if (StringUtils.isBlank(ip)) {
            return false;
        }
        try {
            MachineInfo machine = machineDao.getMachineFullInfoByIp(ip.trim());
            return machine != null
                    && machine.getAvailable() == 1
                    && StringUtils.isNotBlank(machine.getSshUser())
                    && StringUtils.isNotBlank(machine.getSshPasswd());
        } catch (Exception e) {
            logger.warn("resolve machine ssh failed ip={}: {}", ip, e.getMessage());
            return false;
        }
    }

    private String describeSshLinkedHosts(List<InstanceInfo> instances) {
        List<String> parts = new ArrayList<String>();
        for (String ip : uniqueHostIps(filterDataNodes(instances))) {
            parts.add(ip + " → machine_info");
        }
        return StringUtils.join(parts, "；");
    }

    private void fillLevelCounts(FaultDiagnosticReportVO report) {
        int pass = 0, warn = 0, fail = 0, skip = 0;
        for (FaultDiagnosticCheckVO c : report.getChecks()) {
            if (c == null || c.getLevel() == null) {
                continue;
            }
            String level = c.getLevel().toUpperCase(Locale.ROOT);
            if (FaultDiagnosticLevelEnum.PASS.getCode().equals(level)) {
                pass++;
            } else if (FaultDiagnosticLevelEnum.WARN.getCode().equals(level)) {
                warn++;
            } else if (FaultDiagnosticLevelEnum.FAIL.getCode().equals(level)) {
                fail++;
            } else if (FaultDiagnosticLevelEnum.SKIP.getCode().equals(level)) {
                skip++;
            }
        }
        report.setPassCount(pass);
        report.setWarnCount(warn);
        report.setFailCount(fail);
        report.setSkipCount(skip);
        report.setTotalCount(report.getChecks().size());
    }

    private void saveReport(FaultDiagnosticReportVO report) {
        if (report == null || StringUtils.isBlank(report.getReportId())) {
            return;
        }
        String key = REPORT_KEY_PREFIX + report.getReportId();
        String json = JSON.toJSONString(report);
        boolean ok = assistRedisService.setWithNoSerialize(key, json, REPORT_TTL_SEC);
        if (!ok) {
            logger.warn("fault diag report save failed reportId={} appId={}", report.getReportId(), report.getAppId());
        }
    }

    private void appendHistory(long appId, String reportId) {
        String key = HISTORY_KEY_PREFIX + appId;
        List<String> ids = loadHistoryIds(key);
        ids.remove(reportId);
        ids.add(0, reportId);
        while (ids.size() > HISTORY_MAX) {
            ids.remove(ids.size() - 1);
        }
        boolean ok = assistRedisService.setWithNoSerialize(key, JSON.toJSONString(ids), REPORT_TTL_SEC);
        if (!ok) {
            logger.warn("fault diag history save failed appId={}", appId);
        }
    }

    private List<String> loadHistoryIds(String key) {
        try {
            String json = assistRedisService.getWithNoSerialize(key);
            if (StringUtils.isNotBlank(json)) {
                List<String> ids = JSON.parseArray(json, String.class);
                if (ids != null) {
                    return new ArrayList<String>(ids);
                }
            }
        } catch (Exception e) {
            logger.warn("parse fault diag history json failed key={}: {}", key, e.getMessage());
        }
        try {
            List<String> legacy = assistRedisService.get(key);
            if (legacy != null && !legacy.isEmpty()) {
                return new ArrayList<String>(legacy);
            }
        } catch (Exception e) {
            logger.warn("load fault diag history legacy failed key={}: {}", key, e.getMessage());
        }
        return new ArrayList<String>();
    }

    private String resolveScenarioLabel(String scenario) {
        if (SCENARIO_REPLICATION_BREAK.equals(scenario)) {
            return "主从连接中断";
        }
        return scenario;
    }
}
