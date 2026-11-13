package com.phh.procure.security.ratelimit;

import java.time.Duration;

/**
 * 限流计数器存储抽象：本地内存实现用于单机/断网演示，Redis 实现用于多实例。
 */
public interface RateLimitStore {

    /**
     * 尝试占用一次配额。
     *
     * @return true 表示放行，false 表示已超限
     */
    boolean tryAcquire(String key, int limit, Duration window);

    String name();
}
