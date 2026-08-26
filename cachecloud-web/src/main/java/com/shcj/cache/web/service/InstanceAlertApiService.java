package com.shcj.cache.web.service;

import com.shcj.cache.dao.InstanceDao;
import com.shcj.cache.entity.InstanceAlertConfig;
import com.shcj.cache.entity.InstanceInfo;
import com.shcj.cache.exception.BizException;
import com.shcj.cache.redis.enums.*;
import com.shcj.cache.stats.instance.InstanceAlertConfigService;
import com.shcj.cache.util.TypeUtil;
import com.shcj.cache.web.controller.api.dto.*;
import com.shcj.cache.web.util.DateUtil;
import com.shcj.cache.web.vo.AlertConfig;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.math.NumberUtils;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class InstanceAlertApiService {

    @Resource
    private InstanceAlertConfigService instanceAlertConfigService;

    @Resource
    private InstanceDao instanceDao;

    public InstanceAlertPageDto getPage() {
        InstanceAlertPageDto page = new InstanceAlertPageDto();
        for (InstanceAlertCheckCycleEnum e : InstanceAlertCheckCycleEnum.getInstanceAlertCheckCycleEnumList()) {
            page.getCheckCycles().add(new EnumOptionDto(e.getValue(), e.getInfo()));
        }
        for (InstanceAlertCompareTypeEnum e : InstanceAlertCompareTypeEnum.getInstanceAlertCompareTypeEnumList()) {
            page.getCompareTypes().add(new EnumOptionDto(e.getValue(), e.getInfo()));
        }
        for (RedisAlertConfigEnum e : RedisAlertConfigEnum.getRedisAlertConfigEnumList()) {
            AlertConfigOptionDto opt = new AlertConfigOptionDto();
            opt.setValue(e.getValue());
            opt.setInfo(e.getInfo());
            page.getAlertConfigs().add(opt);
        }
        List<InstanceAlertConfig> globalList = instanceAlertConfigService.getByType(InstanceAlertTypeEnum.ALL_ALERT.getValue());
        page.setUsedGlobalConfigs(distinctUsedGlobal(globalList));
        page.setGlobalAlerts(toItems(globalList));
        List<InstanceAlertConfig> instanceList = instanceAlertConfigService.getByType(InstanceAlertTypeEnum.INSTANCE_ALERT.getValue());
        List<InstanceAlertConfig> appList = instanceAlertConfigService.getByType(InstanceAlertTypeEnum.APP_ALERT.getValue());
        fillHostPort(instanceList);
        List<InstanceAlertConfig> special = mergeSpecial(instanceList, appList);
        page.setSpecialAlerts(toItems(special));
        return page;
    }

    public void add(InstanceAlertSaveRequestDto req) {
        InstanceAlertConfig config = buildConfig(req);
        if (config.getImportantLevel() == null) {
            int level = instanceAlertConfigService.getImportantLevelByAlertConfigAndCompareType(
                    config.getAlertConfig(), config.getCompareType());
            config.setImportantLevel(level);
        }
        instanceAlertConfigService.save(config);
    }

    public void addApp(InstanceAlertSaveRequestDto req) {
        if (req.getAppId() == null || req.getAppId() <= 0) {
            throw new BizException("appId 不能为空");
        }
        List<InstanceInfo> instances = instanceDao.getEffectiveInstListByAppId(req.getAppId());
        if (CollectionUtils.isEmpty(instances)) {
            throw new BizException("集群下没有节点");
        }
        InstanceAlertConfig config = buildAppConfig(req);
        if (config.getImportantLevel() == null) {
            int level = instanceAlertConfigService.getImportantLevelByAlertConfigAndCompareType(
                    config.getAlertConfig(), config.getCompareType());
            config.setImportantLevel(level);
        }
        instanceAlertConfigService.save(config);
    }

    public void update(long id, String alertValue, int checkCycle, int compareType, int importantLevel) {
        try {
            InstanceAlertConfig org = instanceAlertConfigService.get((int) id);
            instanceAlertConfigService.update(id, alertValue, checkCycle, compareType, importantLevel);
            if (org != null && importantLevel != org.getImportantLevel()
                    && InstanceAlertTypeEnum.ALL_ALERT.getValue() == org.getType()) {
                instanceAlertConfigService.updateImportantLevel(org.getAlertConfig(), compareType, importantLevel);
            }
        } catch (DuplicateKeyException e) {
            throw new BizException("报警配置约束冲突");
        }
    }

    public void remove(long id) {
        instanceAlertConfigService.remove((int) id);
    }

    public void checkHostPort(String hostPort) {
        if (StringUtils.isBlank(hostPort)) {
            throw new BizException("hostPort 不能为空");
        }
        resolveInstance(hostPort);
    }

    private InstanceInfo resolveInstance(String hostPort) {
        String[] parts = hostPort.split(":");
        if (parts.length != 2) {
            throw new BizException("hostPort 格式错误");
        }
        InstanceInfo info = instanceDao.getAllInstByIpAndPort(parts[0], NumberUtils.toInt(parts[1]));
        if (info == null) {
            throw new BizException("节点不存在: " + hostPort);
        }
        return info;
    }

    private InstanceAlertConfig buildConfig(InstanceAlertSaveRequestDto req) {
        Date now = new Date();
        InstanceAlertConfig config = new InstanceAlertConfig();
        config.setAlertConfig(req.getAlertConfig());
        config.setAlertValue(req.getAlertValue());
        config.setConfigInfo(req.getConfigInfo());
        config.setCompareType(req.getCompareType());
        config.setCheckCycle(req.getCheckCycle());
        config.setImportantLevel(req.getImportantLevel());
        config.setType(req.getType());
        config.setLastCheckTime(now);
        config.setUpdateTime(now);
        config.setStatus(InstanceAlertStatusEnum.YES.getValue());
        if (InstanceAlertTypeEnum.INSTANCE_ALERT.getValue() == req.getType()) {
            InstanceInfo info = resolveInstance(req.getInstanceHostPort());
            config.setInstanceId(info.getId());
        }
        return config;
    }

    private InstanceAlertConfig buildAppConfig(InstanceAlertSaveRequestDto req) {
        Date now = new Date();
        InstanceAlertConfig config = new InstanceAlertConfig();
        config.setAlertConfig(req.getAlertConfig());
        config.setAlertValue(req.getAlertValue());
        config.setConfigInfo(req.getConfigInfo());
        config.setCompareType(req.getCompareType());
        config.setCheckCycle(req.getCheckCycle());
        config.setImportantLevel(req.getImportantLevel());
        config.setInstanceId(req.getAppId());
        config.setType(InstanceAlertTypeEnum.APP_ALERT.getValue());
        config.setLastCheckTime(now);
        config.setUpdateTime(now);
        config.setStatus(InstanceAlertStatusEnum.YES.getValue());
        return config;
    }

    private List<AlertConfigOptionDto> distinctUsedGlobal(List<InstanceAlertConfig> list) {
        if (CollectionUtils.isEmpty(list)) {
            return Collections.emptyList();
        }
        return list.stream().map(c -> {
            AlertConfigOptionDto dto = new AlertConfigOptionDto();
            dto.setValue(c.getAlertConfig());
            dto.setInfo(resolveConfigInfo(c.getAlertConfig(), c.getConfigInfo()));
            return dto;
        }).distinct().collect(Collectors.toList());
    }

    private void fillHostPort(List<InstanceAlertConfig> list) {
        if (CollectionUtils.isEmpty(list)) {
            return;
        }
        for (InstanceAlertConfig config : list) {
            InstanceInfo info = instanceDao.getInstanceInfoById(config.getInstanceId());
            if (info != null) {
                config.setInstanceInfo(info);
            }
        }
    }

    private List<InstanceAlertConfig> mergeSpecial(List<InstanceAlertConfig> instanceList, List<InstanceAlertConfig> appList) {
        if (CollectionUtils.isEmpty(appList)) {
            return instanceList == null ? Collections.emptyList() : instanceList;
        }
        List<InstanceAlertConfig> merged = instanceList == null ? new ArrayList<>() : new ArrayList<>(instanceList);
        merged.addAll(appList);
        merged.sort(Comparator.comparing(InstanceAlertConfig::getId));
        return merged;
    }

    /**
     * 内置报警项的说明以枚举为准。
     *
     * <p>初始化 SQL 是以非 UTF-8 字符集导入的，instance_alert_configs.config_info
     * 里的中文已经损坏且不可逆（形如 {@code aof????(???MB)}），而这一列正是
     * 「报警配置」页的配置项列。枚举里有同名项时直接覆盖，自定义项保持 DB 原值。</p>
     */
    private String resolveConfigInfo(String alertConfig, String configInfo) {
        RedisAlertConfigEnum configEnum = RedisAlertConfigEnum.getRedisAlertConfig(alertConfig);
        return configEnum == null ? configInfo : configEnum.getInfo();
    }

    private List<InstanceAlertItemDto> toItems(List<InstanceAlertConfig> list) {
        if (CollectionUtils.isEmpty(list)) {
            return Collections.emptyList();
        }
        List<InstanceAlertItemDto> items = new ArrayList<>();
        for (InstanceAlertConfig c : list) {
            InstanceAlertItemDto dto = new InstanceAlertItemDto();
            dto.setId(c.getId());
            dto.setAlertConfig(c.getAlertConfig());
            dto.setAlertValue(c.getAlertValue());
            dto.setCompareType(c.getCompareType());
            dto.setCompareInfo(c.getCompareInfo());
            dto.setConfigInfo(resolveConfigInfo(c.getAlertConfig(), c.getConfigInfo()));
            dto.setType(c.getType());
            dto.setInstanceId(c.getInstanceId());
            dto.setCheckCycle(c.getCheckCycle());
            dto.setImportantLevel(c.getImportantLevel());
            if (c.getUpdateTime() != null) {
                dto.setUpdateTime(DateUtil.formatDate(c.getUpdateTime(), "yyyy-MM-dd HH:mm:ss"));
            }
            if (c.getLastCheckTime() != null) {
                dto.setLastCheckTime(DateUtil.formatDate(c.getLastCheckTime(), "yyyy-MM-dd HH:mm:ss"));
            }
            if (c.getInstanceInfo() != null) {
                dto.setInstanceHostPort(c.getInstanceInfo().getHostPort());
            } else if (c.getType() == InstanceAlertTypeEnum.APP_ALERT.getValue()) {
                dto.setInstanceHostPort("app:" + c.getInstanceId());
            }
            items.add(dto);
        }
        return items;
    }
}
