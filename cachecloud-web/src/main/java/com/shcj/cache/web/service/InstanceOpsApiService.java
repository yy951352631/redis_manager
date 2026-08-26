package com.shcj.cache.web.service;

import com.shcj.cache.constant.ErrorMessageEnum;
import com.shcj.cache.entity.*;
import com.shcj.cache.task.constant.ResourceEnum;
import com.shcj.cache.web.controller.api.dto.*;
import com.shcj.cache.web.enums.CompareTypeEnum;
import com.shcj.cache.web.enums.ConfigRestartOperateEnum;
import com.shcj.cache.web.enums.RestartStatusEnum;
import com.shcj.cache.web.util.DateUtil;
import com.shcj.cache.web.util.Page;
import com.shcj.cache.web.vo.*;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.ui.Model;

import javax.annotation.Resource;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 实例运维 API（M8）
 */
@Service
public class InstanceOpsApiService {

    @Autowired
    private AppRedisConfigCheckService appRedisConfigCheckService;

    @Autowired
    private AppRedisCommandCheckService appRedisCommandCheckService;

    @Autowired
    private AppScrollRestartService appScrollRestartService;

    @Resource(name = "appService")
    private AppService appService;

    @Autowired
    private ResourceService resourceService;

    public InstanceOpsOptionsDto getOptions() {
        InstanceOpsOptionsDto options = new InstanceOpsOptionsDto();
        for (SystemResource version : resourceService.getResourceList(ResourceEnum.REDIS.getValue())) {
            RedisVersionOptionDto item = new RedisVersionOptionDto();
            item.setId(version.getId());
            item.setName(version.getName());
            options.getRedisVersions().add(item);
        }
        CompareTypeEnum[] values = CompareTypeEnum.values();
        Arrays.sort(values, Comparator.comparingInt(CompareTypeEnum::getType));
        for (CompareTypeEnum compareType : values) {
            CompareTypeOptionDto item = new CompareTypeOptionDto();
            item.setType(compareType.getType());
            item.setLabel(compareType.getInfo());
            options.getCompareTypes().add(item);
        }
        return options;
    }

    public List<ConfigCheckRecordDto> listConfigChecks() {
        Map<Integer, String> versionNameMap = buildVersionNameMap();
        Map<Integer, String> compareLabelMap = buildCompareLabelMap();
        List<ConfigCheckRecordDto> items = new ArrayList<>();
        for (RedisConfigCheckResult result : appRedisConfigCheckService.getRedisConfigCheckResult()) {
            items.add(toConfigCheckRecord(result, versionNameMap, compareLabelMap));
        }
        return items;
    }

    public ConfigCheckDetailDto getConfigCheckDetail(String uuid) {
        Map<Integer, String> versionNameMap = buildVersionNameMap();
        Map<Integer, String> compareLabelMap = buildCompareLabelMap();
        ConfigCheckDetailDto detail = new ConfigCheckDetailDto();
        detail.setKey(uuid);
        List<AppRedisConfigCheckResult> results = appRedisConfigCheckService.getRedisConfigCheckDetailResult(uuid);
        if (CollectionUtils.isEmpty(results)) {
            return detail;
        }
        for (AppRedisConfigCheckResult checkResult : results) {
            if (CollectionUtils.isEmpty(checkResult.getInstanceCheckList())) {
                continue;
            }
            String versionName = resolveVersionName(checkResult.getAppDesc(), versionNameMap);
            for (InstanceRedisConfigCheckResult instanceCheck : checkResult.getInstanceCheckList()) {
                if (instanceCheck.isSuccess() || instanceCheck.getInstanceInfo() == null) {
                    continue;
                }
                ConfigCheckDetailRowDto row = new ConfigCheckDetailRowDto();
                row.setInstanceId(instanceCheck.getInstanceInfo().getId());
                row.setHostPort(instanceCheck.getInstanceInfo().getHostPort());
                row.setVersionName(versionName);
                if (checkResult.getAppDesc() != null) {
                    row.setAppId(checkResult.getAppDesc().getAppId());
                    row.setAppName(checkResult.getAppDesc().getName());
                }
                row.setCreateTime(checkResult.getCreateTimeStr());
                row.setConfigName(checkResult.getConfigName());
                row.setCompareType(checkResult.getCompareType());
                row.setCompareTypeLabel(compareLabelMap.getOrDefault(checkResult.getCompareType(), ""));
                row.setExpectValue(checkResult.getExpectValue());
                row.setRealValue(instanceCheck.getRealValue());
                detail.getRows().add(row);
            }
        }
        return detail;
    }

    public String runConfigCheck(AppUser user, ConfigCheckRequestDto request) {
        if (request == null || StringUtils.isBlank(request.getConfigName())) {
            throw new IllegalArgumentException("请填写配置名");
        }
        AppRedisConfigCheckVo checkVo = new AppRedisConfigCheckVo();
        checkVo.setAppId(request.getAppId());
        checkVo.setVersionId(request.getVersionId());
        checkVo.setConfigName(request.getConfigName().trim());
        checkVo.setCompareType(request.getCompareType() != null ? request.getCompareType() : CompareTypeEnum.EQUAL.getType());
        checkVo.setExpectValue(StringUtils.defaultString(request.getExpectValue()));
        RedisConfigCheckResult result = appRedisConfigCheckService.checkRedisConfig(user, checkVo);
        if (result == null) {
            throw new IllegalStateException(ErrorMessageEnum.INNER_ERROR_MSG.getMessage());
        }
        return result.getKey();
    }

    public List<CommandCheckRecordDto> listCommandChecks() {
        List<CommandCheckRecordDto> items = new ArrayList<>();
        for (RedisCommandCheckResult result : appRedisCommandCheckService.getRedisCommandCheckResult()) {
            items.add(toCommandCheckRecord(result));
        }
        return items;
    }

    public CommandCheckDetailDto getCommandCheckDetail(String uuid) {
        CommandCheckDetailDto detail = new CommandCheckDetailDto();
        detail.setKey(uuid);
        AppRedisCommandCheckResult result = appRedisCommandCheckService.getRedisCommandCheckDetailResult(uuid);
        if (result == null || CollectionUtils.isEmpty(result.getInstanceCheckList())) {
            return detail;
        }
        for (InstanceRedisCommandCheckResult instanceCheck : result.getInstanceCheckList()) {
            if (instanceCheck.isSuccess() || instanceCheck.getInstanceInfo() == null) {
                continue;
            }
            CommandCheckDetailRowDto row = new CommandCheckDetailRowDto();
            row.setInstanceId(instanceCheck.getInstanceInfo().getId());
            row.setHostPort(instanceCheck.getInstanceInfo().getHostPort());
            row.setCreateTime(result.getCreateTimeStr());
            row.setCommand(result.getCommand());
            row.setMessage(instanceCheck.getMessage());
            detail.getRows().add(row);
        }
        return detail;
    }

    public String runCommandCheck(AppUser user, CommandCheckRequestDto request) {
        if (request == null || StringUtils.isBlank(request.getCommand())) {
            throw new IllegalArgumentException("请填写命令");
        }
        String command = request.getCommand().trim();
        if (!"bgsave".equals(command) && !"bgrewriteaof".equals(command)) {
            throw new IllegalArgumentException("仅支持 bgsave 或 bgrewriteaof 命令检测");
        }
        AppRedisCommandCheckVo checkVo = new AppRedisCommandCheckVo();
        checkVo.setMachineIps(StringUtils.trimToEmpty(request.getMachineIps()));
        checkVo.setPodIp(StringUtils.trimToEmpty(request.getPodIp()));
        checkVo.setCommand(command);
        RedisCommandCheckResult result = appRedisCommandCheckService.checkRedisCommand(user, checkVo);
        if (result == null) {
            throw new IllegalStateException(ErrorMessageEnum.INNER_ERROR_MSG.getMessage());
        }
        return result.getKey();
    }

    public RestartRecordPageDto listRestartRecords(Long appId, int pageNo, int pageSize) {
        ConfigRestartRecord condition = new ConfigRestartRecord();
        condition.setAppId(appId);
        int safePageNo = Math.max(pageNo, 1);
        int safePageSize = pageSize > 0 ? Math.min(pageSize, 100) : 10;

        Model model = new ExtendedModelMap();
        List<ConfigRestartRecord> records = appScrollRestartService.getConfigRestartRecordByCondition(
                model, condition, safePageNo, safePageSize);
        Page page = (Page) model.asMap().get("page");

        Map<Integer, InstanceInfo> instanceInfoMap = buildInstanceInfoMap(appId, records);

        RestartRecordPageDto result = new RestartRecordPageDto();
        result.setPageNo(safePageNo);
        result.setPageSize(safePageSize);
        if (page != null) {
            result.setTotalCount(page.getTotalCount());
            result.setTotalPages(page.getTotalPages());
        }
        for (ConfigRestartRecord record : records) {
            result.getItems().add(toRestartRecord(record, instanceInfoMap));
        }
        return result;
    }

    public String stopRestart(Long appId) {
        if (appId == null) {
            throw new IllegalArgumentException("集群编码不能为空");
        }
        if (appScrollRestartService.existsStopRestartFlag(appId)) {
            return "停止请求不允许重复发送，请通过日志查看重启进度。";
        }
        ConfigRestartRecord condition = new ConfigRestartRecord();
        condition.setAppId(appId);
        Model model = new ExtendedModelMap();
        List<ConfigRestartRecord> records = appScrollRestartService.getConfigRestartRecordByCondition(
                model, condition, 1, 10);
        boolean existRestart = false;
        for (ConfigRestartRecord record : records) {
            if ((record.getStatus() == RestartStatusEnum.RUNNING.getValue()
                    && record.getOperateType() == ConfigRestartOperateEnum.RESTART.getValue())
                    || (record.getStatus() == RestartStatusEnum.RESTART_AFTER_CONFIG.getValue()
                    && record.getOperateType() == ConfigRestartOperateEnum.CONFIG_RESTART.getValue())) {
                existRestart = true;
                break;
            }
        }
        if (existRestart) {
            boolean result = appScrollRestartService.addStopRestartFlag(appId);
            if (result) {
                return "停止请求已发送，但不确保停止，请通过日志查看重启进度。";
            }
            throw new IllegalStateException("停止请求发送失败");
        }
        return "重启任务不存在或已结束，请确认。";
    }

    private Map<Integer, InstanceInfo> buildInstanceInfoMap(Long appId, List<ConfigRestartRecord> records) {
        List<InstanceInfo> instanceInfoList = new ArrayList<>();
        if (appId != null) {
            instanceInfoList = appService.getAppBasicInstanceInfo(appId);
        } else if (CollectionUtils.isNotEmpty(records)) {
            Set<Long> appIdSet = new HashSet<>();
            for (ConfigRestartRecord record : records) {
                if (record.getAppId() == null || appIdSet.contains(record.getAppId())) {
                    continue;
                }
                appIdSet.add(record.getAppId());
                List<InstanceInfo> appInstances = appService.getAppBasicInstanceInfo(record.getAppId());
                if (appInstances != null) {
                    instanceInfoList.addAll(appInstances);
                }
            }
        }
        if (instanceInfoList == null) {
            instanceInfoList = Collections.emptyList();
        }
        return instanceInfoList.stream()
                .collect(Collectors.toMap(InstanceInfo::getId, Function.identity(), (v1, v2) -> v1));
    }

    private RestartRecordDto toRestartRecord(ConfigRestartRecord record, Map<Integer, InstanceInfo> instanceInfoMap) {
        RestartRecordDto dto = new RestartRecordDto();
        dto.setId(record.getId());
        dto.setUserName(record.getUserName());
        dto.setAppId(record.getAppId());
        dto.setAppName(record.getAppName());
        dto.setOperateType(record.getOperateType());
        dto.setOperateTypeLabel(resolveOperateTypeLabel(record.getOperateType()));
        dto.setStatus(record.getStatus());
        dto.setStatusLabel(resolveStatusLabel(record.getStatus()));
        dto.setStartTime(formatDateTime(record.getStartTime()));
        dto.setEndTime(formatDateTime(record.getEndTime()));
        if (record.getLogList() != null) {
            dto.setLogs(new ArrayList<>(record.getLogList()));
        }
        if (record.getInstanceIdList() != null) {
            for (Integer instanceId : record.getInstanceIdList()) {
                RestartRecordInstanceDto inst = new RestartRecordInstanceDto();
                inst.setInstanceId(instanceId);
                InstanceInfo info = instanceInfoMap.get(instanceId);
                inst.setHostPort(info != null ? info.getHostPort() : "-");
                dto.getInstances().add(inst);
            }
        }
        dto.setStoppable(record.getStatus() != null
                && ((record.getStatus() == RestartStatusEnum.RUNNING.getValue()
                && record.getOperateType() == ConfigRestartOperateEnum.RESTART.getValue())
                || (record.getStatus() == RestartStatusEnum.RESTART_AFTER_CONFIG.getValue()
                && record.getOperateType() == ConfigRestartOperateEnum.CONFIG_RESTART.getValue())));
        return dto;
    }

    private CommandCheckRecordDto toCommandCheckRecord(RedisCommandCheckResult result) {
        CommandCheckRecordDto dto = new CommandCheckRecordDto();
        dto.setKey(result.getKey());
        dto.setUserName(result.getUserName());
        dto.setCreateTime(result.getCreateTimeStr());
        dto.setMachineIps(result.getMachineIps());
        dto.setPodIp(result.getPodIp());
        dto.setCommand(result.getCommand());
        dto.setSuccess(result.isSuccess());
        return dto;
    }

    private ConfigCheckRecordDto toConfigCheckRecord(RedisConfigCheckResult result,
                                                     Map<Integer, String> versionNameMap,
                                                     Map<Integer, String> compareLabelMap) {
        ConfigCheckRecordDto dto = new ConfigCheckRecordDto();
        dto.setKey(result.getKey());
        dto.setVersionId(result.getVersionId());
        dto.setVersionName(resolveVersionNameById(result.getVersionId(), versionNameMap));
        dto.setUserName(result.getUserName());
        dto.setCreateTime(result.getCreateTimeStr());
        dto.setConfigName(result.getConfigName());
        dto.setCompareType(result.getCompareType());
        dto.setCompareTypeLabel(compareLabelMap.getOrDefault(result.getCompareType(), ""));
        dto.setExpectValue(result.getExpectValue());
        dto.setSuccess(result.isSuccess());
        return dto;
    }

    private Map<Integer, String> buildVersionNameMap() {
        Map<Integer, String> map = new HashMap<>();
        for (SystemResource version : resourceService.getResourceList(ResourceEnum.REDIS.getValue())) {
            map.put(version.getId(), version.getName());
        }
        return map;
    }

    private Map<Integer, String> buildCompareLabelMap() {
        Map<Integer, String> map = new HashMap<>();
        for (CompareTypeEnum compareType : CompareTypeEnum.values()) {
            map.put(compareType.getType(), compareType.getInfo());
        }
        return map;
    }

    private String resolveVersionNameById(Integer versionId, Map<Integer, String> versionNameMap) {
        if (versionId == null || versionId == 0) {
            return "所有";
        }
        return versionNameMap.getOrDefault(versionId, String.valueOf(versionId));
    }

    private String resolveVersionName(AppDesc appDesc, Map<Integer, String> versionNameMap) {
        if (appDesc == null || appDesc.getVersionId() <= 0) {
            return "-";
        }
        return versionNameMap.getOrDefault(appDesc.getVersionId(), String.valueOf(appDesc.getVersionId()));
    }

    private String resolveOperateTypeLabel(int operateType) {
        if (operateType == ConfigRestartOperateEnum.RESTART.getValue()) {
            return "滚动重启";
        }
        if (operateType == ConfigRestartOperateEnum.CONFIG_RESTART.getValue()) {
            return "修改冷配置并重启";
        }
        if (operateType == ConfigRestartOperateEnum.CONFIG.getValue()) {
            return "修改热配置";
        }
        return String.valueOf(operateType);
    }

    private String resolveStatusLabel(Integer status) {
        if (status == null) {
            return "-";
        }
        if (status == RestartStatusEnum.WAITING.getValue()) {
            return "等待中";
        }
        if (status == RestartStatusEnum.RUNNING.getValue()) {
            return "运行中";
        }
        if (status == RestartStatusEnum.SUCCESS.getValue()) {
            return "成功";
        }
        if (status == RestartStatusEnum.FAIL.getValue()) {
            return "失败";
        }
        if (status == RestartStatusEnum.NEED_RESTART.getValue()) {
            return "配置已改待重启";
        }
        if (status == RestartStatusEnum.RESTART_AFTER_CONFIG.getValue()) {
            return "配置已改重启中";
        }
        if (status == RestartStatusEnum.INTERUPT.getValue()) {
            return "已停止";
        }
        return String.valueOf(status);
    }

    private String formatDateTime(Date date) {
        if (date == null) {
            return "-";
        }
        return DateUtil.formatYYYYMMddHHMMSS(date);
    }
}
