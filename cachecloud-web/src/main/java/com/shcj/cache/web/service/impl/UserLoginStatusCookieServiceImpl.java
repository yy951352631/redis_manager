package com.shcj.cache.web.service.impl;

import com.shcj.cache.entity.AppUser;
import com.shcj.cache.login.LoginComponent;
import com.shcj.cache.redis.AssistRedisService;
import com.shcj.cache.web.service.UserLoginStatusService;
import com.shcj.cache.web.util.WebUtil;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * cookie保护登录状态
 *
 * @author leifu
 */
@Service("userLoginStatusService")
public class UserLoginStatusCookieServiceImpl implements UserLoginStatusService {

    private static final Logger LOGGER = LoggerFactory.getLogger(UserLoginStatusCookieServiceImpl.class);

    private static final String SESSION_KEY_PREFIX = "cc:auth:session:";

    private static final String BEARER_PREFIX = "Bearer ";

    private static final Pattern TOKEN_PATTERN = Pattern.compile("[A-Za-z0-9_-]{43,128}");

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private static final Base64.Encoder TOKEN_ENCODER = Base64.getUrlEncoder().withoutPadding();

    @Autowired
    private LoginComponent loginComponent;

    @Autowired
    private AssistRedisService assistRedisService;

    @Value("${cachecloud.auth.session-ttl-seconds:43200}")
    private int sessionTtlSeconds;

    @Override
    public String getUserNameFromLoginStatus(HttpServletRequest request) {
        for (String token : getRequestTokens(request)) {
            if (!TOKEN_PATTERN.matcher(token).matches()) {
                continue;
            }
            String userName = assistRedisService.getWithNoSerialize(toSessionKey(token));
            if (StringUtils.isNotBlank(userName)) {
                return userName;
            }
        }
        return null;
    }

    @Override
    public String getUserNameFromTicket(HttpServletRequest request) {
        String ticket = request.getParameter("ticket");
        if (StringUtils.isNotBlank(ticket)) {
            String email = loginComponent.getEmail(ticket);
            if (StringUtils.isNotBlank(email) && email.contains("@")) {
                String userName = email.substring(0, email.indexOf("@"));
                return userName;
            }
        }
        return null;
    }

    @Override
    public String getRedirectUrl(HttpServletRequest request) {
        return loginComponent.getRedirectUrl(request);
    }

    @Override
    public String getLogoutUrl() {
        return loginComponent.getLogoutUrl();
    }

    @Override
    public String getRegisterUrl(AppUser user) {
        if (user != null && user.getType() == -1) {
            return "/user/register?success=1";
        }
        return "/user/register";
    }

    @Override
    public String addLoginStatus(HttpServletRequest request, HttpServletResponse response, String userName) {
        byte[] randomBytes = new byte[32];
        SECURE_RANDOM.nextBytes(randomBytes);
        String token = TOKEN_ENCODER.encodeToString(randomBytes);
        if (!assistRedisService.setWithNoSerialize(toSessionKey(token), userName, sessionTtlSeconds)) {
            throw new IllegalStateException("failed to persist login session");
        }
        response.addHeader(HttpHeaders.SET_COOKIE, buildSessionCookie(request, token, sessionTtlSeconds));
        return token;
    }

    @Override
    public void removeLoginStatus(HttpServletRequest request, HttpServletResponse response) {
        for (String token : getRequestTokens(request)) {
            if (TOKEN_PATTERN.matcher(token).matches()) {
                assistRedisService.remove(toSessionKey(token));
            }
        }
        response.addHeader(HttpHeaders.SET_COOKIE, buildSessionCookie(request, "", 0));
    }

    private Set<String> getRequestTokens(HttpServletRequest request) {
        Set<String> tokens = new LinkedHashSet<>();
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization != null
                && authorization.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            String bearerToken = authorization.substring(BEARER_PREFIX.length()).trim();
            if (!bearerToken.isEmpty()) {
                tokens.add(bearerToken);
            }
        }
        String cookieToken = WebUtil.getLoginCookieValue(request);
        if (StringUtils.isNotBlank(cookieToken)) {
            tokens.add(cookieToken.trim());
        }
        return tokens;
    }

    private String buildSessionCookie(HttpServletRequest request, String token, int maxAge) {
        StringBuilder cookie = new StringBuilder()
                .append(WebUtil.LOGIN_USER_STATUS_NAME).append('=').append(token)
                .append("; Path=/; Max-Age=").append(maxAge)
                .append("; HttpOnly; SameSite=Lax");
        String forwardedProto = request.getHeader("X-Forwarded-Proto");
        if (request.isSecure()
                || (forwardedProto != null
                && "https".equalsIgnoreCase(forwardedProto.split(",", 2)[0].trim()))) {
            cookie.append("; Secure");
        }
        return cookie.toString();
    }

    private String toSessionKey(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.US_ASCII));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                hex.append(String.format("%02x", value));
            }
            return SESSION_KEY_PREFIX + hex;
        } catch (NoSuchAlgorithmException e) {
            LOGGER.error("SHA-256 is unavailable", e);
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
