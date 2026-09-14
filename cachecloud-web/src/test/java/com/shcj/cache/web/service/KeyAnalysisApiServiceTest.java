package com.shcj.cache.web.service;

import com.alibaba.fastjson.JSON;
import com.shcj.cache.constant.AppCheckEnum;
import com.shcj.cache.dao.AppAuditDao;
import com.shcj.cache.dao.AppKeyAnalysisStatsDao;
import com.shcj.cache.entity.AppAudit;
import com.shcj.cache.entity.AppDesc;
import com.shcj.cache.entity.AppUser;
import com.shcj.cache.entity.InstanceInfo;
import com.shcj.cache.redis.RedisCenter;
import com.shcj.cache.task.TaskService;
import com.shcj.cache.web.controller.api.dto.KeyAnalysisStartResultDto;
import com.shcj.cache.web.controller.api.dto.KeyAnalysisStatsSnapshotDto;
import com.shcj.cache.web.support.AppRouteIdSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class KeyAnalysisApiServiceTest {

    private static final long APP_ID = 7L;
    private static final long AUDIT_ID = 41L;

    @Mock
    private AppRouteIdSupport appRouteIdSupport;
    @Mock
    private AppService appService;
    @Mock
    private AppAuditDao appAuditDao;
    @Mock
    private TaskService taskService;
    @Mock
    private RedisCenter redisCenter;
    @Mock
    private AppKeyAnalysisStatsDao appKeyAnalysisStatsDao;

    @InjectMocks
    private KeyAnalysisApiService service;

    private AppUser user;

    @BeforeEach
    public void setUp() {
        user = new AppUser();
        user.setId(1L);

        AppDesc app = new AppDesc();
        app.setAppId(APP_ID);
        AppAudit audit = new AppAudit();
        audit.setId(AUDIT_ID);
        InstanceInfo instance = new InstanceInfo();
        instance.setIp("127.0.0.1");
        instance.setPort(6379);

        when(appRouteIdSupport.requireAppId(APP_ID)).thenReturn(APP_ID);
        when(appService.listDefaultKeyAnalysisInstances(APP_ID)).thenReturn(Collections.singletonList(instance));
        when(appService.getByAppId(APP_ID)).thenReturn(app);
        when(appService.saveAppKeyAnalysis(app, user, "reason", null)).thenReturn(audit);
    }

    @Test
    public void emptyTargetCompletesImmediatelyWithEmptySnapshot() {
        when(redisCenter.getDbSize(APP_ID, "127.0.0.1", 6379)).thenReturn(0L);

        KeyAnalysisStartResultDto result = service.start(APP_ID, user, "reason", null, 2048L, 3000L);

        assertEquals(AUDIT_ID, result.getAuditId());
        assertEquals(0L, result.getTaskId());
        verify(taskService, never()).addAppKeyAnalysisTask(anyLong(), anyLong(), anyLong(), anyLong(), anyLong());
        verify(appAuditDao).updateParam2(AUDIT_ID, "0");
        verify(appAuditDao).updateAppAudit(AUDIT_ID, AppCheckEnum.APP_PASS.value());

        ArgumentCaptor<String> snapshotJson = ArgumentCaptor.forClass(String.class);
        verify(appKeyAnalysisStatsDao).upsertStats(eq(APP_ID), eq(AUDIT_ID), snapshotJson.capture());
        KeyAnalysisStatsSnapshotDto snapshot = JSON.parseObject(snapshotJson.getValue(), KeyAnalysisStatsSnapshotDto.class);
        assertEquals(2048L, snapshot.getBigKeyStringBytes());
        assertEquals(3000L, snapshot.getBigKeyCollectionElements());
    }

    @Test
    public void nonEmptyTargetStillCreatesAnalysisTask() {
        when(redisCenter.getDbSize(APP_ID, "127.0.0.1", 6379)).thenReturn(12L);
        when(taskService.addAppKeyAnalysisTask(APP_ID, AUDIT_ID, 0L, 2048L, 3000L)).thenReturn(88L);

        KeyAnalysisStartResultDto result = service.start(APP_ID, user, "reason", null, 2048L, 3000L);

        assertEquals(AUDIT_ID, result.getAuditId());
        assertEquals(88L, result.getTaskId());
        verify(appKeyAnalysisStatsDao, never()).upsertStats(anyLong(), anyLong(), org.mockito.ArgumentMatchers.anyString());
    }
}
