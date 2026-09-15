package com.shcj.cache.web.service;

import com.shcj.cache.constant.AppUserTypeEnum;
import com.shcj.cache.entity.AppUser;
import com.shcj.cache.exception.BizException;
import com.shcj.cache.web.controller.api.dto.UserListItemDto;
import com.shcj.cache.web.controller.api.dto.UserProfileUpdateDto;
import com.shcj.cache.web.controller.api.dto.UserSaveRequestDto;
import com.shcj.cache.web.enums.SuccessEnum;
import com.shcj.cache.web.util.DateUtil;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class UserManageApiService {

    @Autowired
    private UserService userService;

    public List<UserListItemDto> listUsers(String searchChName) {
        List<AppUser> users = userService.getUserListLike(searchChName);
        List<UserListItemDto> items = new ArrayList<>();
        if (users == null) {
            return items;
        }
        for (AppUser user : users) {
            items.add(toListItem(user));
        }
        return items;
    }

    public void updateProfile(AppUser existing, UserProfileUpdateDto request) {
        AppUser appUser = AppUser.buildFrom(
                existing.getId(), existing.getName(), request.getChName(), request.getEmail(),
                request.getMobile(), request.getWeChat(), existing.getType(),
                request.getIsAlert(), request.getCompany(), request.getPurpose());
        userService.update(appUser);
    }

    public void saveUser(UserSaveRequestDto request) {
        AppUser appUser = AppUser.buildFrom(
                request.getId(), request.getName(), request.getChName(), request.getEmail(),
                request.getMobile(), request.getWeChat(), request.getType(),
                request.getIsAlert(), request.getCompany(), request.getPurpose());
        if (request.getId() == null) {
            if (userService.getByName(request.getName()) != null) {
                throw new BizException("输入参数错误，已存在该用户");
            }
            if (StringUtils.isBlank(request.getPassword())) {
                throw new BizException("新增用户必须设置初始密码");
            }
            appUser.setPassword(request.getPassword());
            if (!SuccessEnum.SUCCESS.equals(userService.save(appUser))) {
                throw new BizException("新增用户失败");
            }
        } else {
            AppUser existing = userService.get(request.getId());
            if (existing == null || !existing.getName().equals(request.getName())) {
                throw new BizException("输入参数错误，id/name不匹配");
            }
            userService.update(appUser);
        }
    }

    public void deleteUser(long userId) {
        userService.delete(userId);
    }

    public void updatePassword(long userId, String password) {
        SuccessEnum result = userService.updatePwd(userId, password);
        if (!SuccessEnum.SUCCESS.equals(result)) {
            throw new BizException("修改密码失败");
        }
    }

    private UserListItemDto toListItem(AppUser user) {
        UserListItemDto dto = new UserListItemDto();
        dto.setId(user.getId());
        dto.setName(user.getName());
        dto.setChName(user.getChName());
        dto.setEmail(user.getEmail());
        dto.setMobile(user.getMobile());
        dto.setCompany(user.getCompany());
        dto.setIsAlert(user.getIsAlert());
        dto.setIsAlertLabel(user.getIsAlert() == 1 ? "是" : "否");
        dto.setType(user.getType());
        dto.setTypeLabel(resolveTypeLabel(user.getType()));
        if (user.getRegisterTime() != null) {
            dto.setRegisterTime(DateUtil.formatYYYYMMddHHMMSS(user.getRegisterTime()));
        }
        return dto;
    }

    private String resolveTypeLabel(int type) {
        if (AppUserTypeEnum.ADMIN_USER.value().equals(type)) {
            return "管理员";
        }
        if (AppUserTypeEnum.REGULAR_USER.value().equals(type)) {
            return "普通用户";
        }
        return String.valueOf(type);
    }
}
