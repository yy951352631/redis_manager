package com.shcj.cache.web.controller.api;

import com.alibaba.fastjson.JSON;
import com.shcj.cache.entity.AppUser;
import com.shcj.cache.entity.OfflineAnalysisRecord;
import com.shcj.cache.offline.OfflineAnalysisService;
import com.shcj.cache.web.controller.api.dto.KeyAnalysisResultDto;
import com.shcj.cache.web.controller.api.dto.KeyAnalysisStatsSnapshotDto;
import com.shcj.cache.web.controller.api.dto.OfflineAnalysisPageDto;
import com.shcj.cache.web.controller.api.dto.OfflineAnalysisRecordDto;
import com.shcj.cache.web.vo.ApiResponse;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.HttpServletRequest;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

/**
 * 离线数据分析：上传 RDB 文件解析，结果结构与键值分析一致，前端复用同一套报告渲染。
 */
@RestController
@RequestMapping("/api/v1/offline-analysis")
public class OfflineAnalysisApiController extends AbstractAdminApiController {

    private static final String TIME_PATTERN = "yyyy-MM-dd HH:mm:ss";

    @Autowired
    private OfflineAnalysisService offlineAnalysisService;

    @GetMapping
    public ApiResponse<OfflineAnalysisPageDto> list(HttpServletRequest request,
                                                    @RequestParam(value = "pageNo", defaultValue = "1") int pageNo,
                                                    @RequestParam(value = "pageSize", defaultValue = "20") int pageSize) {
        ApiResponse<OfflineAnalysisPageDto> denied = requireAdmin(request);
        if (denied != null) {
            return denied;
        }
        List<OfflineAnalysisRecord> records = offlineAnalysisService.list(pageNo, pageSize);
        OfflineAnalysisPageDto page = new OfflineAnalysisPageDto();
        page.setPageNo(pageNo <= 0 ? 1 : pageNo);
        page.setPageSize(pageSize <= 0 ? 20 : pageSize);
        page.setTotalCount(offlineAnalysisService.count());
        for (OfflineAnalysisRecord record : records) {
            page.getItems().add(toDto(record));
        }
        return ApiResponse.ok(page);
    }

    @PostMapping
    public ApiResponse<OfflineAnalysisRecordDto> upload(HttpServletRequest request,
                                                        @RequestParam("file") MultipartFile file) {
        ApiResponse<OfflineAnalysisRecordDto> denied = requireAdmin(request);
        if (denied != null) {
            return denied;
        }
        AppUser user = resolveApiUser(request);
        OfflineAnalysisRecord record = offlineAnalysisService.upload(file, user == null ? "" : user.getName());
        return ApiResponse.ok(toDto(record));
    }

    /**
     * 结果按 KeyAnalysisResultDto 返回，前端直接复用键值分析的报告组件。
     */
    @GetMapping("/{id}/result")
    public ApiResponse<KeyAnalysisResultDto> result(HttpServletRequest request, @PathVariable("id") long id) {
        ApiResponse<KeyAnalysisResultDto> denied = requireAdmin(request);
        if (denied != null) {
            return denied;
        }
        OfflineAnalysisRecord record = offlineAnalysisService.get(id);
        if (record == null) {
            return ApiResponse.fail(404, "记录不存在");
        }
        if (record.getStatus() != OfflineAnalysisRecord.STATUS_DONE || StringUtils.isBlank(record.getResultJson())) {
            return ApiResponse.fail(400, "该记录尚未分析完成");
        }
        KeyAnalysisStatsSnapshotDto snapshot =
                JSON.parseObject(record.getResultJson(), KeyAnalysisStatsSnapshotDto.class);

        KeyAnalysisResultDto dto = new KeyAnalysisResultDto();
        dto.setAppId(0);
        dto.setAuditId(record.getId());
        dto.setTotalKeyCount(record.getKeyCount());
        dto.setEmptyDb(record.getKeyCount() == 0);
        dto.setStatsMissing(false);
        dto.getAnalysisNodes().add(record.getFileName());
        dto.setHasDistributionData(snapshot.isHasDistributionData());
        dto.setHasTypeMemoryData(snapshot.isHasTypeMemoryData());
        dto.setTopKeyFromMemoryScan(snapshot.isTopKeyFromMemoryScan());
        dto.setBigKeyCount(snapshot.getBigKeyCount());
        dto.setBigKeyStringBytes(snapshot.getBigKeyStringBytes());
        dto.setBigKeyCollectionElements(snapshot.getBigKeyCollectionElements());
        dto.setAnalysisRisks(snapshot.getAnalysisRisks());
        dto.setHasAnalysisRisk(!snapshot.getAnalysisRisks().isEmpty());
        dto.setIdleKeyDistri(snapshot.getIdleKeyDistri());
        dto.setKeyTypeDistri(snapshot.getKeyTypeDistri());
        dto.setKeyTtlDistri(snapshot.getKeyTtlDistri());
        dto.setKeyValueSizeDistri(snapshot.getKeyValueSizeDistri());
        dto.setValueSizeMaxBytes(snapshot.getValueSizeMaxBytes());
        dto.setValueSizeMinBytes(snapshot.getValueSizeMinBytes());
        dto.setValueSizeSumBytes(snapshot.getValueSizeSumBytes());
        dto.setValueSizeSampleCount(snapshot.getValueSizeSampleCount());
        dto.setKeyTypeMemoryDistri(snapshot.getKeyTypeMemoryDistri());
        dto.setTopKeys(snapshot.getTopKeys());
        dto.setBigKeys(snapshot.getBigKeys());
        dto.setKeyPrefixTop(snapshot.getKeyPrefixTop());
        return ApiResponse.ok(dto);
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(HttpServletRequest request, @PathVariable("id") long id) {
        ApiResponse<Void> denied = requireAdmin(request);
        if (denied != null) {
            return denied;
        }
        offlineAnalysisService.delete(id);
        return ApiResponse.ok(null);
    }

    private OfflineAnalysisRecordDto toDto(OfflineAnalysisRecord record) {
        OfflineAnalysisRecordDto dto = new OfflineAnalysisRecordDto();
        dto.setId(record.getId() == null ? 0 : record.getId());
        dto.setFileName(record.getFileName());
        dto.setFileSize(record.getFileSize());
        dto.setFileSizeLabel(humanBytes(record.getFileSize()));
        dto.setFileType(record.getFileType());
        dto.setStatus(record.getStatus());
        dto.setStatusDesc(statusDesc(record.getStatus()));
        dto.setKeyCount(record.getKeyCount());
        dto.setErrorMsg(record.getErrorMsg());
        dto.setUserName(record.getUserName());
        dto.setCreateTime(formatTime(record.getCreateTime()));
        dto.setUpdateTime(formatTime(record.getUpdateTime()));
        return dto;
    }

    private String statusDesc(int status) {
        switch (status) {
            case OfflineAnalysisRecord.STATUS_PENDING:
                return "排队中";
            case OfflineAnalysisRecord.STATUS_RUNNING:
                return "分析中";
            case OfflineAnalysisRecord.STATUS_DONE:
                return "已完成";
            case OfflineAnalysisRecord.STATUS_FAILED:
                return "失败";
            default:
                return "未知";
        }
    }

    private String formatTime(Date date) {
        return date == null ? "" : new SimpleDateFormat(TIME_PATTERN).format(date);
    }

    private String humanBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + "B";
        }
        if (bytes < 1024 * 1024) {
            return String.format("%.2fKB", bytes / 1024.0);
        }
        if (bytes < 1024L * 1024 * 1024) {
            return String.format("%.2fMB", bytes / 1024.0 / 1024.0);
        }
        return String.format("%.2fGB", bytes / 1024.0 / 1024.0 / 1024.0);
    }
}
