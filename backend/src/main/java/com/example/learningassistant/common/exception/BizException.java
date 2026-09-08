package com.example.learningassistant.common;

import lombok.Getter;

/**
 * 业务异常：code 为 HTTP 语义扩展码，message 为面向用户的提示。
 */
@Getter
public class BizException extends RuntimeException {

    private final int code;

    public BizException(String message) {
        this(400, message);
    }

    public BizException(int code, String message) {
        super(message);
        this.code = code;
    }
}
