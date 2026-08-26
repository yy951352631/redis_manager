package com.shcj.cache.web.controller;

import com.alibaba.fastjson.JSONObject;
import com.shcj.cache.web.enums.SuccessEnum;
import com.shcj.cache.entity.InstanceInfo;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.MapUtils;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

import javax.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Map;

/**
 * 前后端分离后仅保留 SPA 仍在调用的遗留 ajax 接口，页面渲染统一由 cachecloud-ui 承担。
 */
@Controller
@RequestMapping("/admin/app")
public class AppController extends BaseController {

    /**
     * 读取实例当前生效的 redis 配置项，供前端「配置变更」下拉框使用。
     */
    @RequestMapping("/redisConfig")
    public void redisConfig(HttpServletResponse response, Long appId, Integer instanceId) {
        JSONObject json = new JSONObject();
        if (instanceId == null) {
            List<InstanceInfo> instanceInfos = appService.getAppOnlineInstanceInfo(appId);
            instanceId = CollectionUtils.isNotEmpty(instanceInfos) ? instanceInfos.get(0).getId() : -1;
        }
        Map<String, String> redisConfigMap = redisCenter.getRedisConfigList(instanceId);

        if (MapUtils.isEmpty(redisConfigMap)) {
            json.put("status", String.valueOf(SuccessEnum.FAIL.value()));
        } else {
            json.put("redisConfigMap", redisConfigMap);
            json.put("status", String.valueOf(SuccessEnum.SUCCESS.value()));
        }
        sendMessage(response, json.toString());
    }
}
