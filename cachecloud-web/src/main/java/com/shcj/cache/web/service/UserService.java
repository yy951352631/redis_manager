package com.shcj.cache.web.service;

import com.shcj.cache.entity.AppUser;
import com.shcj.cache.web.enums.SuccessEnum;

import java.util.List;

/**
 * 用户管理service
 *
 * @author leifu
 * @Date 2014年10月27日
 * @Time 上午9:57:47
 */
public interface UserService {

    /**
     * 通过id获取用户
     *
     * @param userId
     * @return
     */
    AppUser get(Long userId);

    /**
     * 通过中文名获取用户
     *
     * @param chName
     * @return
     */
    List<AppUser> getUserList(String chName);

    /**
     * 获取所有用户
     */
    List<AppUser> getAllUser();

    /**
     * 获取某个应用下的所有用户
     *
     * @param appId
     * @return
     */
    List<AppUser> getByAppId(Long appId);

    /**
     * 获取需要报警的用户列表
     *
     * @param appId
     * @return
     */
    public List<AppUser> getAlertByAppId(Long appId);

    /**
     * 通过域账户前缀获取用户
     *
     * @param name
     * @return
     */
    AppUser getByName(String name);

    /**
     * 保存用户
     *
     * @param appUser
     * @return
     */
    SuccessEnum save(AppUser appUser);

    /**
     * 更新用户
     *
     * @param appUser
     * @return
     */
    SuccessEnum update(AppUser appUser);

    /**
     * 删除用户
     *
     * @param userId
     * @return
     */
    SuccessEnum delete(Long userId);

    /**
     * 重置密码
     *
     * @param userId
     * @return
     */
    /**
     * 修改密码
     *
     * @param userId
     * @param password
     * @return
     */
    SuccessEnum updatePwd(Long userId, String password);

    /**
     * 写入已经编码的密码，仅供认证成功后的旧哈希迁移使用。
     */
    SuccessEnum updateEncodedPwd(Long userId, String encodedPassword);

    String getPwdByName(String name);

    String getOfficerName(Long appId);

    String getOfficerName(String officer);

    /**
     * 获取某个应用下的所有负责人
     *
     * @param officer
     * @return
     */
    List<AppUser> getOfficerUserByUserIds(String officer);

    List<AppUser> getUserListLike(String searchChName);
}
