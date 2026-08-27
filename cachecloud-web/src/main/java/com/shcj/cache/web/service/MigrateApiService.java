package com.shcj.cache.web.service;

import com.shcj.cache.constant.AppDataMigrateEnum;
import com.shcj.cache.constant.AppDataMigrateResult;
import com.shcj.cache.constant.MachineInfoEnum;
import com.shcj.cache.dao.AppUserDao;
import com.shcj.cache.dao.AppDataMigrateStatusDao;
import com.shcj.cache.dao.ResourceDao;
import com.shcj.cache.entity.*;
import com.shcj.cache.exception.BizException;
import com.shcj.cache.machine.MachineCenter;
import com.shcj.cache.ssh.SSHUtil;
import com.shcj.cache.redis.RedisConfigTemplateService;
import com.shcj.cache.stats.app.AppDataMigrateCenter;
import com.shcj.cache.stats.app.RedisMigrateToolCenter;
import com.shcj.cache.stats.app.RedisShakeCenter;
import com.shcj.cache.stats.app.impl.EmbeddedRedisShakeService;
import com.shcj.cache.task.constant.ResourceEnum;
import com.shcj.cache.util.ConstUtils;
import com.shcj.cache.web.controller.api.dto.*;
import com.shcj.cache.web.enums.SuccessEnum;
import com.shcj.cache.web.util.Page;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.math.NumberUtils;
import org.springframework.beans.factory.annotation.Autowired;
import com.shcj.cache.web.support.AppRouteIdSupport;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.*;
import java.util.stream.Collectors;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

@Service
public class MigrateApiService {
    @Autowired
    private AppRouteIdSupport appRouteIdSupport;

    private long resolveRouteAppId(long idOrClusterNo) {
        return appRouteIdSupport.requireAppId(idOrClusterNo);
    }


    private static final Set<String> MIGRATE_SAMPLE_USEFUL_LINES = new HashSet<>();

    static {
        MIGRATE_SAMPLE_USEFUL_LINES.add("Checked keys");
        MIGRATE_SAMPLE_USEFUL_LINES.add("Inconsistent value keys");
        MIGRATE_SAMPLE_USEFUL_LINES.add("Inconsistent expire keys");
        MIGRATE_SAMPLE_USEFUL_LINES.add("Other check error keys");
        MIGRATE_SAMPLE_USEFUL_LINES.add("Checked OK keys");
    }

    @Autowired
    private AppDataMigrateCenter appDataMigrateCenter;
    @Autowired
    private AppDataMigrateStatusDao appDataMigrateStatusDao;
    @Resource(name = "redisMigrateToolCenter")
    private RedisMigrateToolCenter redisMigrateToolCenter;
    @Autowired
    private RedisShakeCenter redisShakeCenter;
    @Autowired
    private EmbeddedRedisShakeService embeddedRedisShakeService;
    @Autowired
    private AppService appService;
    @Autowired
    private MachineCenter machineCenter;
    @Autowired
    private ResourceDao resourceDao;
    @Autowired
    private AppUserDao appUserDao;
    @Autowired
    private ResourceService resourceService;
    @Autowired
    private AppImportService appImportService;
    @Autowired
    private RedisConfigTemplateService redisConfigTemplateService;

    public MigrateListPageDto list(AppDataMigrateSearch search, int pageNo, int pageSize) {
        int safePageNo = Math.max(pageNo, 1);
        int safePageSize = pageSize > 0 ? Math.min(pageSize, 100) : 15;
        int total = appDataMigrateCenter.getMigrateTaskCount(search);
        Page page = new Page(safePageNo, safePageSize, total);
        search.setPage(page);
        List<AppDataMigrateStatus> list = appDataMigrateCenter.search(search);

        MigrateListPageDto result = new MigrateListPageDto();
        result.setPageNo(safePageNo);
        result.setPageSize(safePageSize);
        result.setTotalCount(total);
        result.setTotalPages(page.getTotalPages());
        if (list == null) {
            return result;
        }
        for (AppDataMigrateStatus s : list) {
            // 状态只在打开进度视图时才推进，列表不回读的话正在迁移的任务会一直显示「准备阶段」；
            // 非内嵌任务与终态任务由 refreshStatus 内部自行跳过
            embeddedRedisShakeService.refreshStatus(s);
            fillStoredVersions(s);
            result.getItems().add(toItem(s));
        }
        return result;
    }

    public MigrateInitDto getInit(Long importId) {
        MigrateInitDto dto = new MigrateInitDto();
        MigrateToolOptionDto embedded = new MigrateToolOptionDto();
        embedded.setId(EmbeddedRedisShakeService.TOOL_ID);
        embedded.setName(EmbeddedRedisShakeService.TOOL_NAME);
        embedded.setIntro("Runs RedisShake 4.6.1 on the CacheCloud application host");
        dto.getTools().add(embedded);
        List<AppDesc> apps = appService.getAllAppDesc();
        if (apps != null) apps.stream()
                .filter(AppDesc::isOnline)
                .filter(app -> app.getType() == ConstUtils.CACHE_TYPE_REDIS_CLUSTER
                        || app.getType() == ConstUtils.CACHE_REDIS_SENTINEL
                        || app.getType() == ConstUtils.CACHE_REDIS_STANDALONE)
                .sorted(Comparator.comparing(AppDesc::getName, Comparator.nullsLast(String::compareToIgnoreCase)))
                .forEach(app -> {
                    MigrateAppOptionDto option = new MigrateAppOptionDto();
                    option.setValue(app.getClusterNo() == null ? app.getAppId() : app.getClusterNo());
                    option.setLabel("[" + option.getValue() + "] " + app.getName());
                    option.setAppType(app.getType());
                    dto.getApps().add(option);
                });
        if (importId != null && importId > 0) {
            AppImport appImport = appImportService.get(importId);
            if (appImport != null) {
                dto.setImportId(importId);
                dto.setTargetAppId(appImport.getAppId());
                dto.setSourceServers(appImport.getInstanceInfo());
                dto.setRedisSourcePass(appImport.getRedisPassword());
                dto.setSourceType(appImport.getSourceType());
                dto.setSourceDataType(0);
                dto.setRedisSourceVersion(appImport.getRedisVersionName());
            }
        }
        return dto;
    }

    public MigrateAppInstancesDto getAppInstances(long appId, int migrateTool) {
        appId = resolveRouteAppId(appId);
        AppDesc appDesc = appService.getByAppId(appId);
        MigrateAppInstancesDto dto = new MigrateAppInstancesDto();
        dto.setInstances("");
        dto.setPassword("");
        dto.setAppType(appDesc == null ? -1 : appDesc.getType());
        dto.setAppName(appDesc == null ? "" : appDesc.getName());
        if (appDesc != null && appDesc.getVersionId() > 0) {
            SystemResource resource = resourceDao.getResourceById(appDesc.getVersionId());
            dto.setRedisVersion(resource == null ? "" : resource.getName());
        }
        return dto;
    }

    public MigrateActionResultDto check(Map<String, Object> body) {
        populateManagedApp(body, "source");
        populateManagedApp(body, "target");
        String migrateMachineIp = str(body, "migrateMachineIp");
        AppDataMigrateEnum sourceEnum = AppDataMigrateEnum.getByIndex(intVal(body, "sourceRedisMigrateIndex", -1));
        AppDataMigrateEnum targetEnum = AppDataMigrateEnum.getByIndex(intVal(body, "targetRedisMigrateIndex", -1));
        String sourceServers = str(body, "sourceServers");
        String targetServers = str(body, "targetServers");
        String redisSourcePass = str(body, "redisSourcePass");
        String redisTargetPass = str(body, "redisTargetPass");
        int versionId = intVal(body, "versionId", EmbeddedRedisShakeService.TOOL_ID);
        if (versionId == EmbeddedRedisShakeService.TOOL_ID) {
            AppDataMigrateResult result = embeddedRedisShakeService.check(sourceEnum, sourceServers,
                    redisSourcePass, targetEnum, targetServers, redisTargetPass,
                    boolVal(body, "clearTarget", false));
            return toActionResult(result.getStatus(), result.getMessage(), null);
        }
        SystemResource resource = resourceService.getResourceById(versionId);
        if (resource == null) {
            throw new BizException("迁移工具版本不存在");
        }
        redisConfigTemplateService.checkAndInstallRedisTool(migrateMachineIp, resource);
        AppDataMigrateResult result;
        if (resource.getName().contains("redis-shake")) {
            result = redisShakeCenter.check(migrateMachineIp, sourceEnum, sourceServers, targetEnum,
                    targetServers, redisSourcePass, redisTargetPass, resource);
        } else if (resource.getName().contains("redis-migrate-tool")) {
            result = redisMigrateToolCenter.check(migrateMachineIp, sourceEnum, sourceServers, targetEnum,
                    targetServers, redisSourcePass, redisTargetPass, resource);
        } else {
            throw new BizException("不支持的迁移工具: " + resource.getName());
        }
        return toActionResult(result.getStatus(), result.getMessage(), null);
    }

    public MigrateActionResultDto start(AppUser user, Map<String, Object> body) {
        populateManagedApp(body, "source");
        populateManagedApp(body, "target");
        String migrateMachineIp = str(body, "migrateMachineIp");
        AppDataMigrateEnum sourceEnum = AppDataMigrateEnum.getByIndex(intVal(body, "sourceRedisMigrateIndex", -1));
        AppDataMigrateEnum targetEnum = AppDataMigrateEnum.getByIndex(intVal(body, "targetRedisMigrateIndex", -1));
        String sourceServers = str(body, "sourceServers");
        String targetServers = str(body, "targetServers");
        long sourceAppId = longVal(body, "sourceAppId");
        long targetAppId = longVal(body, "targetAppId");
        String redisSourcePass = str(body, "redisSourcePass");
        String redisTargetPass = str(body, "redisTargetPass");
        String redisSourceVersion = str(body, "redisSourceVersion");
        String redisTargetVersion = str(body, "redisTargetVersion");
        redisSourceVersion = detectedVersion(sourceServers, redisSourcePass, redisSourceVersion);
        redisTargetVersion = detectedVersion(targetServers, redisTargetPass, redisTargetVersion);
        int sourceRdbParallel = intVal(body, "sourceRdbParallel", 8);
        int parallel = intVal(body, "parallel", 16);
        int versionId = intVal(body, "versionId", EmbeddedRedisShakeService.TOOL_ID);
        long userId = user == null ? 0 : user.getId();

        if (versionId == EmbeddedRedisShakeService.TOOL_ID) {
            AppDataMigrateStatus embedded = embeddedRedisShakeService.start(sourceEnum, targetEnum,
                    sourceServers, targetServers,
                    sourceAppId, targetAppId, redisSourcePass, redisTargetPass, redisSourceVersion,
                    redisTargetVersion, userId, body);
            return toActionResult(1, "ok", embedded.getMigrateId());
        }

        SystemResource resource = resourceService.getResourceById(versionId);
        if (resource == null) {
            throw new BizException("迁移工具版本不存在");
        }
        AppDataMigrateStatus status;
        if (resource.getName().contains("redis-shake")) {
            status = redisShakeCenter.migrate(migrateMachineIp, sourceRdbParallel, parallel,
                    sourceEnum, sourceServers, targetEnum, targetServers,
                    sourceAppId, targetAppId, redisSourcePass, redisTargetPass,
                    redisSourceVersion, redisTargetVersion, userId, resource);
        } else if (resource.getName().contains("redis-migrate-tool")) {
            status = redisMigrateToolCenter.migrate(migrateMachineIp, sourceEnum, sourceServers,
                    targetEnum, targetServers, sourceAppId, targetAppId, redisSourcePass, redisTargetPass,
                    userId, resource);
        } else {
            throw new BizException("不支持的迁移工具: " + resource.getName());
        }
        return toActionResult(1, "ok", status.getMigrateId());
    }

    public MigrateActionResultDto stop(long id, int migrateTool) {
        AppDataMigrateStatus status = appDataMigrateStatusDao.get(id);
        if (status != null && StringUtils.startsWith(status.getMigrateMachineIp(), "embedded@")) {
            AppDataMigrateResult result = embeddedRedisShakeService.stop(id);
            return toActionResult(result.getStatus(), result.getMessage(), null);
        }
        AppDataMigrateResult result = migrateTool == 1
                ? redisMigrateToolCenter.stopMigrate(id)
                : redisShakeCenter.stopMigrate(id);
        return toActionResult(result.getStatus(), result.getMessage(), null);
    }

    public MigrateActionResultDto resync(long id, AppUser user) {
        AppDataMigrateStatus original = appDataMigrateStatusDao.get(id);
        if (!isEmbedded(original)) throw new BizException("仅支持重新同步内嵌 RedisShake 任务");
        AppDataMigrateStatus status = embeddedRedisShakeService.resync(id, user == null ? 0 : user.getId());
        return toActionResult(1, "重新同步任务已启动", status.getMigrateId());
    }

    public void delete(long id) {
        AppDataMigrateStatus status = appDataMigrateStatusDao.get(id);
        if (!isEmbedded(status)) throw new BizException("仅支持删除内嵌 RedisShake 任务");
        embeddedRedisShakeService.delete(id);
    }

    public Map<String, Object> compareKeyCounts(long id) {
        AppDataMigrateStatus status = appDataMigrateStatusDao.get(id);
        if (!isEmbedded(status)) throw new BizException("仅支持校验内嵌 RedisShake 任务");
        return embeddedRedisShakeService.compareKeyCounts(id);
    }

    public MigrateTextDto getLog(long id, int pageSize) {
        int size = pageSize > 0 ? pageSize : 100;
        MigrateTextDto dto = new MigrateTextDto();
        AppDataMigrateStatus status = appDataMigrateStatusDao.get(id);
        if (isEmbedded(status)) {
            dto.getLines().addAll(readEmbeddedLog(status, size));
            return dto;
        }
        String log = appDataMigrateCenter.showDataMigrateLog(id, size);
        if (StringUtils.isNotBlank(log)) {
            dto.getLines().addAll(Arrays.asList(log.split(ConstUtils.NEXT_LINE)));
        }
        return dto;
    }

    public MigrateTextDto getConfig(long id) {
        MigrateTextDto dto = new MigrateTextDto();
        AppDataMigrateStatus status = appDataMigrateStatusDao.get(id);
        if (isEmbedded(status)) {
            dto.getLines().addAll(readTail(status.getConfigPath(), Integer.MAX_VALUE, true));
            return dto;
        }
        String config = appDataMigrateCenter.showDataMigrateConf(id);
        if (StringUtils.isNotBlank(config)) {
            dto.getLines().addAll(Arrays.asList(config.split(ConstUtils.NEXT_LINE)));
        }
        return dto;
    }

    public Map<String, Object> getProcess(long id, int migrateTool) {
        Map<String, Object> result = new HashMap<>();
        if (migrateTool == 0) {
            AppDataMigrateStatus status = appDataMigrateStatusDao.get(id);
            if (status != null && StringUtils.startsWith(status.getMigrateMachineIp(), "embedded@")) {
                result.putAll(embeddedRedisShakeService.progress(id));
            } else {
                result.put("process", redisShakeCenter.showProcess(id));
            }
        } else {
            result.put("toolStats", appDataMigrateCenter.showMiragteToolProcess(id));
        }
        return result;
    }

    private int getMigrateMachineUsed(String migrateMachineIp) {
        try {
            String cmd = "ps -ef | grep redis-shake | grep -v grep | grep -v tail";
            String response = SSHUtil.execute(migrateMachineIp, cmd);
            if (StringUtils.isNotEmpty(response)) {
                return response.split(ConstUtils.NEXT_LINE).length;
            }
        } catch (Exception ignored) {
            // ignore ssh errors
        }
        return 0;
    }

    private MigrateActionResultDto toActionResult(int status, String message, String migrateId) {
        MigrateActionResultDto dto = new MigrateActionResultDto();
        dto.setStatus(status);
        dto.setMessage(message);
        dto.setMigrateId(migrateId);
        return dto;
    }

    private String str(Map<String, Object> body, String key) {
        Object val = body.get(key);
        return val == null ? null : String.valueOf(val);
    }

    private int intVal(Map<String, Object> body, String key, int defaultValue) {
        Object val = body.get(key);
        if (val == null) return defaultValue;
        return NumberUtils.toInt(String.valueOf(val), defaultValue);
    }

    private long longVal(Map<String, Object> body, String key) {
        Object val = body.get(key);
        if (val == null) return 0L;
        return NumberUtils.toLong(String.valueOf(val), 0L);
    }

    private boolean boolVal(Map<String, Object> body, String key, boolean defaultValue) {
        Object val = body.get(key);
        return val == null ? defaultValue : Boolean.parseBoolean(String.valueOf(val));
    }

    private void populateManagedApp(Map<String, Object> body, String side) {
        if (intVal(body, side + "DataType", 0) != 1) return;
        long routeId = longVal(body, side + "AppId");
        long appId = resolveRouteAppId(routeId);
        AppDesc app = appService.getByAppId(appId);
        if (app == null) throw new BizException("选择的集群不存在: " + routeId);
        body.put(side + "AppId", appId);
        body.put(side + "Servers", migrationAddress(appId, app));
        body.put("redis" + capitalize(side) + "Pass", StringUtils.defaultString(app.getAppPassword()));
        body.put(side + "RedisMigrateIndex", app.getType() == ConstUtils.CACHE_TYPE_REDIS_CLUSTER
                ? AppDataMigrateEnum.cluster.getIndex() : AppDataMigrateEnum.standalone.getIndex());
    }

    private String migrationAddress(long appId, AppDesc app) {
        if (app == null) throw new BizException("集群不存在: " + appId);
        List<InstanceInfo> instances = appService.getAppOnlineInstanceInfo(appId);
        if (instances == null) instances = Collections.emptyList();
        List<InstanceInfo> dataNodes = instances.stream().filter(InstanceInfo::isRedisData).collect(Collectors.toList());
        if (dataNodes.isEmpty()) throw new BizException("集群没有可用的数据节点: " + app.getName());
        if (app.getType() == ConstUtils.CACHE_REDIS_SENTINEL) {
            InstanceInfo master = dataNodes.stream().filter(i -> "master".equalsIgnoreCase(i.getRoleDesc()))
                    .findFirst().orElseThrow(() -> new BizException("Sentinel 集群未找到在线主节点: " + app.getName()));
            return master.getHostPort();
        }
        return dataNodes.get(0).getHostPort();
    }

    private String capitalize(String value) {
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    private String detectedVersion(String servers, String password, String fallback) {
        String detected = embeddedRedisShakeService.detectRedisVersion(servers, password);
        return StringUtils.isNotBlank(detected) ? detected : StringUtils.defaultString(fallback);
    }

    private void fillStoredVersions(AppDataMigrateStatus status) {
        if (StringUtils.isNotBlank(status.getRedisSourceVersion())
                && StringUtils.isNotBlank(status.getRedisTargetVersion())) return;
        String sourceVersion = status.getRedisSourceVersion();
        String targetVersion = status.getRedisTargetVersion();
        if (StringUtils.isBlank(sourceVersion) && status.getSourceAppId() > 0) {
            AppDesc app = appService.getByAppId(status.getSourceAppId());
            sourceVersion = detectedVersion(status.getSourceServers(), app == null ? "" : app.getAppPassword(), "");
        }
        if (StringUtils.isBlank(targetVersion) && status.getTargetAppId() > 0) {
            AppDesc app = appService.getByAppId(status.getTargetAppId());
            targetVersion = detectedVersion(status.getTargetServers(), app == null ? "" : app.getAppPassword(), "");
        }
        status.setRedisSourceVersion(StringUtils.defaultString(sourceVersion));
        status.setRedisTargetVersion(StringUtils.defaultString(targetVersion));
        if (status.getId() > 0 && (StringUtils.isNotBlank(sourceVersion) || StringUtils.isNotBlank(targetVersion))) {
            appDataMigrateStatusDao.updateVersions(status.getId(), status.getRedisSourceVersion(), status.getRedisTargetVersion());
        }
    }

    private boolean isEmbedded(AppDataMigrateStatus status) {
        return status != null && StringUtils.startsWith(status.getMigrateMachineIp(), "embedded@");
    }

    private List<String> readTail(String path, int pageSize, boolean redactPassword) {
        if (StringUtils.isBlank(path)) return Collections.emptyList();
        try {
            List<String> lines = Files.readAllLines(Paths.get(path), StandardCharsets.UTF_8);
            int start = Math.max(0, lines.size() - pageSize);
            List<String> result = new ArrayList<>();
            for (String line : lines.subList(start, lines.size())) {
                result.add(redactPassword && line.trim().startsWith("password =")
                        ? "password = \"******\"" : line);
            }
            return result;
        } catch (Exception e) {
            return Collections.singletonList("read failed: " + e.getMessage());
        }
    }

    private List<String> readEmbeddedLog(AppDataMigrateStatus status, int pageSize) {
        if (StringUtils.isNotBlank(status.getLogPath()) && Files.exists(Paths.get(status.getLogPath()))) {
            return readTail(status.getLogPath(), pageSize, false);
        }
        if (StringUtils.isNotBlank(status.getConfigPath())) {
            String consolePath = Paths.get(status.getConfigPath()).getParent().resolve("console.log").toString();
            if (Files.exists(Paths.get(consolePath))) {
                List<String> lines = readTail(consolePath, pageSize, false);
                return lines.stream()
                        .map(line -> line.replaceAll("\\u001B\\[[;\\d]*m", ""))
                        .collect(Collectors.toList());
            }
        }
        return Collections.singletonList("RedisShake 日志尚未生成");
    }

    private MigrateListItemDto toItem(AppDataMigrateStatus s) {
        MigrateListItemDto dto = new MigrateListItemDto();
        dto.setId(s.getId());
        dto.setMigrateId(s.getMigrateId());
        dto.setMigrateTool(s.getMigrateTool());
        dto.setMigrateMachineIp(s.getMigrateMachineIp());
        dto.setSourceAppId(s.getSourceAppId());
        dto.setTargetAppId(s.getTargetAppId());
        if (s.getSourceAppId() > 0) {
            AppDesc source = appService.getByAppId(s.getSourceAppId());
            dto.setSourceAppName(source == null ? "" : source.getName());
        }
        if (s.getTargetAppId() > 0) {
            AppDesc target = appService.getByAppId(s.getTargetAppId());
            dto.setTargetAppName(target == null ? "" : target.getName());
        }
        dto.setSourceServers(s.getSourceServers());
        dto.setTargetServers(s.getTargetServers());
        dto.setUserName(s.getUserName());
        dto.setStatus(s.getStatus());
        dto.setStatusDesc(s.getStatusDesc());
        dto.setStartTime(s.getStartTimeFormat());
        dto.setEndTime(s.getEndTimeFormat());
        dto.setMigrateToolLabel(s.getMigrateTool() == 1 ? "redis-migrate-tool" : "redis-shake");
        if (s.getMigrateTool() == 1 && s.getMigrateMachinePort() > 0) {
            dto.setMigrateMachine(s.getMigrateMachineIp() + ":" + s.getMigrateMachinePort());
        } else {
            dto.setMigrateMachine(s.getMigrateMachineIp());
        }
        dto.setSourceMigrateTypeDesc(s.getSourceMigrateTypeDesc());
        dto.setTargetMigrateTypeDesc(s.getTargetMigrateTypeDesc());
        dto.setRedisSourceVersion(s.getRedisSourceVersion());
        dto.setRedisTargetVersion(s.getRedisTargetVersion());
        return dto;
    }
}
