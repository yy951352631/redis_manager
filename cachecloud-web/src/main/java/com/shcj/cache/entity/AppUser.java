package com.shcj.cache.entity;

import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 系统用户信息
 * 
 * @author leifu
 */
@Data
public class AppUser implements Serializable {

    private static final long serialVersionUID = 7425158151337667662L;

    /**
     * 自增id
     */
    private Long id;

    /**
     * 用户名(英文，域账户)
     */
    private String name;

    /**
     * 密码
     */
    private String password;

    /**
     * 中文名
     */
    private String chName;
    
    /**
     * 用户域账户邮箱
     */
    private String email;

    /**
     * 用户手机
     */
    private String mobile;

    /**
     * 用户微信号
     */
    private String weChat;

    /**
     * 用户类型(类型参考AppUserTypeEnum)
     */
    private int type;

    /**
     * 是否接收报警
     */
    private int isAlert;

    /**
     * 公司名 --> 部门名称
     */
    private String company;

    /**
     * 目的
     */
    private String purpose;

    /**
     * 注册时间
     */
    private Date registerTime;

    public static AppUser buildFrom(Long userId, String name, String chName, String email, String mobile, String weChat,
            Integer type) {
        AppUser appUser = new AppUser();
        appUser.setId(userId);
        appUser.setName(name);
        appUser.setChName(chName);
        appUser.setEmail(email);
        appUser.setMobile(mobile);
        appUser.setWeChat(weChat);
        appUser.setType(type);
        return appUser;
    }

    public static AppUser buildFrom(Long userId, String name, String chName, String email, String mobile, String weChat,
                                    Integer type,Integer isAlert) {
        AppUser appUser = new AppUser();
        appUser.setId(userId);
        appUser.setName(name);
        appUser.setChName(chName);
        appUser.setEmail(email);
        appUser.setMobile(mobile);
        appUser.setWeChat(weChat);
        appUser.setType(type);
        appUser.setIsAlert(isAlert);
        return appUser;
    }

    public static AppUser buildFrom(Long userId, String name, String chName, String email, String mobile, String weChat,
                                    Integer type,Integer isAlert, String company, String purpose) {
        AppUser appUser = new AppUser();
        appUser.setId(userId);
        appUser.setName(name);
        appUser.setChName(chName);
        appUser.setEmail(email);
        appUser.setMobile(mobile);
        appUser.setWeChat(weChat);
        appUser.setType(type);
        appUser.setIsAlert(isAlert);
        appUser.setCompany(company);
        appUser.setPurpose(purpose);
        return appUser;
    }

    public static AppUser buildFrom(Long userId, String name, String chName, String email, String mobile, String weChat,
                                    Integer type,Integer isAlert, String password, String company, String purpose) {
        AppUser appUser = new AppUser();
        appUser.setId(userId);
        appUser.setName(name);
        appUser.setChName(chName);
        appUser.setEmail(email);
        appUser.setMobile(mobile);
        appUser.setWeChat(weChat);
        appUser.setType(type);
        appUser.setIsAlert(isAlert);
        appUser.setPassword(password);
        appUser.setCompany(company);
        appUser.setPurpose(purpose);
        return appUser;
    }

    public AppUser() {
    }

    public AppUser(String name, String chName, String email, String mobile, String weChat, int type) {
        this.name = name;
        this.chName = chName;
        this.email = email;
        this.mobile = mobile;
        this.weChat = weChat;
        this.type = type;
    }

    public AppUser(String name, String chName, String email, String mobile, String weChat, int type, int isAlert) {
        this.name = name;
        this.chName = chName;
        this.email = email;
        this.mobile = mobile;
        this.weChat = weChat;
        this.type = type;
        this.isAlert = isAlert;
    }
}
