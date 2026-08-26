package com.shcj.cache.web.service;

import com.shcj.cache.constant.InstanceStatusEnum;
import com.shcj.cache.entity.AppDesc;
import com.shcj.cache.entity.AppUser;
import com.shcj.cache.entity.InstanceInfo;
import com.shcj.cache.exception.BizException;
import com.shcj.cache.redis.AssistRedisService;
import com.shcj.cache.web.controller.api.dto.AppScrollRestartRequestDto;
import com.shcj.cache.web.controller.api.dto.ScrollRestartConfigItemDto;
import com.shcj.cache.web.enums.AppTypeEnum;
import com.shcj.cache.web.vo.AppRedisConfigVo;
import com.shcj.cache.web.vo.ExecuteResult;
import com.shcj.cache.web.vo.RedisConfigVo;
import org.apache.commons.collections.CollectionUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class AppScrollRestartApiService {

    private static final String RESTART_CONFIG_KEY = "restart:config:";

    @Resource(name = "appService")
    private AppService appService;

    @Autowired
    private AppScrollRestartService appScrollRestartService;

    @Autowired
    private AssistRedisService assistRedisService;

    public String scrollRestart(AppUser appUser, long appId, AppScrollRestartRequestDto request) {
        if (assistRedisService.get(RESTART_CONFIG_KEY + appId) != null) {
            throw new BizException("滚动重启/修改配置正在执行中，不允许重复操作。");
        }
        AppDesc appDesc = validateApp(appId);
        List<InstanceInfo> instanceList = loadGoodInstances(appId);
        if (!appScrollRestartService.handleAppInstanceInfo(instanceList, appDesc)) {
            throw new BizException("未正确获取到实例主从信息，请重试。");
        }
        AppRedisConfigVo configVo = toConfigVo(appId, request);
        if (!checkPointedInstance(instanceList, configVo.getInstanceList())) {
            throw new BizException("节点不满足此操作。");
        }
        if (assistRedisService.get(RESTART_CONFIG_KEY + appId) != null) {
            throw new BizException("滚动重启/修改配置正在执行中，不允许重复操作。");
        }
        ExecuteResult executeResult = appScrollRestartService.handleRestart(appUser, appDesc, instanceList, configVo);
        return executeResult.getMessage() != null ? executeResult.getMessage() : "滚动重启任务已提交";
    }

    public Map<String, Object> updateConfig(AppUser appUser, long appId, AppScrollRestartRequestDto request) {
        if (!assistRedisService.setNx(RESTART_CONFIG_KEY + appId, "1")) {
            throw new BizException("滚动重启/修改配置正在执行中，不允许重复操作。");
        }
        try {
            AppDesc appDesc = validateApp(appId);
            if (CollectionUtils.isEmpty(request.getConfigList())) {
                throw new BizException("参数有误，请确认。");
            }
            List<InstanceInfo> instanceList = loadGoodInstances(appId);
            if (!appScrollRestartService.handleAppInstanceInfo(instanceList, appDesc)) {
                throw new BizException("未正确获取到实例主从信息，请重试。");
            }
            AppRedisConfigVo configVo = toConfigVo(appId, request);
            if (!checkPointedInstance(instanceList, configVo.getInstanceList())) {
                throw new BizException("节点不满足此操作。");
            }
            Map<String, Object> map = appScrollRestartService.handleConfig(appUser, appDesc, instanceList, configVo);
            String handleResult = (String) map.get("errorInfo");
            if (handleResult != null && !handleResult.isEmpty()) {
                throw new BizException(handleResult);
            }
            return map;
        } finally {
            assistRedisService.remove(RESTART_CONFIG_KEY + appId);
        }
    }

    private AppDesc validateApp(long appId) {
        AppDesc appDesc = appService.getByAppId(appId);
        if (appDesc == null
                || (appDesc.getType() != AppTypeEnum.REDIS_SENTINEL.getType()
                && appDesc.getType() != AppTypeEnum.REDIS_CLUSTER.getType())) {
            throw new BizException("参数有误，请确认。");
        }
        return appDesc;
    }

    private List<InstanceInfo> loadGoodInstances(long appId) {
        List<InstanceInfo> instanceList = appService.getAppBasicInstanceInfo(appId);
        if (instanceList == null) {
            return new ArrayList<>();
        }
        return instanceList.stream()
                .filter(i -> i.getStatus() == InstanceStatusEnum.GOOD_STATUS.getStatus())
                .collect(Collectors.toList());
    }

    private AppRedisConfigVo toConfigVo(long appId, AppScrollRestartRequestDto request) {
        AppRedisConfigVo vo = new AppRedisConfigVo();
        vo.setAppId(appId);
        vo.setRecordId(request.getRecordId());
        vo.setConfigFlag(request.isConfigFlag());
        vo.setTransferFlag(request.isTransferFlag());
        if (request.getInstanceIds() != null && !request.getInstanceIds().isEmpty()) {
            vo.setInstanceList(new ArrayList<>(request.getInstanceIds()));
        }
        if (request.getConfigList() != null) {
            List<RedisConfigVo> configList = new ArrayList<>();
            for (ScrollRestartConfigItemDto item : request.getConfigList()) {
                RedisConfigVo config = new RedisConfigVo();
                config.setConfigName(item.getConfigName());
                config.setConfigValue(item.getConfigValue());
                configList.add(config);
            }
            vo.setConfigList(configList);
        }
        return vo;
    }

    private boolean checkPointedInstance(List<InstanceInfo> instanceInfoList, List<Integer> instanceIdList) {
        if (CollectionUtils.isEmpty(instanceInfoList)) {
            return false;
        }
        if (CollectionUtils.isEmpty(instanceIdList)) {
            return true;
        }
        for (Integer instanceId : instanceIdList) {
            boolean existFlag = false;
            for (InstanceInfo instanceInfo : instanceInfoList) {
                if (instanceId.equals(instanceInfo.getId())) {
                    existFlag = true;
                    break;
                }
            }
            if (!existFlag) {
                return false;
            }
        }
        return true;
    }
}
