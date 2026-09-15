package com.shcj.cache.login.impl;

import com.shcj.cache.entity.AppUser;
import com.shcj.cache.util.MD5Util;
import com.shcj.cache.util.PasswordHashUtil;
import com.shcj.cache.web.enums.SuccessEnum;
import com.shcj.cache.web.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultLoginComponentTest {

    @Mock
    private UserService userService;

    private DefaultLoginComponent component;

    @BeforeEach
    void setUp() {
        component = new DefaultLoginComponent();
        ReflectionTestUtils.setField(component, "userService", userService);
    }

    @Test
    void upgradesLegacyMd5AfterSuccessfulLogin() {
        AppUser user = new AppUser();
        user.setId(7L);
        when(userService.getPwdByName("legacy")).thenReturn(MD5Util.string2MD5("password"));
        when(userService.getByName("legacy")).thenReturn(user);
        when(userService.updateEncodedPwd(org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.anyString())).thenReturn(SuccessEnum.SUCCESS);

        assertTrue(component.passportCheck("legacy", "password"));

        ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);
        verify(userService).updateEncodedPwd(org.mockito.ArgumentMatchers.eq(7L), hash.capture());
        assertTrue(PasswordHashUtil.matches(hash.getValue(), "password"));
    }

    @Test
    void doesNotRewriteCurrentBcryptHash() {
        when(userService.getPwdByName("current")).thenReturn(PasswordHashUtil.encode("password"));

        assertTrue(component.passportCheck("current", "password"));

        verify(userService, never()).updateEncodedPwd(
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyString());
    }
}
