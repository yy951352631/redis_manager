package com.shcj.cache.web.controller.api;

import com.shcj.cache.constant.AppUserTypeEnum;
import com.shcj.cache.entity.AppUser;
import com.shcj.cache.login.LoginComponent;
import com.shcj.cache.web.controller.BaseController;
import com.shcj.cache.exception.BizException;
import com.shcj.cache.web.controller.api.dto.CurrentUserDto;
import com.shcj.cache.web.controller.api.dto.LoginRequestDto;
import com.shcj.cache.web.controller.api.dto.LoginResponseDto;
import com.shcj.cache.web.controller.api.dto.UserProfileUpdateDto;
import com.shcj.cache.web.service.UserLoginStatusService;
import com.shcj.cache.web.service.UserManageApiService;
import com.shcj.cache.web.vo.ApiResponse;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 前后端分离 SPA 认证接口
 */
@RestController
@RequestMapping("/api/v1")
public class AuthApiController extends BaseController {

    @Resource(name = "userLoginStatusService")
    private UserLoginStatusService userLoginStatusService;

    @Autowired(required = false)
    private LoginComponent loginComponent;

    @Autowired
    private UserManageApiService userManageApiService;

    @PostMapping("/auth/login")
    public ApiResponse<LoginResponseDto> login(@RequestBody LoginRequestDto body,
                                               HttpServletRequest request,
                                               HttpServletResponse response) {
        if (body == null || StringUtils.isBlank(body.getUsername()) || StringUtils.isBlank(body.getPassword())) {
            return ApiResponse.fail(400, "用户名和密码不能为空");
        }
        String userName = body.getUsername().trim();
        String password = body.getPassword();
        boolean requireAdmin = body.getIsAdmin() == null || Boolean.TRUE.equals(body.getIsAdmin());

        if (loginComponent == null || !loginComponent.passportCheck(userName, password)) {
            return ApiResponse.fail(401, "用户名或密码错误");
        }

        AppUser user = userService.getByName(userName);
        if (user == null || AppUserTypeEnum.NO_USER.value().equals(user.getType())) {
            return ApiResponse.fail(403, "用户不存在或无 CacheCloud 权限");
        }
        if (requireAdmin && !AppUserTypeEnum.ADMIN_USER.value().equals(user.getType())) {
            return ApiResponse.fail(403, "需要管理员权限");
        }

        userLoginStatusService.addLoginStatus(request, response, user.getName());

        LoginResponseDto data = new LoginResponseDto();
        data.setToken(user.getName());
        data.setUsername(user.getName());
        data.setRoles(resolveRoles(user));
        return ApiResponse.ok(data);
    }

    @PostMapping("/auth/logout")
    public ApiResponse<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        userLoginStatusService.removeLoginStatus(request, response);
        return ApiResponse.ok();
    }

    @GetMapping("/users/me")
    public ApiResponse<CurrentUserDto> currentUser(HttpServletRequest request) {
        String userName = resolveAuthenticatedUserName(request);
        if (StringUtils.isBlank(userName)) {
            return ApiResponse.fail(401, "未登录或登录已过期");
        }
        AppUser user = userService.getByName(userName);
        if (user == null || AppUserTypeEnum.NO_USER.value().equals(user.getType())) {
            return ApiResponse.fail(401, "用户不存在或已失效");
        }

        return ApiResponse.ok(toCurrentUserDto(user));
    }

    @PutMapping("/users/me")
    public ApiResponse<Void> updateProfile(HttpServletRequest request, @RequestBody UserProfileUpdateDto body) {
        String userName = resolveAuthenticatedUserName(request);
        if (StringUtils.isBlank(userName)) {
            return ApiResponse.fail(401, "未登录或登录已过期");
        }
        if (body == null || StringUtils.isBlank(body.getChName())
                || StringUtils.isBlank(body.getEmail()) || StringUtils.isBlank(body.getMobile())) {
            return ApiResponse.fail(400, "中文名、邮箱和手机不能为空");
        }
        AppUser user = userService.getByName(userName);
        if (user == null || AppUserTypeEnum.NO_USER.value().equals(user.getType())) {
            return ApiResponse.fail(401, "用户不存在或已失效");
        }
        try {
            userManageApiService.updateProfile(user, body);
            return ApiResponse.ok();
        } catch (BizException ex) {
            return ApiResponse.fail(400, ex.getMessage());
        }
    }

    private CurrentUserDto toCurrentUserDto(AppUser user) {
        CurrentUserDto data = new CurrentUserDto();
        data.setId(user.getId());
        data.setUsername(user.getName());
        data.setChName(user.getChName());
        data.setEmail(user.getEmail());
        data.setMobile(user.getMobile());
        data.setWeChat(user.getWeChat());
        data.setCompany(user.getCompany());
        data.setPurpose(user.getPurpose());
        data.setType(user.getType());
        data.setIsAlert(user.getIsAlert());
        data.setRoles(resolveRoles(user));
        return data;
    }

    /**
     * Cookie 会话（JSP）或 Authorization Bearer（SPA 前后端分离）
     */
    private String resolveAuthenticatedUserName(HttpServletRequest request) {
        String userName = userLoginStatusService.getUserNameFromLoginStatus(request);
        if (StringUtils.isNotBlank(userName)) {
            return userName;
        }
        String authHeader = request.getHeader("Authorization");
        if (StringUtils.isBlank(authHeader) || !authHeader.startsWith("Bearer ")) {
            return null;
        }
        userName = authHeader.substring(7).trim();
        if (StringUtils.isBlank(userName)) {
            return null;
        }
        AppUser user = userService.getByName(userName);
        if (user == null || AppUserTypeEnum.NO_USER.value().equals(user.getType())) {
            return null;
        }
        return userName;
    }

    private List<String> resolveRoles(AppUser user) {
        if (user == null) {
            return Collections.singletonList("DEFAULT_ROLE");
        }
        List<String> roles = new ArrayList<>();
        if (AppUserTypeEnum.ADMIN_USER.value().equals(user.getType())) {
            roles.add("admin");
        } else if (AppUserTypeEnum.REGULAR_USER.value().equals(user.getType())) {
            roles.add("editor");
        } else {
            roles.add("DEFAULT_ROLE");
        }
        return roles;
    }
}
