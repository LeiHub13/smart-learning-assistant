package com.example.learningassistant.common.context;

/**
 * 租户上下文：请求级 ThreadLocal，由安全拦截器填充、请求结束清理。
 * 多租户数据隔离（tenant_id 行级过滤）依赖该上下文。
 */
public final class TenantContext {

    private static final ThreadLocal<Long> TENANT = new ThreadLocal<>();

    private TenantContext() {
    }

    public static void set(Long tenantId) {
        TENANT.set(tenantId);
    }

    public static Long get() {
        return TENANT.get();
    }

    public static void clear() {
        TENANT.remove();
    }
}
