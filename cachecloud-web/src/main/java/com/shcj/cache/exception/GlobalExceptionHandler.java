package com.shcj.cache.exception;

import com.alibaba.fastjson.JSONObject;
import com.shcj.cache.web.controller.BaseController;
import com.shcj.cache.web.vo.AjaxResult;
import com.shcj.cache.web.vo.ApiResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.ModelAndView;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * 全局异常处理器
 *
 * @author zoushunqing 2023/2/8 12:13
 * @since Dev_1.0.1
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends BaseController {

    @ExceptionHandler(Exception.class)
    public ModelAndView handleBizException(Exception exception, HttpServletRequest request, HttpServletResponse response) {
        if (isClientAbort(exception) || response.isCommitted()) {
            logger.debug("client connection closed before error response was written: {}", exception.getMessage());
            return null;
        }
        if (exception instanceof BizException) {
            logger.warn("request rejected: {}", exception.getMessage());
        } else {
            logger.error("unhandled request error", exception);
        }
        if (isApiV1Request(request)) {
            int code = exception instanceof BizException ? 400 : 500;
            sendMessage(response, JSONObject.toJSONString(ApiResponse.fail(code, exception.getMessage())));
            return null;
        }
        sendMessage(response, AjaxResult.error(exception.getMessage()).toString());
        return null;
    }

    private boolean isApiV1Request(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return uri != null && uri.contains("/api/v1");
    }

    private boolean isClientAbort(Throwable throwable) {
        Throwable cause = throwable;
        while (cause != null) {
            if ("org.apache.catalina.connector.ClientAbortException".equals(cause.getClass().getName())) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

}
