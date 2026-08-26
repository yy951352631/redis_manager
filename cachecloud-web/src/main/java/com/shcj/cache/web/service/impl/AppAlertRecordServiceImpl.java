package com.shcj.cache.web.service.impl;

import com.shcj.cache.async.AsyncThreadPoolFactory;
import com.shcj.cache.dao.AppAlertRecordDao;
import com.shcj.cache.dao.AppDao;
import com.shcj.cache.entity.*;
import com.shcj.cache.web.controller.api.dto.AlertRecordAppOptionDto;
import com.shcj.cache.web.controller.api.dto.AlertRecordItemDto;
import com.shcj.cache.web.controller.api.dto.AlertRecordPageDto;
import com.shcj.cache.web.controller.api.dto.EnumOptionDto;
import com.shcj.cache.web.enums.AlertTypeEnum;
import com.shcj.cache.redis.enums.RedisAlertConfigEnum;
import com.shcj.cache.web.enums.ImportantLevelTypeEnum;
import com.shcj.cache.web.service.AppAlertRecordService;
import com.shcj.cache.web.vo.AppDetailVO;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * @Author: zengyizhao
 * @DateTime: 2021/9/3 13:34
 * @Description: 报警记录服务实现类
 */
@Slf4j
@Service
public class AppAlertRecordServiceImpl implements AppAlertRecordService {

    private static final String TIME_PATTERN = "yyyy-MM-dd HH:mm:ss";

    /** 配置项名称里的「(单位：MB)」后缀，数值已带单位时去掉 */
    private static final Pattern UNIT_SUFFIX = Pattern.compile("\\(单位[：:][^)]*\\)");

    @Autowired
    private AppAlertRecordDao appAlertRecordDao;

    @Autowired
    private AppDao appDao;

    /**
     * 分页查询报警记录。
     */
    @Override
    public AlertRecordPageDto search(Integer importantLevel, Long appId, String ip,
                                     String keyword, Date startTime, Date endTime, int pageNo, int pageSize) {
        int safePageNo = pageNo < 1 ? 1 : pageNo;
        int safePageSize = pageSize < 1 || pageSize > 200 ? 20 : pageSize;

        AlertRecordPageDto page = new AlertRecordPageDto();
        page.setPageNo(safePageNo);
        page.setPageSize(safePageSize);
        for (ImportantLevelTypeEnum level : ImportantLevelTypeEnum.values()) {
            page.getImportantLevels().add(new EnumOptionDto(level.getType(), level.getInfo()));
        }
        // 集群下拉只列出真正出现过报警的集群，避免堆一堆永远选不出结果的选项
        Map<Long, String> appOptionCache = new HashMap<>();
        for (Long alertAppId : listAlertAppIds()) {
            page.getApps().add(new AlertRecordAppOptionDto(alertAppId,
                    StringUtils.defaultIfBlank(resolveAppName(alertAppId, appOptionCache), "应用 " + alertAppId)));
        }

        String safeIp = trimToNull(ip);
        String safeKeyword = trimToNull(keyword);
        int total = appAlertRecordDao.count(importantLevel, appId, safeIp, safeKeyword, startTime, endTime);
        page.setTotalCount(total);
        page.setTotalPages(total == 0 ? 0 : (total + safePageSize - 1) / safePageSize);
        if (total == 0) {
            return page;
        }

        List<AppAlertRecord> records = appAlertRecordDao.search(importantLevel, appId, safeIp,
                safeKeyword, startTime, endTime, (safePageNo - 1) * safePageSize, safePageSize);
        SimpleDateFormat formatter = new SimpleDateFormat(TIME_PATTERN);
        // 一页内同一个 appId 往往重复出现，缓存一次即可
        Map<Long, String> appNameCache = new HashMap<>();
        List<AlertRecordItemDto> items = new ArrayList<>(records.size());
        for (AppAlertRecord record : records) {
            items.add(toDto(record, formatter, appNameCache));
        }
        page.setItems(items);
        return page;
    }

    private List<Long> listAlertAppIds() {
        try {
            return appAlertRecordDao.listAlertAppIds();
        } catch (Exception e) {
            log.warn("list alert app ids failed: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    private AlertRecordItemDto toDto(AppAlertRecord record, SimpleDateFormat formatter, Map<Long, String> appNameCache) {
        AlertRecordItemDto dto = new AlertRecordItemDto();
        dto.setId(record.getId());
        dto.setCreateTime(record.getCreateTime() == null ? null : formatter.format(record.getCreateTime()));
        dto.setImportantLevel(record.getImportantLevel());
        dto.setImportantLevelDesc(ImportantLevelTypeEnum.getInfoByType(record.getImportantLevel()));
        dto.setAppId(record.getAppId());
        dto.setAppName(resolveAppName(record.getAppId(), appNameCache));
        dto.setInstanceId(record.getInstanceId());
        dto.setIp(record.getIp());
        dto.setPort(record.getPort());
        dto.setTitle(record.getTitle());
        dto.setContent(record.getContent());
        return dto;
    }

    private String resolveAppName(Long appId, Map<Long, String> cache) {
        if (appId == null || appId <= 0) {
            return null;
        }
        if (cache.containsKey(appId)) {
            return cache.get(appId);
        }
        String name = null;
        try {
            AppDesc appDesc = appDao.getAppDescById(appId);
            if (appDesc != null) {
                name = appDesc.getName();
            }
        } catch (Exception e) {
            log.warn("resolve app name failed appId={}: {}", appId, e.getMessage());
        }
        cache.put(appId, name);
        return name;
    }

    private String trimToNull(String value) {
        return StringUtils.isBlank(value) ? null : value.trim();
    }

    /**
     * 把分钟报警拼成人话，例如：
     * {@code 内存碎片率(mem_fragmentation_ratio) 当前 2.43，大于预设值 1.5}。
     *
     * <p>配置项名称取自 {@link RedisAlertConfigEnum}，与「报警配置」页展示的一致；
     * 括号里保留原始 INFO 字段名，便于按关键字检索。</p>
     */
    private String describeMinuteAlert(InstanceAlertValueResult instanceAlert) {
        InstanceAlertConfig config = instanceAlert.getInstanceAlertConfig();
        if (config == null) {
            return "";
        }
        String alertConfig = config.getAlertConfig();
        String unit = StringUtils.defaultString(instanceAlert.getUnit());
        StringBuilder sb = new StringBuilder();
        sb.append(configLabel(alertConfig, unit));
        sb.append(" 当前 ").append(instanceAlert.getCurrentValue()).append(unit);
        sb.append("，").append(config.getCompareInfo()).append("预设值 ");
        sb.append(config.getAlertValue()).append(unit);
        return sb.toString();
    }

    /**
     * 配置项显示名。单位已经跟在数值后面时，去掉名称里重复的「(单位：XX)」。
     */
    private String configLabel(String alertConfig, String unit) {
        RedisAlertConfigEnum configEnum = RedisAlertConfigEnum.getRedisAlertConfig(alertConfig);
        if (configEnum == null) {
            return alertConfig;
        }
        String info = configEnum.getInfo();
        if (StringUtils.isNotBlank(unit)) {
            info = UNIT_SUFFIX.matcher(info).replaceAll("");
        }
        return info + "(" + alertConfig + ")";
    }

    /** otherInfo 大多为空，为空时不要在内容里留下「其他信息:null」 */
    private void appendOtherInfo(StringBuilder sb, String otherInfo) {
        if (StringUtils.isNotBlank(otherInfo) && !"null".equals(otherInfo)) {
            sb.append("，").append(otherInfo);
        }
    }

    /**
     * 保存报警信息
     *
     * @param appAlertRecord
     * @return
     */
    @Override
    public int saveAlertInfo(AppAlertRecord appAlertRecord) {
        return appAlertRecordDao.save(appAlertRecord);
    }

    /**
     * 批量保存报警信息
     *
     * @param appAlertRecordList
     * @return
     */
    @Override
    public int saveBatchAlertInfo(List<AppAlertRecord> appAlertRecordList) {
        return appAlertRecordDao.batchSave(appAlertRecordList);
    }

    /**
     * 异步保存报警信息
     *
     * @param appAlertRecord
     * @return
     */
    @Override
    public void asyncSaveAlertInfo(AppAlertRecord appAlertRecord) {
        AsyncThreadPoolFactory.ALERT_RECORD_THREAD_POOL.execute(new Runnable() {
            @Override
            public void run() {
                appAlertRecordDao.save(appAlertRecord);
            }
        });
    }

    /**
     * 异步批量保存报警信息
     *
     * @param appAlertRecordList
     * @return
     */
    @Override
    public void asyncSaveBatchAlertInfo(List<AppAlertRecord> appAlertRecordList) {
        AsyncThreadPoolFactory.ALERT_RECORD_THREAD_POOL.execute(new Runnable() {
            @Override
            public void run() {
                appAlertRecordDao.batchSave(appAlertRecordList);
            }
        });
    }

    /**
     * 根据报警类型，保存报警信息
     *
     * @param type   类型
     * @param object
     * @return
     */
    @Override
    public int saveAlertInfoByType(AlertTypeEnum type, String title, String content, Object... object) {
        return saveAlertInfoByType(type, null, title, content, object);
    }

    @Override
    public int saveAlertInfoByTypeWithLevel(AlertTypeEnum type, int importantLevel, String title, String content,
                                            Object... object) {
        return saveAlertInfoByType(type, importantLevel, title, content, object);
    }

    private int saveAlertInfoByType(AlertTypeEnum type, Integer importantLevel, String title, String content,
                                    Object... object) {
        try {
            Date date = new Date();
            List<AppAlertRecord> appAlertRecordList = this.getAlertRecordByType(type, date, title, content, object);
            if (CollectionUtils.isNotEmpty(appAlertRecordList)) {
                if (importantLevel != null) {
                    for (AppAlertRecord record : appAlertRecordList) {
                        record.setImportantLevel(importantLevel);
                    }
                }
                this.asyncSaveBatchAlertInfo(appAlertRecordList);
            }
        } catch (Exception e) {
            log.error("saveAlertInfoByType error : ", e);
        }
        return 1;
    }

    /**
     * 根据邮件类型及信息生成对应的报警信息记录
     *
     * @param type
     * @param title
     * @param content
     * @param objects
     * @return
     */
    private List<AppAlertRecord> getAlertRecordByType(AlertTypeEnum type, Date date, String title, String content, Object... objects) {
        if (type == null) {
            return null;
        }
        if (objects == null || objects.length < 1) {
            return null;
        }
        List<AppAlertRecord> appAlertRecordList = new ArrayList<>();
        if (type == AlertTypeEnum.INSTANCE_RUNNING_STATE_CHANGE) {
            InstanceInfo info = (InstanceInfo) (objects[0]);
            generateAlertRecordByInstanceInfo(type, date, appAlertRecordList, title, content, info);
        } else if (type == AlertTypeEnum.INATANCE_EXCEPTION_STATE_MONITOR
                || type == AlertTypeEnum.INSTANCE_MINUTE_MONITOR
                || type == AlertTypeEnum.POD_RESTART_INSTANCE_RECOVER) {
            //此类型，传入的content为空，content需自己组装
            List<InstanceAlertValueResult> alertInstInfoList = (List<InstanceAlertValueResult>) (objects[0]);
            generateAlertRecordByInstanceAlertList(type, date, appAlertRecordList, title, alertInstInfoList);
        } else if (type == AlertTypeEnum.MACHINE_MEMORY_OVER_PRESET
                || type == AlertTypeEnum.POD_RESTART_SYNC_TASK) {
            String ip = (String) (objects[0]);
            generateAlertRecordByIp(type, date, appAlertRecordList, title, content, ip);
        } else if (type == AlertTypeEnum.MACHINE_MANAGE) {
            //此类型，传入的content为空，content需自己组装
            List<OperationAlertValueResult> recoverInstInfo = (List<OperationAlertValueResult>) (objects[0]);
            generateAlertRecordByOPerationList(type, date, appAlertRecordList, title, recoverInstInfo);
        } else if (type == AlertTypeEnum.APP_SHARD_CLENT_CONNECTION
                || type == AlertTypeEnum.APP_SHARD_MEM_USED_RATIO) {
            AppDetailVO appDetailVO = (AppDetailVO) (objects[0]);
            InstanceStats instanceStats = (InstanceStats) (objects[1]);
            generateAlertRecordByAppAndInstance(type, date, appAlertRecordList, title, content, appDetailVO, instanceStats);
        } else if (type == AlertTypeEnum.APP_CLIENT_CONNECTION
                || type == AlertTypeEnum.APP_HIT_RATIO
                || type == AlertTypeEnum.APP_MEM_USED_RATIO) {
            AppDetailVO appDetailVO = (AppDetailVO) (objects[0]);
            generateAlertRecordByAppDetailVO(type, date, appAlertRecordList, title, content, appDetailVO);
        }
        //此种类型type == AlertTypeEnum.APP_MINUTE_MONITOR不做处理，与 AlertTypeEnum.INSTANCE_MINUTE_MONITOR重复
        return appAlertRecordList;
    }

    /**
     * 根据实例信息生成报警信息
     *
     * @param type
     * @param appAlertRecordList
     * @param title
     * @param content
     * @param instanceInfo
     */
    private void generateAlertRecordByInstanceInfo(AlertTypeEnum type, Date date, List<AppAlertRecord> appAlertRecordList, String title, String content, InstanceInfo instanceInfo) {
        AppAlertRecord appAlertRecord = new AppAlertRecord();
        appAlertRecord.setVisibleType(type.getVisibleType());
        appAlertRecord.setImportantLevel(type.getImportantLevel());
        appAlertRecord.setTitle(title);
        appAlertRecord.setContent(content);
        appAlertRecord.setAppId(instanceInfo.getAppId());
        appAlertRecord.setInstanceId(Long.valueOf(instanceInfo.getId()));
        appAlertRecord.setIp(instanceInfo.getIp());
        appAlertRecord.setPort(instanceInfo.getPort());
        appAlertRecord.setCreateTime(date);
        appAlertRecordList.add(appAlertRecord);
    }

    /**
     * 根据机器ip生成报警信息
     *
     * @param type
     * @param appAlertRecordList
     * @param title
     * @param content
     * @param ip
     */
    private void generateAlertRecordByIp(AlertTypeEnum type, Date date, List<AppAlertRecord> appAlertRecordList, String title, String content, String ip) {
        AppAlertRecord appAlertRecord = new AppAlertRecord();
        appAlertRecord.setVisibleType(type.getVisibleType());
        appAlertRecord.setImportantLevel(type.getImportantLevel());
        appAlertRecord.setTitle(title);
        appAlertRecord.setContent(content);
        appAlertRecord.setIp(ip);
        appAlertRecord.setCreateTime(date);
        appAlertRecordList.add(appAlertRecord);
    }

    /**
     * 根据应用详细信息生成报警信息
     *
     * @param type
     * @param appAlertRecordList
     * @param title
     * @param content
     * @param appDetailVO
     */
    private void generateAlertRecordByAppDetailVO(AlertTypeEnum type, Date date, List<AppAlertRecord> appAlertRecordList, String title, String content, AppDetailVO appDetailVO) {
        AppAlertRecord appAlertRecord = new AppAlertRecord();
        appAlertRecord.setVisibleType(type.getVisibleType());
        appAlertRecord.setImportantLevel(type.getImportantLevel());
        appAlertRecord.setTitle(title);
        appAlertRecord.setContent(content);
        long appId = appDetailVO.getAppDesc().getAppId();
        appAlertRecord.setAppId(appId);
        appAlertRecord.setCreateTime(date);
        appAlertRecordList.add(appAlertRecord);
    }

    /**
     * 根据应用和实例信息生成报警信息
     *
     * @param type
     * @param appAlertRecordList
     * @param title
     * @param content
     * @param appDetailVO
     * @param instanceStats
     */
    private void generateAlertRecordByAppAndInstance(AlertTypeEnum type, Date date, List<AppAlertRecord> appAlertRecordList, String title, String content, AppDetailVO appDetailVO, InstanceStats instanceStats) {
        AppAlertRecord appAlertRecord = new AppAlertRecord();
        appAlertRecord.setVisibleType(type.getVisibleType());
        appAlertRecord.setImportantLevel(type.getImportantLevel());
        appAlertRecord.setTitle(title);
        appAlertRecord.setContent(content);
        long appId = appDetailVO.getAppDesc().getAppId();
        appAlertRecord.setAppId(appId);
        appAlertRecord.setInstanceId(instanceStats.getInstId());
        appAlertRecord.setIp(instanceStats.getIp());
        appAlertRecord.setPort(instanceStats.getPort());
        appAlertRecord.setCreateTime(date);
        appAlertRecordList.add(appAlertRecord);
    }

    /**
     * 根据实例报警结果列表生成报警信息
     *
     * @param type
     * @param appAlertRecordList
     * @param title
     * @param alertInstInfoList
     */
    private void generateAlertRecordByInstanceAlertList(AlertTypeEnum type, Date date, List<AppAlertRecord> appAlertRecordList, String title, List<InstanceAlertValueResult> alertInstInfoList) {
        for (InstanceAlertValueResult instanceAlert : alertInstInfoList) {
            AppAlertRecord appAlertRecord = new AppAlertRecord();
            appAlertRecord.setVisibleType(type.getVisibleType());
            appAlertRecord.setImportantLevel(type.getImportantLevel());
            appAlertRecord.setTitle(title);
            appAlertRecord.setAppId(instanceAlert.getAppId());
            appAlertRecord.setIp(instanceAlert.getInstanceInfo().getIp());
            appAlertRecord.setPort(instanceAlert.getInstanceInfo().getPort());
            StringBuilder sb = new StringBuilder();
            if (type == AlertTypeEnum.INATANCE_EXCEPTION_STATE_MONITOR) {
                sb.append("节点状态异常，当前状态：");
                sb.append(instanceAlert.getInstanceInfo().getStatusDesc());
                appendOtherInfo(sb, instanceAlert.getOtherInfo());
            }
            if (type == AlertTypeEnum.INSTANCE_MINUTE_MONITOR) {
                if (instanceAlert.getInstanceAlertConfig() != null && instanceAlert.getInstanceAlertConfig().getImportantLevel() != null) {
                    appAlertRecord.setImportantLevel(instanceAlert.getInstanceAlertConfig().getImportantLevel());
                }
                sb.append(describeMinuteAlert(instanceAlert));
                appendOtherInfo(sb, instanceAlert.getOtherInfo());
            }
            if (type == AlertTypeEnum.POD_RESTART_INSTANCE_RECOVER) {
                sb.append("节点已恢复，恢复时间：");
                sb.append(instanceAlert.getOtherInfo());
            }
            appAlertRecord.setContent(sb.toString());
            appAlertRecord.setCreateTime(date);
            appAlertRecordList.add(appAlertRecord);
        }
    }

    /**
     * 根据机器操作结果列表生成报警信息
     *
     * @param type
     * @param appAlertRecordList
     * @param title
     * @param recoverInstInfo
     */
    private void generateAlertRecordByOPerationList(AlertTypeEnum type, Date date, List<AppAlertRecord> appAlertRecordList, String title, List<OperationAlertValueResult> recoverInstInfo) {
        for (OperationAlertValueResult operationAlertValueResult : recoverInstInfo) {
            AppAlertRecord appAlertRecord = new AppAlertRecord();
            appAlertRecord.setVisibleType(type.getVisibleType());
            appAlertRecord.setImportantLevel(type.getImportantLevel());
            appAlertRecord.setTitle(title);
            MachineInfo machineInfo = operationAlertValueResult.getMachineInfo();
            StringBuilder sb = new StringBuilder();
            if (machineInfo != null) {
                appAlertRecord.setIp(machineInfo.getIp());
                sb.append("ip:");
                sb.append(machineInfo.getIp());
                sb.append(", real_ip:");
                sb.append(machineInfo.getRealIp());
                sb.append(", machineInfo(内存:");
                sb.append(machineInfo.getMem());
                sb.append(", cpu:");
                sb.append(machineInfo.getCpu());
                sb.append(", 备注说明:");
                sb.append(machineInfo.getExtraDesc());
                sb.append("), ");
            }
            sb.append("操作:");
            sb.append(operationAlertValueResult.getType());
            sb.append(", staus:");
            sb.append(operationAlertValueResult.getStatus());
            sb.append(", message:");
            sb.append(operationAlertValueResult.getMessage());
            appAlertRecord.setCreateTime(date);
            appAlertRecord.setContent(sb.toString());
            appAlertRecordList.add(appAlertRecord);
        }
    }

}
