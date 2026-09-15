package com.shcj.cache.web.service.impl;

import com.shcj.cache.redis.AssistRedisService;
import com.shcj.cache.web.util.WebUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import javax.servlet.http.Cookie;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserLoginStatusCookieServiceImplTest {

    @Mock
    private AssistRedisService assistRedisService;

    private UserLoginStatusCookieServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new UserLoginStatusCookieServiceImpl();
        ReflectionTestUtils.setField(service, "assistRedisService", assistRedisService);
        ReflectionTestUtils.setField(service, "sessionTtlSeconds", 43200);
    }

    @Test
    void rejectsUserNameUsedAsBearerToken() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer admin");

        assertNull(service.getUserNameFromLoginStatus(request));
        verify(assistRedisService, never()).getWithNoSerialize(anyString());
    }

    @Test
    void createsOpaqueServerSideSessionAndSecureCookieAttributes() {
        when(assistRedisService.setWithNoSerialize(anyString(), eq("admin"), eq(43200))).thenReturn(true);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Forwarded-Proto", "https");
        MockHttpServletResponse response = new MockHttpServletResponse();

        String token = service.addLoginStatus(request, response, "admin");
        String cookie = response.getHeader("Set-Cookie");

        assertEquals(43, token.length());
        assertTrue(cookie.contains(WebUtil.LOGIN_USER_STATUS_NAME + "=" + token));
        assertTrue(cookie.contains("HttpOnly"));
        assertTrue(cookie.contains("SameSite=Lax"));
        assertTrue(cookie.contains("Secure"));
        assertFalse(cookie.contains("Domain="));
        verify(assistRedisService).setWithNoSerialize(anyString(), eq("admin"), eq(43200));
    }

    @Test
    void resolvesOpaqueBearerTokenFromRedis() {
        String token = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQ";
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + token);
        when(assistRedisService.getWithNoSerialize(anyString())).thenReturn("admin");

        assertEquals("admin", service.getUserNameFromLoginStatus(request));
    }

    @Test
    void logoutRevokesCookieSession() {
        String token = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQ";
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie(WebUtil.LOGIN_USER_STATUS_NAME, token));
        MockHttpServletResponse response = new MockHttpServletResponse();

        service.removeLoginStatus(request, response);

        verify(assistRedisService).remove(anyString());
        assertTrue(response.getHeader("Set-Cookie").contains("Max-Age=0"));
        assertTrue(response.getHeader("Set-Cookie").contains("HttpOnly"));
    }
}
