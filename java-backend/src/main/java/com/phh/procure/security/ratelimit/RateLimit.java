package com.phh.procure.security.ratelimit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 方法级限流。key 由 name + 登录用户（未登录用 IP）组成。
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface RateLimit {

    /** 限流分组名，例如 login / task-create */
    String name();

    /** 窗口内允许的最大请求数 */
    int limit() default 30;

    /** 窗口长度（秒） */
    int windowSeconds() default 60;
}
