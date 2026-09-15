package com.shcj.cache.web.service;

import com.shcj.cache.constant.ImportAppResult;
import com.shcj.cache.constant.InstanceStatusEnum;
import com.shcj.cache.stats.app.ExternalRedisCenter;
import com.shcj.cache.web.controller.api.dto.ExternalNodeListPageDto;
import com.shcj.cache.web.support.AppRouteIdSupport;
import com.shcj.cache.web.vo.ExternalNodeVO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class ExternalRedisApiServiceTest {

    private static final long APP_ID = 7L;

    @Mock
    private AppRouteIdSupport appRouteIdSupport;
    @Mock
    private ExternalRedisCenter externalRedisCenter;
    @Mock
    private AppDetailTabApiService appDetailTabApiService;

    @InjectMocks
    private ExternalRedisApiService service;

    @Test
    public void successfulRemovalEvictsTopologyCache() {
        ImportAppResult result = ImportAppResult.success();
        result.setMessage("removed");
        when(appRouteIdSupport.requireAppId(APP_ID)).thenReturn(APP_ID);
        when(externalRedisCenter.removeInstance(APP_ID, 3)).thenReturn(result);

        assertEquals("removed", service.removeInstance(APP_ID, 3));

        verify(appDetailTabApiService).evictTopologyCache(APP_ID);
    }

    @Test
    public void offlineFilterIncludesOfflineAndForgottenInstances() {
        ExternalNodeVO active = node(1, InstanceStatusEnum.GOOD_STATUS.getStatus());
        ExternalNodeVO offline = node(2, InstanceStatusEnum.OFFLINE_STATUS.getStatus());
        ExternalNodeVO forgotten = node(3, InstanceStatusEnum.FORGET_STATUS.getStatus());
        when(externalRedisCenter.listExternalNodes("")).thenReturn(Arrays.asList(active, offline, forgotten));

        ExternalNodeListPageDto result = service.listNodes(
                "", InstanceStatusEnum.OFFLINE_STATUS.getStatus(), false, 1, 20);

        assertEquals(2, result.getTotalCount());
        assertEquals(2, result.getItems().size());
        assertEquals(2, result.getItems().get(0).getInstanceId());
        assertEquals(3, result.getItems().get(1).getInstanceId());
    }

    private ExternalNodeVO node(int instanceId, int status) {
        ExternalNodeVO node = new ExternalNodeVO();
        node.setInstanceId(instanceId);
        node.setStatus(status);
        node.setNodeTypeDesc("redis-server");
        return node;
    }
}
