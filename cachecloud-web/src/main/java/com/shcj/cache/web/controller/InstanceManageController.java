package com.shcj.cache.web.controller;

import com.alibaba.fastjson.JSONObject;
import com.shcj.cache.entity.AppUser;
import com.shcj.cache.exception.BizException;
import com.shcj.cache.redis.enums.RedisConfigPoolEnum;
import org.apache.commons.lang.StringUtils;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * 前后端分离后仅保留 SPA 仍在调用的遗留 ajax 接口，页面渲染统一由 cachecloud-ui 承担。
 */
@Controller
@RequestMapping("/manage/instance")
public class InstanceManageController extends BaseController {

    /**
     * 修改单个实例的运行时配置。
     */
    @RequestMapping("/addInstanceConfigChange")
    public void doAddInstanceConfigChange(HttpServletRequest request, HttpServletResponse response, Long appId,
                                          String host, int port, String instanceConfigKey, String instanceConfigValue) {
        AppUser appUser = getUserInfo(request);
        logger.warn("user {} change instanceConfig:appId={},{}:{};key={};value={}", appUser.getName(), appId, host,
                port, instanceConfigKey, instanceConfigValue);

        if (!RedisConfigPoolEnum.containKey(instanceConfigKey)) {
            throw new BizException("输入的配置项名称非法，请重复输入，配置项{}", instanceConfigKey);
        }

        boolean isModify = false;
        if (StringUtils.isNotBlank(host) && port > 0 && StringUtils.isNotBlank(instanceConfigKey)) {
            isModify = instanceDeployCenter.modifyInstanceConfig(appId, null, host, port, instanceConfigKey,
                    instanceConfigValue);
        }
        logger.warn("user {} change instanceConfig:appId={},{}:{};key={};value={},result is:{}", appUser.getName(),
                appId, host, port, instanceConfigKey, instanceConfigValue, isModify);

        JSONObject json = new JSONObject();
        json.put("status", isModify ? 1 : 0);
        sendMessage(response, json.toString());
    }
}
