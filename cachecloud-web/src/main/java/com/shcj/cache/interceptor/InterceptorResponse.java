package com.shcj.cache.interceptor;

import com.shcj.cache.web.vo.AjaxResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;

/**
 * 前后端分离后拦截器不再跳转 JSP 登录页，统一以 JSON + HTTP 状态码回应。
 */
final class InterceptorResponse {

    private static final Logger LOGGER = LoggerFactory.getLogger(InterceptorResponse.class);

    private InterceptorResponse() {
    }

    /**
     * 未登录/登录态失效。
     */
    static void unauthorized(HttpServletResponse response, String message) {
        write(response, HttpServletResponse.SC_UNAUTHORIZED, message);
    }

    /**
     * 已登录但无权限。
     */
    static void forbidden(HttpServletResponse response, String message) {
        write(response, HttpServletResponse.SC_FORBIDDEN, message);
    }

    private static void write(HttpServletResponse response, int status, String message) {
        if (response.isCommitted()) {
            return;
        }
        // 只清空响应体缓冲，保留 Spring CORS 拦截器已写入的 Access-Control-* 头，
        // 否则跨域场景下浏览器读不到这里的 401/403，会误报成跨域错误
        response.resetBuffer();
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        try {
            PrintWriter writer = response.getWriter();
            writer.write(AjaxResult.error(message).toString());
            writer.flush();
        } catch (IOException e) {
            LOGGER.error("write interceptor response failed: {}", e.getMessage(), e);
        }
    }
}
