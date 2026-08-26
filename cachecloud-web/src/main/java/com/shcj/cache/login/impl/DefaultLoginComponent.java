package com.shcj.cache.login.impl;

import com.shcj.cache.login.LoginComponent;
import com.shcj.cache.util.MD5Util;
import com.shcj.cache.utils.EnvCustomUtil;
import com.shcj.cache.web.service.UserService;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import javax.servlet.http.HttpServletRequest;
import java.io.UnsupportedEncodingException;
import java.net.InetAddress;
import java.net.URLEncoder;
import java.net.UnknownHostException;

/**
 * Created by yijunzhang
 */
@Component
public class DefaultLoginComponent implements LoginComponent {

    private Logger logger = LoggerFactory.getLogger(this.getClass());

    @Autowired
    private RestTemplate restTemplate;

    @Autowired
    private UserService userService;

    @Value(value = "${server.port:8080}")
    private String serverPort;

    /**
     * it is open for change
     *
     * @param userName
     * @param password
     * @return
     */
    @Override
    public boolean passportCheck(String userName, String password) {
        if (StringUtils.isBlank(userName) || StringUtils.isBlank(password)) {
            return false;
        }
        String pwd = userService.getPwdByName(userName);
        // 库中存 MD5；校验时对输入做 MD5
        return MD5Util.matchesStoredPassword(pwd, password);
    }

    private String getUrl() {
        InetAddress address = null;
        try {
            address = InetAddress.getLocalHost();
        } catch (UnknownHostException e) {
            logger.error(e.getMessage(), e);
        }
        if (address != null) {
            return "http://" + address.getHostAddress() + ":" + this.serverPort;
        }
        return "http://127.0.0.1:" + this.serverPort;
    }

    @Override
    public String getEmail(String ticket) {
        return null;
    }

    /**
     * 前后端分离后登录页由 SPA 承载，这里返回 SPA 的登录路由；
     * 拦截器已改为直接返回 401，本方法仅保留给自定义 SSO 实现覆写。
     */
    @Override
    public String getRedirectUrl(HttpServletRequest request) {
        StringBuilder redirectUrl = new StringBuilder("/#/login");
        String target = request.getRequestURI();
        String query = request.getQueryString();
        if (StringUtils.isNotBlank(query)) {
            target = target + "?" + query;
        }
        try {
            redirectUrl.append("?redirect=").append(URLEncoder.encode(target, "UTF-8"));
        } catch (UnsupportedEncodingException e) {
            logger.error(e.getMessage(), e);
        }
        return redirectUrl.toString();
    }

    @Override
    public String getLogoutUrl() {
        return null;
    }
}
