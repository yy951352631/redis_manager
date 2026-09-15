package com.shcj.cache.web.controller.api;

import com.shcj.cache.entity.AppUser;
import com.shcj.cache.exception.BizException;
import com.shcj.cache.web.controller.api.dto.UserListItemDto;
import com.shcj.cache.web.controller.api.dto.UserSaveRequestDto;
import com.shcj.cache.web.service.UserManageApiService;
import com.shcj.cache.web.vo.ApiResponse;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/users")
public class UserManageApiController extends AbstractAdminApiController {

    @Autowired
    private UserManageApiService userManageApiService;

    @GetMapping
    public ApiResponse<List<UserListItemDto>> list(
            HttpServletRequest request,
            @RequestParam(value = "searchChName", required = false) String searchChName) {
        ApiResponse<List<UserListItemDto>> denied = requireAdmin(request);
        if (denied != null) {
            return denied;
        }
        return ApiResponse.ok(userManageApiService.listUsers(searchChName));
    }

    @PostMapping
    public ApiResponse<Void> save(HttpServletRequest request, @RequestBody UserSaveRequestDto body) {
        ApiResponse<Void> denied = requireAdmin(request);
        if (denied != null) {
            return denied;
        }
        try {
            userManageApiService.saveUser(body);
            return ApiResponse.ok();
        } catch (BizException ex) {
            return ApiResponse.fail(400, ex.getMessage());
        }
    }

    @DeleteMapping("/{userId}")
    public ApiResponse<Void> delete(HttpServletRequest request, @PathVariable long userId) {
        ApiResponse<Void> denied = requireAdmin(request);
        if (denied != null) {
            return denied;
        }
        userManageApiService.deleteUser(userId);
        return ApiResponse.ok();
    }

    @PutMapping("/{userId}/password")
    public ApiResponse<Void> updatePassword(
            HttpServletRequest request,
            @PathVariable long userId,
            @RequestBody Map<String, String> body) {
        ApiResponse<Void> denied = requireAdmin(request);
        if (denied != null) {
            return denied;
        }
        String password = body != null ? body.get("password") : null;
        if (StringUtils.isBlank(password)) {
            return ApiResponse.fail(400, "密码不能为空");
        }
        try {
            userManageApiService.updatePassword(userId, password);
            return ApiResponse.ok();
        } catch (BizException ex) {
            return ApiResponse.fail(400, ex.getMessage());
        }
    }
}
