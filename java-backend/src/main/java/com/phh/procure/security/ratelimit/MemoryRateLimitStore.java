package com.phh.procure.security.ratelimit;

import org.springframework.scheduling.annotation.Scheduled;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 本地固定窗口计数器。用 ConcurrentHashMap#compute 保证同一 key 的原子性，
 * 不用 synchronized 锁全表（面试常问的对比点）。
 */
public class MemoryRateLimitStore implements RateLimitStore {

    private record Window(long resetAtMillis, int count) {
    }

    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    @Override
    public boolean tryAcquire(String key, int limit, Duration window) {
        long now = System.currentTimeMillis();
        Window updated = windows.compute(key, (k, current) -> {
            if (current == null || current.resetAtMillis() <= now) {
                return new Window(now + window.toMillis(), 1);
            }
            return new Window(current.resetAtMillis(), current.count() + 1);
        });
        return updated.count() <= limit;
    }

    @Override
    public String name() {
        return "memory";
    }

    @Scheduled(fixedDelay = 300_000L)
    public void evictExpired() {
        long now = System.currentTimeMillis();
        windows.entrySet().removeIf(entry -> entry.getValue().resetAtMillis() <= now);
    }

    /** 仅用于测试观察内部状态 */
    public int size() {
        return windows.size();
    }
}
