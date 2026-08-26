package com.shcj.cache.exception;

import org.slf4j.helpers.MessageFormatter;

/**
 * 业务异常类
 *
 * @author zoushunqing 2023/2/7 15:25
 * @since Dev_1.0.1
 */
public class BizException extends RuntimeException {

    private final String message;

    public BizException(String message) {
        this.message = message;
    }

    public BizException(String message, Object... data) {
        this.message = MessageFormatter.arrayFormat(message, data).getMessage();
    }

    @Override
    public String getMessage() {
        return message;
    }
}


