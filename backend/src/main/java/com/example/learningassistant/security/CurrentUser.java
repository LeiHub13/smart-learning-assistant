package com.example.learningassistant.security;

import com.example.learningassistant.common.BizException;
import jakarta.servlet.http.HttpServletRequest;

/**
 * 从 request 中取当前登录用户（由 AuthInterceptor 注入）。
 */
public final class CurrentUser {

    private CurrentUser() {
    }

    public static AuthUser get(HttpServletRequest request) {
        Object u = request.getAttribute(AuthInterceptor.ATTR_USER);
        if (u instanceof AuthUser user) {
            return user;
        }
        throw new BizException(401, "未登录或登录已过期");
    }
}
