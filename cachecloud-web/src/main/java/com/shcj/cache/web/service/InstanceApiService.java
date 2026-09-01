package com.shcj.cache.web.service;

import com.shcj.cache.alert.InstanceAlertService;
import com.shcj.cache.constant.RedisExcludeCommand;
import com.shcj.cache.dao.InstanceDao;
import com.shcj.cache.entity.*;
import com.shcj.cache.exception.BizException;
import com.shcj.cache.redis.RedisCenter;
import com.shcj.cache.stats.app.AppStatsCenter;
import com.shcj.cache.stats.instance.InstanceDeployCenter;
import com.shcj.cache.stats.instance.InstanceStatsCenter;
import com.shcj.cache.stats.instance.impl.InstanceStatsCenterImpl;
import com.shcj.cache.util.RedisCommandKindUtil;
import com.shcj.cache.util.ConstUtils;
import com.shcj.cache.util.RedisOsArchUtil;
import com.shcj.cache.web.controller.api.dto.*;
import com.shcj.cache.web.util.DateUtil;
import com.shcj.cache.web.vo.AppDetailVO;
import com.shcj.cache.web.vo.RedisSlowLog;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.math.NumberUtils;
import org.apache.commons.lang.time.DateUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.exceptions.JedisDataException;

import javax.annotation.Resource;
import java.text.DecimalFormat;
import java.text.ParseException;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class InstanceApiService {

    /** CONFIG REWRITE 连接类异常的重试次数 */
    private static final int REWRITE_MAX_ATTEMPTS = 3;

    @Resource(name = "instanceStatsCenter")
    private InstanceStatsCenter instanceStatsCenter;

    @Resource(name = "instanceDeployCenter")
    private InstanceDeployCenter instanceDeployCenter;

    @Resource(name = "appStatsCenter")
    private AppStatsCenter appStatsCenter;

    @Resource(name = "appService")
    private AppService appService;

    @Resource(name = "redisCenter")
    private RedisCenter redisCenter;

    @Resource
    private InstanceAlertService instanceAlertService;

    @Autowired
    private InstanceDao instanceDao;

    @Autowired
    private WebClientComponent webClientComponent;

    public InstanceDetailDto getDetail(long instanceId, Long appId, boolean fromNode) {
        InstanceInfo info = instanceStatsCenter.getInstanceInfo(instanceId);
        if (info == null) {
            return null;
        }
        long resolvedAppId = appId != null && appId >= 0 ? appId : info.getAppId();
        AppDesc appDesc = appService.getByAppId(resolvedAppId);
        boolean nodeManageContext = fromNode || info.getHostId() == ExternalRedis.EXTERNAL_HOST_ID;

        InstanceDetailDto dto = new InstanceDetailDto();
        dto.setInstanceId(instanceId);
        dto.setAppId(resolvedAppId);
        dto.setAppName(appDesc != null ? appDesc.getName() : "");
        dto.setHostPort(info.getHostPort());
        dto.setType(info.getType());
        dto.setTypeDesc(info.getTypeDesc());
        dto.setStatus(info.getStatus());
        dto.setStatusDesc(info.getStatusDesc());
        dto.setNodeManageContext(nodeManageContext);
        dto.setTabs(buildTabs(info.getType()));
        dto.setDefaultTab("instance_stat");
        return dto;
    }

    public InstanceStatDto getStat(long instanceId, String startDate, String endDate) {
        InstanceInfo info = instanceStatsCenter.getInstanceInfo(instanceId);
        if (info == null) {
            return null;
        }
        String[] range = resolveDateRange(startDate, endDate);
        startDate = range[0];
        endDate = range[1];

        InstanceStats stats = instanceStatsCenter.getInstanceStats(instanceId);
        AppDetailVO appDetail = appStatsCenter.getAppDetail(info.getAppId());
        String appName = appDetail != null && appDetail.getAppDesc() != null
                ? appDetail.getAppDesc().getName() : "";

        InstanceStatDto dto = new InstanceStatDto();
        dto.setInstanceId(instanceId);
        dto.setAppId(info.getAppId());
        dto.setAppName(appName);
        dto.setHostPort(info.getHostPort());
        dto.setInstanceType(info.getType());
        dto.setTypeDesc(info.getTypeDesc());
        dto.setStatusDesc(info.getStatusDesc());
        dto.setStartDate(startDate);
        dto.setEndDate(endDate);
        dto.setRealtimeSupported(info.getType() == 2 || info.getType() == 5 || info.getType() == 6);

        if (stats != null) {
            dto.setMemUsePercent(stats.getMemUsePercent());
            dto.setCurrItems(stats.getCurrItems());
            dto.setCurrConnections(stats.getCurrConnections());
            dto.setMemFragmentationRatio(stats.getMemFragmentationRatio());
            if (stats.getRole() == 1) {
                dto.setRoleDesc("master");
            } else if (stats.getRole() == 2) {
                dto.setRoleDesc("slave");
            }
            long hits = stats.getHits();
            long misses = stats.getMisses();
            if (hits + misses > 0) {
                DecimalFormat df = new DecimalFormat("0.00%");
                dto.setHitPercent(df.format((double) hits / (hits + misses)));
            } else {
                dto.setHitPercent("0%");
            }
            fillInfoSections(dto, stats);
            dto.setUptimeSeconds(resolveUptimeSeconds(info.getType(), stats));
            dto.setOsInfo(RedisOsArchUtil.readOsLine(stats.getInfoMap()));
            dto.setOsArch(RedisOsArchUtil.resolveArch(stats.getInfoMap()));
            // 与节点列表一致：优先用采集 used/max，不再依赖纳管时 instance_info.mem
            long maxBytes = stats.getEffectiveMaxMemory();
            if (maxBytes > 0) {
                dto.setMemTotalGb(maxBytes / 1024.0 / 1024.0 / 1024.0);
                dto.setMemUsedGb(stats.getUsedMemory() / 1024.0 / 1024.0 / 1024.0);
            }
        }
        if (dto.getMemTotalGb() <= 0 && info.getMem() > 0) {
            long memMb = info.getMem();
            dto.setMemTotalGb(memMb / 1024.0);
            dto.setMemUsedGb(memMb * dto.getMemUsePercent() / 100.0 / 1024.0);
        }

        long[] minuteRange = resolveMinuteRange(startDate, endDate);
        long begin = minuteRange[0];
        long end = minuteRange[1];
        fillInstanceCommandSummary(dto, info, begin, end);
        return dto;
    }

    private void fillInstanceCommandSummary(InstanceStatDto dto, InstanceInfo info, long begin, long end) {
        List<Map<String, Object>> rows = instanceStatsCenter.queryDiffMapList(
                begin, end, info.getIp(), info.getPort(), "redis");
        Map<String, Long> totals = new HashMap<String, Long>();
        Map<String, Long> peaks = new HashMap<String, Long>();
        Map<String, Date> peakTimes = new HashMap<String, Date>();
        long totalHits = 0L;
        long totalMisses = 0L;
        for (Map<String, Object> row : rows) {
            totalHits += MapUtils.getLongValue(row, "keyspace_hits", 0L);
            totalMisses += MapUtils.getLongValue(row, "keyspace_misses", 0L);
            Date collectTime = parseCollectTime(row.get(ConstUtils.COLLECT_TIME));
            for (Map.Entry<String, Object> entry : row.entrySet()) {
                String key = entry.getKey();
                if (StringUtils.isBlank(key) || !key.startsWith("cmdstat_")) continue;
                String command = key.substring("cmdstat_".length()).toLowerCase();
                long count = MapUtils.getLongValue(row, key, 0L);
                if (count <= 0 || RedisExcludeCommand.isExcludeCommand(command)) continue;
                totals.put(command, totals.getOrDefault(command, 0L) + count);
                if (!peaks.containsKey(command) || count > peaks.get(command)) {
                    peaks.put(command, count);
                    peakTimes.put(command, collectTime);
                }
            }
        }
        long lookupCount = totalHits + totalMisses;
        if (lookupCount > 0) {
            dto.setHitPercent(new DecimalFormat("0.00%").format((double) totalHits / lookupCount));
        } else {
            dto.setHitPercent("0%");
        }
        List<String> topCommands = totals.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .limit(5)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
        for (String command : topCommands) {
            ChartPointDto distribution = new ChartPointDto();
            distribution.setCommandName(command);
            distribution.setY(totals.get(command));
            dto.getTopCommands().add(distribution);

            AppCommandClimaxDto climax = new AppCommandClimaxDto();
            climax.setCommandName(command);
            climax.setCommandCount(peaks.getOrDefault(command, 0L));
            Date peakTime = peakTimes.get(command);
            climax.setCreateTime(peakTime == null ? "" : DateUtil.formatDate(peakTime, "yyyy-MM-dd HH:mm:ss"));
            dto.getTop5Climax().add(climax);
        }
    }

    private void fillInfoSections(InstanceStatDto dto, InstanceStats stats) {
        if (stats.getInfoMap() == null) {
            return;
        }
        for (Map.Entry<String, Object> entry : stats.getInfoMap().entrySet()) {
            Object value = entry.getValue();
            if (!(value instanceof Map)) {
                continue;
            }
            Map<?, ?> raw = (Map<?, ?>) value;
            Map<String, String> fields = new LinkedHashMap<>();
            for (Map.Entry<?, ?> field : raw.entrySet()) {
                if (field.getKey() != null && field.getValue() != null) {
                    fields.put(String.valueOf(field.getKey()), String.valueOf(field.getValue()));
                }
            }
            if (!fields.isEmpty()) {
                dto.getInfoSections().put(entry.getKey(), fields);
            }
        }
    }

    /**
     * 运行时长（秒）。页面统一按「天/小时/分钟」展示，因此这里给秒而不是天，
     * 与节点列表的 uptimeSeconds 保持同一口径。
     */
    private long resolveUptimeSeconds(int type, InstanceStats stats) {
        if (stats.getUptimeInSeconds() > 0) {
            return stats.getUptimeInSeconds();
        }
        if (stats.getInfoMap() == null) {
            return 0L;
        }
        if (type == 1) {
            Object statsGroup = stats.getInfoMap().get("stats");
            if (statsGroup instanceof Map) {
                Object uptime = ((Map<?, ?>) statsGroup).get("uptime");
                if (uptime instanceof Number) {
                    return ((Number) uptime).longValue();
                }
            }
            return 0L;
        }
        Object serverGroup = stats.getInfoMap().get("Server");
        if (!(serverGroup instanceof Map)) {
            serverGroup = stats.getInfoMap().get("server");
        }
        if (serverGroup instanceof Map) {
            Object seconds = ((Map<?, ?>) serverGroup).get("uptime_in_seconds");
            if (seconds != null) {
                return NumberUtils.toLong(String.valueOf(seconds), 0L);
            }
        }
        return 0L;
    }

    public InstanceLogDto getLog(long instanceId, int pageSize) {
        int size = pageSize > 0 ? Math.min(pageSize, 500) : 100;
        String logStr = instanceDeployCenter.showInstanceRecentLog((int) instanceId, size);
        InstanceLogDto dto = new InstanceLogDto();
        if (StringUtils.isNotBlank(logStr)) {
            dto.setLines(Arrays.asList(logStr.split("\n")));
        }
        return dto;
    }

    public InstanceSlowLogPageDto getSlowLogs(long instanceId, int pageNo, int pageSize) {
        InstanceSlowLogPageDto page = new InstanceSlowLogPageDto();
        int safePageNo = pageNo <= 0 ? 1 : pageNo;
        int safePageSize = pageSize <= 0 ? 20 : Math.min(pageSize, 100);
        page.setPageNo(safePageNo);
        page.setPageSize(safePageSize);

        List<RedisSlowLog> logs = redisCenter.getRedisSlowLogs((int) instanceId, -1);
        if (logs == null || logs.isEmpty()) {
            return page;
        }

        List<InstanceSlowLogItemDto> allItems = new ArrayList<>();
        for (RedisSlowLog log : logs) {
            InstanceSlowLogItemDto dto = new InstanceSlowLogItemDto();
            dto.setId(log.getId());
            dto.setTimeStamp(log.getTimeStamp());
            dto.setExecutionTime(log.getExecutionTime());
            dto.setCommand(log.getCommand());
            dto.setClientIp(log.getClientIp());
            dto.setClientName(log.getClientName());
            allItems.add(dto);
        }

        int totalCount = allItems.size();
        page.setTotalCount(totalCount);
        page.setTotalPages(totalCount == 0 ? 0 : (totalCount + safePageSize - 1) / safePageSize);

        int fromIndex = (safePageNo - 1) * safePageSize;
        if (fromIndex < totalCount) {
            page.getItems().addAll(allItems.subList(fromIndex, Math.min(fromIndex + safePageSize, totalCount)));
        }
        return page;
    }

    public Map<String, String> getConfig(long instanceId) {
        Map<String, String> config = redisCenter.getRedisConfigList((int) instanceId);
        return config != null ? config : Collections.emptyMap();
    }

    public InstanceConfigUpdateResultDto updateConfig(long instanceId, InstanceConfigUpdateRequestDto request) {
        InstanceInfo instance = instanceDao.getInstanceInfoById(instanceId);
        if (instance == null) {
            throw new BizException("节点不存在");
        }
        if (instance.getType() != ConstUtils.CACHE_TYPE_REDIS_CLUSTER
                && instance.getType() != ConstUtils.CACHE_REDIS_STANDALONE) {
            throw new BizException("当前节点类型不支持 CONFIG SET");
        }
        String configName = request == null ? "" : StringUtils.trimToEmpty(request.getConfigName());
        String configValue = request == null ? "" : StringUtils.defaultString(request.getConfigValue());
        if (StringUtils.isBlank(configName)) {
            throw new BizException("配置项不能为空");
        }

        Jedis jedis = null;
        try {
            jedis = redisCenter.getJedis(instance.getAppId(), instance.getIp(), instance.getPort(), 5000, 5000);
            List<String> before = jedis.configGet(configName);
            if (before == null || before.size() < 2) {
                throw new BizException("当前 Redis 版本不支持配置项：" + configName);
            }
            String response = jedis.configSet(configName, configValue);
            if (!"OK".equalsIgnoreCase(response)) {
                throw new BizException("CONFIG SET 返回异常：" + StringUtils.defaultString(response, "无返回值"));
            }
            List<String> after = jedis.configGet(configName);
            if (after == null || after.size() < 2) {
                throw new BizException("配置已提交，但 CONFIG GET 回读失败：" + configName);
            }

            String rewriteError = rewriteConfigFile(jedis);
            boolean rewriteSuccess = rewriteError == null;
            InstanceConfigUpdateResultDto result = new InstanceConfigUpdateResultDto();
            result.setInstanceId(instanceId);
            result.setConfigName(configName);
            result.setPreviousValue(before.get(1));
            result.setCurrentValue(after.get(1));
            result.setSource("Redis CONFIG GET");
            result.setRewriteSuccess(rewriteSuccess);
            result.setRewriteError(rewriteError);
            result.setMessage(rewriteSuccess
                    ? "运行时配置已更新并写入配置文件"
                    : "运行时配置已更新，但写回配置文件失败：" + rewriteError + "；实例重启后将恢复原值");
            return result;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            String message = StringUtils.defaultIfEmpty(e.getMessage(), e.getClass().getSimpleName());
            throw new BizException("配置修改失败：" + message);
        } finally {
            if (jedis != null) {
                jedis.close();
            }
        }
    }

    /**
     * 执行 CONFIG REWRITE，把运行时配置回写到 Redis 自己的配置文件。
     *
     * 返回 null 表示成功；否则返回 Redis 给出的失败原因。
     * 之所以要把原因带出来，是因为最常见的失败并不是「偶发故障」，而是节点本身
     * 就不是用配置文件启动的（如 docker 里 `redis-server --port 6379 ...`），
     * 此时 Redis 固定返回 "ERR The server is running without a config file"，
     * 只提示「持久化失败」会让人误以为是平台问题。
     *
     * @return null 表示成功，否则为失败原因
     */
    private String rewriteConfigFile(Jedis jedis) {
        String lastError = null;
        for (int attempt = 0; attempt < REWRITE_MAX_ATTEMPTS; attempt++) {
            try {
                String response = jedis.configRewrite();
                if ("OK".equalsIgnoreCase(response)) {
                    return null;
                }
                lastError = StringUtils.defaultIfEmpty(response, "无返回值");
            } catch (JedisDataException e) {
                // Redis 明确返回了错误（未使用配置文件启动、配置文件不可写等），
                // 属于确定性失败，重试只是徒劳，直接把原因抛给调用方。
                return StringUtils.defaultIfEmpty(e.getMessage(), e.getClass().getSimpleName());
            } catch (Exception e) {
                // 连接类异常才有重试价值
                lastError = StringUtils.defaultIfEmpty(e.getMessage(), e.getClass().getSimpleName());
            }
        }
        return lastError;
    }

    public InstanceClientsPageDto getClients(long instanceId, int condition, boolean includeRaw,
            boolean includeDetails, String addr, int detailLimit) {
        InstanceClientsPageDto page = new InstanceClientsPageDto();
        int conditionVal = condition == 0 ? 3 : condition;
        int limit = detailLimit <= 0 ? 200 : Math.min(detailLimit, 500);
        InstanceInfo info = instanceDao.getInstanceInfoById(instanceId);
        if (info == null) {
            return page;
        }
        long appId = info.getAppId();
        final List<String> redisClientList;
        final List<String> ccWebClientList;
        if (conditionVal != 3) {
            redisClientList = appService.getAppOnlineInstanceInfo(appId).stream()
                    .map(InstanceInfo::getIp).collect(Collectors.toList());
            ccWebClientList = webClientComponent.getWebClientIps();
        } else {
            redisClientList = Collections.emptyList();
            ccWebClientList = Collections.emptyList();
        }
        List<String> clientList = redisCenter.getClientList((int) instanceId);
        page.setTotalConnections(clientList != null ? clientList.size() : 0);
        if (includeRaw && clientList != null) {
            page.getRawConnections().addAll(clientList);
        }
        List<String> formatSource = clientList != null ? clientList : Collections.emptyList();
        if (StringUtils.isNotBlank(addr) && !formatSource.isEmpty()) {
            String addrNeedle = "addr=" + addr + ":";
            formatSource = formatSource.stream()
                    .filter(line -> line != null && line.contains(addrNeedle))
                    .collect(Collectors.toList());
        }
        boolean needConnectionDetails = includeDetails || StringUtils.isNotBlank(addr);
        List<Map<String, Object>> clientMapList = redisCenter.formatClientList(formatSource, needConnectionDetails);

        List<Map<String, Object>> filtered;
        switch (conditionVal) {
            case 0:
                filtered = clientMapList.stream()
                        .filter(m -> !ccWebClientList.contains(MapUtils.getString(m, "addr"))
                                && !redisClientList.contains(MapUtils.getString(m, "addr")))
                        .collect(Collectors.toList());
                break;
            case 1:
                filtered = clientMapList.stream()
                        .filter(m -> ccWebClientList.contains(MapUtils.getString(m, "addr")))
                        .collect(Collectors.toList());
                break;
            case 2:
                filtered = clientMapList.stream()
                        .filter(m -> redisClientList.contains(MapUtils.getString(m, "addr")))
                        .collect(Collectors.toList());
                break;
            default:
                filtered = clientMapList;
        }

        if (StringUtils.isNotBlank(addr)) {
            filtered = filtered.stream()
                    .filter(m -> addr.equals(MapUtils.getString(m, "addr")))
                    .collect(Collectors.toList());
        }

        for (Map<String, Object> map : filtered) {
            InstanceClientItemDto dto = new InstanceClientItemDto();
            dto.setAddr(MapUtils.getString(map, "addr"));
            dto.setCount(MapUtils.getIntValue(map, "count", 0));
            Object typeSet = map.get("clientTypeSet");
            if (typeSet instanceof Collection) {
                for (Object t : (Collection<?>) typeSet) {
                    if (t != null) {
                        dto.getClientTypes().add(String.valueOf(t));
                    }
                }
            }
            if (includeDetails) {
                appendClientConnections(dto, map.get("clientInfoList"), limit);
            }
            page.getItems().add(dto);
        }
        return page;
    }

    private void appendClientConnections(InstanceClientItemDto dto, Object connList, int detailLimit) {
        if (!(connList instanceof Collection)) {
            return;
        }
        Collection<?> connections = (Collection<?>) connList;
        dto.setDetailTotal(connections.size());
        int added = 0;
        for (Object c : connections) {
            if (added >= detailLimit) {
                break;
            }
            if (!(c instanceof Map)) {
                continue;
            }
            Map<?, ?> clientInfo = (Map<?, ?>) c;
            String detail = String.valueOf(clientInfo);
            dto.getConnections().add(detail);
            InstanceClientConnectionDto connection = new InstanceClientConnectionDto();
            connection.setFlags(MapUtils.getString(clientInfo, "flags", ""));
            connection.setCmd(MapUtils.getString(clientInfo, "cmd", ""));
            connection.setDetail(detail);
            dto.getConnectionDetails().add(connection);
            added++;
        }
        dto.setDetailsTruncated(connections.size() > detailLimit);
    }

    private void appendClientConnections(InstanceClientItemDto dto, Object connList) {
        appendClientConnections(dto, connList, 500);
    }

    public InstanceFaultPageDto getFaults(long instanceId, int pageNo, int pageSize) {
        InstanceFaultPageDto page = new InstanceFaultPageDto();
        int safePageNo = pageNo <= 0 ? 1 : pageNo;
        int safePageSize = pageSize <= 0 ? 20 : Math.min(pageSize, 100);
        page.setPageNo(safePageNo);
        page.setPageSize(safePageSize);

        List<InstanceFault> list = instanceAlertService.getListByInstId((int) instanceId);
        if (list == null || list.isEmpty()) {
            return page;
        }

        List<InstanceFaultItemDto> allItems = new ArrayList<>();
        for (InstanceFault fault : list) {
            InstanceFaultItemDto dto = new InstanceFaultItemDto();
            dto.setId(fault.getId());
            dto.setAppId(fault.getAppId());
            dto.setInstId(fault.getInstId());
            dto.setIp(fault.getIp());
            dto.setPort(fault.getPort());
            dto.setStatus(fault.getStatus());
            dto.setStatusDesc(fault.getStatusDesc());
            dto.setType(fault.getType());
            dto.setTypeDesc(fault.getTypeDesc());
            dto.setReason(fault.getReason());
            if (fault.getCreateTime() != null) {
                dto.setCreateTime(DateUtil.formatDate(fault.getCreateTime(), "yyyy-MM-dd HH:mm:ss"));
            }
            allItems.add(dto);
        }

        int totalCount = allItems.size();
        page.setTotalCount(totalCount);
        page.setTotalPages(totalCount == 0 ? 0 : (totalCount + safePageSize - 1) / safePageSize);

        int fromIndex = (safePageNo - 1) * safePageSize;
        if (fromIndex < totalCount) {
            page.getItems().addAll(allItems.subList(fromIndex, Math.min(fromIndex + safePageSize, totalCount)));
        }
        return page;
    }

    public InstanceCommandAnalysisDto getCommandAnalysis(long instanceId, String startDate, String endDate) {
        InstanceInfo info = instanceStatsCenter.getInstanceInfo(instanceId);
        if (info == null) {
            return null;
        }
        String[] range = resolveDateRange(startDate, endDate);
        long begin = NumberUtils.toLong(range[0] + "0000");
        long end = NumberUtils.toLong(range[1] + "2359");
        List<String> topCommands = getTopInstanceCommandNames(info, instanceId, begin, end, 5);

        InstanceCommandAnalysisDto dto = new InstanceCommandAnalysisDto();
        dto.setInstanceId(instanceId);
        dto.setStartDate(range[0]);
        dto.setEndDate(range[1]);
        if (topCommands != null) {
            for (String commandName : topCommands) {
                if (StringUtils.isNotBlank(commandName)) {
                    dto.getCommands().add(commandName);
                }
            }
        }
        return dto;
    }

    public Map<String, List<ChartPointDto>> getStatCharts(long instanceId, String statNames,
                                                           String startDate, String endDate) {
        InstanceInfo info = instanceStatsCenter.getInstanceInfo(instanceId);
        Map<String, List<ChartPointDto>> result = emptyChartResult(statNames);
        if (info == null || result.isEmpty()) return result;
        long[] range = resolveMinuteRange(startDate, endDate);
        List<Map<String, Object>> rows = instanceStatsCenter.queryDiffMapList(
                range[0], range[1], info.getIp(), info.getPort(), "redis");
        for (Map<String, Object> row : rows) {
            Date collectTime = parseCollectTime(row.get(ConstUtils.COLLECT_TIME));
            if (collectTime == null) collectTime = parseCollectTime(row.get("collect_time"));
            if (collectTime == null) continue;
            for (String statName : result.keySet()) {
                Double value = instanceMetricValue(row, statName);
                if (value == null) continue;
                ChartPointDto point = chartPoint(collectTime, value);
                if (point != null) result.get(statName).add(point);
            }
        }
        for (String statName : new ArrayList<String>(result.keySet())) {
            if (isCpuRateStat(statName)) {
                result.put(statName, toCpuRatePoints(result.get(statName)));
            }
        }
        return result;
    }

    private boolean isCpuRateStat(String statName) {
        return "cpuSys".equals(statName) || "cpuUser".equals(statName)
                || "cpuUserChildren".equals(statName);
    }

    private List<ChartPointDto> toCpuRatePoints(List<ChartPointDto> points) {
        List<ChartPointDto> rates = new ArrayList<ChartPointDto>();
        if (points == null || points.size() < 2) return rates;
        points.sort(Comparator.comparingLong(ChartPointDto::getX));
        for (int i = 1; i < points.size(); i++) {
            ChartPointDto previous = points.get(i - 1);
            ChartPointDto current = points.get(i);
            long intervalMillis = current.getX() - previous.getX();
            if (intervalMillis <= 0) continue;
            double cpuSeconds = current.getYDouble() != null ? current.getYDouble() : current.getY();
            double rate = Math.round(cpuSeconds * 100000D / intervalMillis * 10000D) / 10000D;
            ChartPointDto point = new ChartPointDto();
            point.setX(current.getX());
            point.setYDouble(rate);
            point.setDate(current.getDate());
            rates.add(point);
        }
        return rates;
    }

    public List<String> getCommandNames(long instanceId, String startDate, String endDate) {
        InstanceInfo info = instanceStatsCenter.getInstanceInfo(instanceId);
        if (info == null) return Collections.emptyList();
        long[] range = resolveMinuteRange(startDate, endDate);
        return getInstanceCommandNames(info, range[0], range[1], 100);
    }

    private List<String> getInstanceCommandNames(InstanceInfo info, long begin, long end, int limit) {
        Map<String, Long> commandCounts = new HashMap<>();
        List<Map<String, Object>> rows = instanceStatsCenter.queryDiffMapList(
                begin, end, info.getIp(), info.getPort(), "redis");
        for (Map<String, Object> row : rows) {
            for (Map.Entry<String, Object> entry : row.entrySet()) {
                String key = entry.getKey();
                if (StringUtils.isBlank(key) || !key.startsWith("cmdstat_")) continue;
                String command = key.substring("cmdstat_".length()).toLowerCase();
                long count = MapUtils.getLongValue(row, key, 0L);
                if (count <= 0 || RedisExcludeCommand.isExcludeCommand(command)) continue;
                commandCounts.put(command, commandCounts.getOrDefault(command, 0L) + count);
            }
        }
        return commandCounts.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .limit(limit)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
    }

    public Map<String, List<ChartPointDto>> getCommandCharts(long instanceId, String commands,
                                                              String startDate, String endDate) {
        InstanceInfo info = instanceStatsCenter.getInstanceInfo(instanceId);
        Map<String, List<ChartPointDto>> result = emptyChartResult(commands);
        if (info == null || result.isEmpty()) return result;
        long[] range = resolveMinuteRange(startDate, endDate);
        List<Map<String, Object>> rows = instanceStatsCenter.queryDiffMapList(
                range[0], range[1], info.getIp(), info.getPort(), "redis");
        for (Map<String, Object> row : rows) {
            Date collectTime = parseCollectTime(row.get(ConstUtils.COLLECT_TIME));
            if (collectTime == null) collectTime = parseCollectTime(row.get("collect_time"));
            if (collectTime == null) continue;
            for (String command : result.keySet()) {
                Double value = number(row, "cmdstat_" + command.toLowerCase());
                if (value == null) continue;
                ChartPointDto point = chartPoint(collectTime, value);
                if (point != null) result.get(command).add(point);
            }
        }
        return result;
    }

    private Map<String, List<ChartPointDto>> emptyChartResult(String names) {
        Map<String, List<ChartPointDto>> result = new LinkedHashMap<>();
        if (StringUtils.isBlank(names)) return result;
        for (String name : names.split(",")) {
            String clean = name.trim();
            if (StringUtils.isNotBlank(clean)) result.put(clean, new ArrayList<ChartPointDto>());
        }
        return result;
    }

    private long[] resolveMinuteRange(String startDate, String endDate) {
        Date end = new Date();
        Date start = DateUtils.addHours(end, -6);
        String startDigits = StringUtils.defaultString(startDate).replaceAll("\\D", "");
        String endDigits = StringUtils.defaultString(endDate).replaceAll("\\D", "");
        long begin = startDigits.length() >= 12
                ? NumberUtils.toLong(startDigits.substring(0, 12))
                : NumberUtils.toLong(DateUtil.formatDate(start, "yyyyMMddHHmm"));
        long finish = endDigits.length() >= 12
                ? NumberUtils.toLong(endDigits.substring(0, 12))
                : NumberUtils.toLong(DateUtil.formatDate(end, "yyyyMMddHHmm"));
        try {
            Date requestedStart = DateUtil.parseYYYYMMddHHMM(String.valueOf(begin));
            Date requestedEnd = DateUtil.parseYYYYMMddHHMM(String.valueOf(finish));
            Date now = new Date();
            if (requestedEnd.after(now)) {
                long duration = Math.max(60_000L, requestedEnd.getTime() - requestedStart.getTime());
                requestedEnd = now;
                requestedStart = new Date(now.getTime() - duration);
                begin = NumberUtils.toLong(DateUtil.formatDate(requestedStart, "yyyyMMddHHmm"));
                finish = NumberUtils.toLong(DateUtil.formatDate(requestedEnd, "yyyyMMddHHmm"));
            }
        } catch (ParseException ignored) {
            // Keep the already normalized fallback range for malformed input.
        }
        return new long[]{begin, finish};
    }

    private Date parseCollectTime(Object value) {
        if (value == null) return null;
        if (value instanceof Date) return (Date) value;
        String text = String.valueOf(value).trim();
        try {
            if (text.matches("\\d{12,}")) {
                return DateUtil.parseYYYYMMddHHMM(text.substring(0, 12));
            }
            return DateUtil.parseRangeDateTime(text);
        } catch (Exception e) {
            return null;
        }
    }

    private ChartPointDto chartPoint(Date collectTime, double value) {
        ChartPointDto point = new ChartPointDto();
        point.setX(collectTime.getTime());
        point.setY((long) value);
        point.setYDouble(value);
        point.setDate(DateUtil.formatDate(collectTime, "yyyy-MM-dd HH:mm"));
        return point;
    }

    private Double instanceMetricValue(Map<String, Object> row, String name) {
        if ("commandCount".equals(name)) {
            double total = 0D;
            boolean found = false;
            for (Map.Entry<String, Object> entry : row.entrySet()) {
                if (entry.getKey() != null && entry.getKey().startsWith("cmdstat_")) {
                    try {
                        total += Double.parseDouble(String.valueOf(entry.getValue()));
                        found = true;
                    } catch (NumberFormatException ignored) { }
                }
            }
            return found ? total : null;
        }
        if (InstanceStatsCenterImpl.READ_COMMAND_STAT.equals(name)
                || InstanceStatsCenterImpl.WRITE_COMMAND_STAT.equals(name)) {
            boolean write = InstanceStatsCenterImpl.WRITE_COMMAND_STAT.equals(name);
            double total = 0D;
            boolean found = false;
            for (Map.Entry<String, Object> entry : row.entrySet()) {
                String key = entry.getKey();
                if (key == null || !key.startsWith("cmdstat_")) {
                    continue;
                }
                String command = key.substring("cmdstat_".length());
                if (write ? !RedisCommandKindUtil.isWrite(command) : !RedisCommandKindUtil.isRead(command)) {
                    continue;
                }
                try {
                    total += Double.parseDouble(String.valueOf(entry.getValue()));
                    found = true;
                } catch (NumberFormatException ignored) { }
            }
            // 一条都没命中时返回 null，让上层跳过这个点，而不是画出恒为 0 的假曲线
            return found ? total : null;
        }
        if ("hitPercent".equals(name)) {
            Double hits = number(row, "keyspace_hits");
            Double misses = number(row, "keyspace_misses");
            if (hits == null && misses == null) return null;
            double total = (hits == null ? 0 : hits) + (misses == null ? 0 : misses);
            return total <= 0 ? 0D : (hits == null ? 0 : hits) * 100D / total;
        }
        String key;
        switch (name) {
            case "hits": key = "keyspace_hits"; break;
            case "misses": key = "keyspace_misses"; break;
            case "netInput": key = "total_net_input_bytes"; break;
            case "netOutput": key = "total_net_output_bytes"; break;
            case "cpuSys": key = "used_cpu_sys"; break;
            case "cpuUser": key = "used_cpu_user"; break;
            case "cpuSysChildren": key = "used_cpu_sys_children"; break;
            case "cpuUserChildren": key = "used_cpu_user_children"; break;
            case "memFragRatio": key = "mem_fragmentation_ratio"; break;
            case "usedMemory": key = "used_memory"; break;
            case "usedMemoryRss": key = "used_memory_rss"; break;
            case "connectedClient": key = "connected_clients"; break;
            case "objectSize": key = "object_size"; break;
            case "expiredKeys": key = "expired_keys"; break;
            case "evictedKeys": key = "evicted_keys"; break;
            default: key = name; break;
        }
        Double value = number(row, key);
        if (value == null && "objectSize".equals(name)) value = number(row, "db_size", "dbsize");
        if (value == null) return null;
        if ("usedMemory".equals(name) || "usedMemoryRss".equals(name)
                || "aof_current_size".equals(name) || "aof_base_size".equals(name)
                || "master_repl_offset".equals(name)) return value / 1024D / 1024D;
        if ("latest_fork_usec".equals(name)) return value / 1000D;
        return value;
    }

    private Double number(Map<String, Object> row, String... keys) {
        for (String key : keys) {
            Object value = row.get(key);
            if (value == null) continue;
            try { return Double.parseDouble(String.valueOf(value)); }
            catch (NumberFormatException ignored) { }
        }
        return null;
    }

    private List<String> getTopInstanceCommandNames(InstanceInfo info, long instanceId, long begin, long end, int limit) {
        Map<String, Long> commandCounts = new HashMap<>();
        List<Map<String, Object>> diffList = instanceStatsCenter
                .queryDiffMapList(begin, end, info.getIp(), info.getPort(), "redis");
        if (diffList != null) {
            for (Map<String, Object> diff : diffList) {
                if (diff == null || diff.isEmpty()) {
                    continue;
                }
                for (Map.Entry<String, Object> entry : diff.entrySet()) {
                    String key = entry.getKey();
                    if (StringUtils.isBlank(key) || !key.startsWith("cmdstat_")) {
                        continue;
                    }
                    String commandName = key.substring("cmdstat_".length()).toLowerCase();
                    long count = MapUtils.getLongValue(diff, key, 0L);
                    if (count <= 0 || RedisExcludeCommand.isExcludeCommand(commandName)) {
                        continue;
                    }
                    commandCounts.put(commandName, commandCounts.getOrDefault(commandName, 0L) + count);
                }
            }
        }
        if (!commandCounts.isEmpty()) {
            return commandCounts.entrySet().stream()
                    .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                    .limit(limit)
                    .map(Map.Entry::getKey)
                    .collect(Collectors.toList());
        }

        List<AppCommandStats> appTopCommands = appStatsCenter.getTopLimitAppCommandStatsList(
                info.getAppId(), begin, end, limit);
        if (appTopCommands == null) {
            return Collections.emptyList();
        }
        return appTopCommands.stream()
                .map(AppCommandStats::getCommandName)
                .filter(StringUtils::isNotBlank)
                .limit(limit)
                .collect(Collectors.toList());
    }

    public InstanceCommandChartDto getCommandChart(long instanceId, String commandName,
                                                   String startDate, String endDate) throws ParseException {
        InstanceInfo info = instanceStatsCenter.getInstanceInfo(instanceId);
        if (info == null || StringUtils.isBlank(commandName)) {
            return null;
        }
        String[] range = resolveDateRange(startDate, endDate);
        startDate = range[0];
        endDate = range[1];

        Date start = DateUtil.parseYYYYMMdd(startDate);
        Date endDay = DateUtil.parseYYYYMMdd(endDate);
        long firstDayBegin = NumberUtils.toLong(DateUtil.formatYYYYMMdd(start) + "0000");
        long firstDayEnd = NumberUtils.toLong(DateUtil.formatYYYYMMdd(start) + "2359");
        long secondDayBegin = NumberUtils.toLong(DateUtil.formatYYYYMMdd(endDay) + "0000");
        long secondDayEnd = NumberUtils.toLong(DateUtil.formatYYYYMMdd(endDay) + "2359");

        List<InstanceCommandStats> firstList = instanceStatsCenter
                .getCommandStatsList(instanceId, firstDayBegin, firstDayEnd, commandName);
        List<InstanceCommandStats> secondList = instanceStatsCenter
                .getCommandStatsList(instanceId, secondDayBegin, secondDayEnd, commandName);
        Map<String, InstanceCommandStats> cmdStatsFirst = new HashMap<>();
        Map<String, InstanceCommandStats> cmdStatsSecond = new HashMap<>();
        if (firstList != null) {
            for (InstanceCommandStats stat : firstList) {
                cmdStatsFirst.put(stat.getCollectTime() + "", stat);
            }
        }
        if (secondList != null) {
            for (InstanceCommandStats stat : secondList) {
                cmdStatsSecond.put(stat.getCollectTime() + "", stat);
            }
        }

        InstanceCommandChartDto dto = new InstanceCommandChartDto();
        dto.setCommandName(commandName);
        dto.setStartDate(startDate);
        dto.setEndDate(endDate);

        InstanceCommandChartSeriesDto series1 = new InstanceCommandChartSeriesDto();
        series1.setName(startDate);
        InstanceCommandChartSeriesDto series2 = new InstanceCommandChartSeriesDto();
        series2.setName(endDate);

        for (int i = 0; i < 1440; i++) {
            Date date = DateUtils.addMinutes(start, i);
            String hhmm = DateUtil.formatHHMM(date);
            dto.getCategories().add(hhmm);
            String key1 = startDate + hhmm;
            String key2 = endDate + hhmm;
            series1.getData().add(cmdStatsFirst.containsKey(key1)
                    ? cmdStatsFirst.get(key1).getCommandCount() : 0L);
            series2.getData().add(cmdStatsSecond.containsKey(key2)
                    ? cmdStatsSecond.get(key2).getCommandCount() : 0L);
        }
        dto.getSeries().add(series1);
        dto.getSeries().add(series2);
        return dto;
    }

    public InstanceCommandResultDto executeCommand(long instanceId, String command) {
        InstanceCommandResultDto dto = new InstanceCommandResultDto();
        if (StringUtils.isBlank(command)) {
            dto.setResult("命令不能为空");
            return dto;
        }
        String result = instanceStatsCenter.executeCommand(instanceId, command);
        dto.setResult(result != null ? result : "");
        return dto;
    }

    private List<InstanceDetailTabDto> buildTabs(int type) {
        List<InstanceDetailTabDto> tabs = new ArrayList<>();
        // 展示顺序：节点统计信息 → 只读命令执行 → 配置查询 → 命令曲线 → 连接信息 → 健康检查
        // 拓扑结构已下线：与「集群管理 → 节点列表 / 集群拓扑」重复，节点视角看整体拓扑意义不大
        // 慢查询分析已下线：统一由「集群管理 → 延迟监控」作为唯一入口
        // 故障报警已下线：统一到「集群管理 → 故障报警」按集群维度查看
        tabs.add(tab("instance_stat", "节点统计信息"));
        tabs.add(tab("instance_command", "只读命令执行"));
        if (type == 2 || type == 6) {
            tabs.add(tab("instance_configSelect", "配置查询"));
        }
        tabs.add(tab("instance_advancedAnalysis", "命令曲线"));
        if (type == 2 || type == 4 || type == 5 || type == 6) {
            tabs.add(tab("instance_clientList", "连接信息"));
        }
        tabs.add(tab("instance_health", "健康检查"));
        return tabs;
    }

    private InstanceDetailTabDto tab(String key, String label) {
        InstanceDetailTabDto t = new InstanceDetailTabDto();
        t.setKey(key);
        t.setLabel(label);
        return t;
    }

    private String[] resolveDateRange(String startDate, String endDate) {
        if (StringUtils.isBlank(startDate) || StringUtils.isBlank(endDate)) {
            Date end = new Date();
            Date start = DateUtils.addDays(end, -1);
            return new String[]{
                    DateUtil.formatDate(start, "yyyyMMdd"),
                    DateUtil.formatDate(end, "yyyyMMdd")
            };
        }
        return new String[]{startDate, endDate};
    }
}
