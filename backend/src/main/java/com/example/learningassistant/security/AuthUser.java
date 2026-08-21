package com.example.learningassistant.security;

/**
 * 当前登录用户信息（由 JWT 解析而来，放入 request attribute 与 TenantContext）。
 */
public record AuthUser(Long id, String username, String role, String nickname, Long tenantId) {
}
