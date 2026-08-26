package com.shcj.cache.web.service;

import com.shcj.cache.constant.ImportAppResult;
import com.shcj.cache.entity.AppUser;
import com.shcj.cache.entity.SystemResource;
import com.shcj.cache.stats.app.ExternalRedisCenter;
import com.shcj.cache.task.constant.ResourceEnum;
import com.shcj.cache.web.controller.api.dto.ExternalNodeListPageDto;
import com.shcj.cache.web.controller.api.dto.ExternalRedisCreateFormDto;
import com.shcj.cache.web.controller.api.dto.SelectOptionDto;
import com.shcj.cache.web.support.AppRouteIdSupport;
import com.shcj.cache.web.util.Page;
import com.shcj.cache.web.vo.ExternalNodeVO;
import org.apache.commons.lang.StringUtils;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Service
public class ExternalRedisApiService {
    @Resource
    private AppRouteIdSupport appRouteIdSupport;

    private long resolveRouteAppId(long idOrClusterNo) {
        return appRouteIdSupport.requireAppId(idOrClusterNo);
    }


    @Resource(name = "externalRedisCenter")
    private ExternalRedisCenter externalRedisCenter;

    @Resource
    private ResourceService resourceService;

    @Resource
    private UserService userService;

    public ExternalNodeListPageDto listNodes(String ip, Integer status, boolean includeSentinel, int pageNo, int pageSize) {
        List<ExternalNodeVO> all = externalRedisCenter.listExternalNodes(StringUtils.trimToEmpty(ip));
        if (all == null) {
            all = Collections.emptyList();
        }
        all = all.stream()
                .filter(node -> includeSentinel
                        ? (node.isSynced() && "sentinel".equalsIgnoreCase(node.getNodeTypeDesc()))
                        : !"sentinel".equalsIgnoreCase(node.getNodeTypeDesc()))
                .collect(java.util.stream.Collectors.toList());
        if (status != null && status >= 0) {
            final int wantedStatus = status;
            all = all.stream().filter(node -> node.getStatus() == wantedStatus).collect(java.util.stream.Collectors.toList());
        }
        int safePageNo = Math.max(pageNo, 1);
        int safePageSize = pageSize > 0 ? Math.min(pageSize, 500) : 20;
        int totalCount = all.size();
        Page page = new Page(safePageNo, safePageSize, totalCount);
        int start = page.getStart();
        List<ExternalNodeVO> items;
        if (start >= totalCount) {
            items = Collections.emptyList();
        } else {
            int end = Math.min(start + safePageSize, totalCount);
            items = new ArrayList<>(all.subList(start, end));
        }
        ExternalNodeListPageDto result = new ExternalNodeListPageDto();
        result.setItems(items);
        result.setPageNo(safePageNo);
        result.setPageSize(safePageSize);
        result.setTotalCount(totalCount);
        result.setTotalPages(page.getTotalPages());
        return result;
    }

    public ExternalRedisCreateFormDto getCreateForm(AppUser currentUser) {
        ExternalRedisCreateFormDto dto = new ExternalRedisCreateFormDto();
        if (currentUser != null) {
            dto.setCurrentUserId(currentUser.getId());
        }
        List<AppUser> users = userService.getAllUser();
        if (users != null) {
            for (AppUser user : users) {
                dto.getUsers().add(new SelectOptionDto(user.getId(), user.getName()));
            }
        }
        List<SystemResource> versions = resourceService.getResourceList(ResourceEnum.REDIS.getValue());
        if (versions != null) {
            for (SystemResource resource : versions) {
                dto.getVersions().add(new SelectOptionDto(resource.getId(), resource.getName()));
            }
        }
        dto.setDefaultVersionId(resolveDefaultRedisVersionId(versions));
        return dto;
    }

    public Map<String, String> check(int appType, String appInstanceInfo, String password, String sentinelPassword) {
        ImportAppResult result = externalRedisCenter.check(appType, appInstanceInfo, password, sentinelPassword);
        if (result.getStatus() == 1) {
            String version = externalRedisCenter.detectRedisVersionName(appType, appInstanceInfo, password);
            if (StringUtils.isBlank(version)) {
                throw new IllegalArgumentException("连接成功，但无法从 Redis 实例获取版本信息");
            }
            Map<String, String> response = new java.util.LinkedHashMap<>();
            response.put("message", result.getMessage());
            response.put("redisVersion", version);
            return response;
        }
        throw new IllegalArgumentException(result.getMessage());
    }

    public void checkName(String name) {
        ImportAppResult result = externalRedisCenter.checkName(name);
        if (result.getStatus() != 1) {
            throw new IllegalArgumentException(result.getMessage());
        }
    }

    public void save(AppUser user, Map<String, Object> body) {
        String name = str(body, "name");
        String intro = str(body, "intro");
        int type = intVal(body, "appType", intVal(body, "type", 0));
        int isTest = 0;
        String officer = str(body, "officer");
        String password = str(body, "password");
        String sentinelPassword = str(body, "sentinelPassword");
        String appInstanceInfo = str(body, "appInstanceInfo");
        int versionId = 0;

        if (StringUtils.isBlank(name)) throw new IllegalArgumentException("集群名称不能为空");
        // 集群描述为选填；留空时用集群名兜底，避免列表/详情页出现空白描述
        if (StringUtils.isBlank(intro)) {
            intro = name;
        }
        if (StringUtils.isBlank(officer)) throw new IllegalArgumentException("项目负责人不能为空");
        if (StringUtils.isBlank(appInstanceInfo)) throw new IllegalArgumentException("节点详情不能为空");

        ImportAppResult result = externalRedisCenter.register(user, name, intro, type, isTest,
                officer, password, sentinelPassword, appInstanceInfo, versionId);
        if (result.getStatus() != 1) {
            throw new IllegalArgumentException(result.getMessage());
        }
    }

    public String collectNow(long appId) {
        appId = resolveRouteAppId(appId);
        ImportAppResult result = externalRedisCenter.collectNow(appId);
        if (result.getStatus() == 1) return result.getMessage();
        throw new IllegalArgumentException(result.getMessage());
    }

    public String repairInstances(long appId) {
        appId = resolveRouteAppId(appId);
        ImportAppResult result = externalRedisCenter.repairInstances(appId);
        if (result.getStatus() == 1) return result.getMessage();
        throw new IllegalArgumentException(result.getMessage());
    }

    public String removeInstance(long appId, int instanceId) {
        appId = resolveRouteAppId(appId);
        ImportAppResult result = externalRedisCenter.removeInstance(appId, instanceId);
        if (result.getStatus() == 1) return result.getMessage();
        throw new IllegalArgumentException(result.getMessage());
    }

    public String addInstances(long appId, String appInstanceInfo) {
        appId = resolveRouteAppId(appId);
        ImportAppResult result = externalRedisCenter.addInstances(appId, appInstanceInfo);
        if (result.getStatus() == 1) return result.getMessage();
        throw new IllegalArgumentException(result.getMessage());
    }

    private int resolveDefaultRedisVersionId(List<SystemResource> redisVersionList) {
        if (redisVersionList == null || redisVersionList.isEmpty()) {
            return 0;
        }
        for (SystemResource resource : redisVersionList) {
            if (resource.getName() != null && resource.getName().startsWith("redis-")) {
                return resource.getId();
            }
        }
        return redisVersionList.get(0).getId();
    }

    private String str(Map<String, Object> body, String key) {
        Object val = body.get(key);
        return val == null ? null : String.valueOf(val);
    }

    private int intVal(Map<String, Object> body, String key, int defaultValue) {
        Object val = body.get(key);
        if (val == null) return defaultValue;
        return org.apache.commons.lang.math.NumberUtils.toInt(String.valueOf(val), defaultValue);
    }
}
