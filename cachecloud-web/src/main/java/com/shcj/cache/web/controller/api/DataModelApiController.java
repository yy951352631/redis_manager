package com.shcj.cache.web.controller.api;

import com.shcj.cache.datamodel.DataModelQuery;
import com.shcj.cache.datamodel.DataModelService;
import com.shcj.cache.web.controller.api.dto.DataModelDto;
import com.shcj.cache.web.controller.api.dto.DataModelOptionsDto;
import com.shcj.cache.web.vo.ApiResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 数据模型：从多个维度展示平台统计数据。只读，不提供写回配置的入口。
 */
@RestController
@RequestMapping("/api/v1/data-model")
public class DataModelApiController extends AbstractAdminApiController {

    @Autowired
    private DataModelService dataModelService;

    /** 筛选项的可选值，由平台现有数据动态给出 */
    @GetMapping("/options")
    public ApiResponse<DataModelOptionsDto> options(HttpServletRequest request) {
        ApiResponse<DataModelOptionsDto> denied = requireAdmin(request);
        if (denied != null) {
            return denied;
        }
        return ApiResponse.ok(dataModelService.options());
    }

    @GetMapping("/command-latency")
    public ApiResponse<DataModelDto> commandLatency(HttpServletRequest request, QueryParams params) {
        ApiResponse<DataModelDto> denied = requireAdmin(request);
        if (denied != null) {
            return denied;
        }
        return ApiResponse.ok(dataModelService.commandLatencyBaseline(params.toQuery()));
    }

    @GetMapping("/key-size-qps")
    public ApiResponse<DataModelDto> keySizeVsQps(HttpServletRequest request, QueryParams params) {
        ApiResponse<DataModelDto> denied = requireAdmin(request);
        if (denied != null) {
            return denied;
        }
        return ApiResponse.ok(dataModelService.keySizeVsQps(params.toQuery()));
    }

    @GetMapping("/memory-persistence")
    public ApiResponse<DataModelDto> memoryVsPersistence(HttpServletRequest request, QueryParams params) {
        ApiResponse<DataModelDto> denied = requireAdmin(request);
        if (denied != null) {
            return denied;
        }
        return ApiResponse.ok(dataModelService.memoryVsPersistence(params.toQuery()));
    }

    @GetMapping("/cluster-comparison")
    public ApiResponse<DataModelDto> clusterComparison(HttpServletRequest request, QueryParams params) {
        ApiResponse<DataModelDto> denied = requireAdmin(request);
        if (denied != null) {
            return denied;
        }
        return ApiResponse.ok(dataModelService.clusterComparison(params.toQuery()));
    }

    /**
     * 全局筛选参数。多值项用逗号分隔的字符串接收，避免前端为数组参数拼装复杂查询串。
     */
    public static class QueryParams {
        private int windowHours = 168;
        private String appIds;
        private String archs;
        private String majorVersions;
        private Integer role;
        private Boolean excludeTestApp;
        private String commands;

        DataModelQuery toQuery() {
            DataModelQuery query = new DataModelQuery();
            query.setWindowHours(windowHours);
            query.setAppIds(splitLongs(appIds));
            query.setArchs(split(archs));
            query.setMajorVersions(split(majorVersions));
            query.setRole(role);
            query.setExcludeTestApp(excludeTestApp == null || excludeTestApp);
            query.setCommands(split(commands));
            return query;
        }

        private List<String> split(String value) {
            if (value == null || value.trim().isEmpty()) {
                return new ArrayList<String>();
            }
            List<String> list = new ArrayList<String>();
            for (String item : Arrays.asList(value.split(","))) {
                if (!item.trim().isEmpty()) {
                    list.add(item.trim());
                }
            }
            return list;
        }

        private List<Long> splitLongs(String value) {
            List<Long> list = new ArrayList<Long>();
            for (String item : split(value)) {
                try {
                    list.add(Long.parseLong(item));
                } catch (NumberFormatException ignore) {
                    // 非法 id 直接忽略，不因为一个坏参数让整页查不出来
                }
            }
            return list;
        }

        public void setWindowHours(int windowHours) {
            this.windowHours = windowHours;
        }

        public void setAppIds(String appIds) {
            this.appIds = appIds;
        }

        public void setArchs(String archs) {
            this.archs = archs;
        }

        public void setMajorVersions(String majorVersions) {
            this.majorVersions = majorVersions;
        }

        public void setRole(Integer role) {
            this.role = role;
        }

        public void setExcludeTestApp(Boolean excludeTestApp) {
            this.excludeTestApp = excludeTestApp;
        }

        public void setCommands(String commands) {
            this.commands = commands;
        }
    }
}
