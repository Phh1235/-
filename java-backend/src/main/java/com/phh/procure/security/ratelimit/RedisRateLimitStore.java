package com.phh.procure.security.ratelimit;

import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

/**
 * Redis 固定窗口计数器：INCR + 首次设置过期时间，天然支持多实例共享。
 */
public class RedisRateLimitStore implements RateLimitStore {

    private final StringRedisTemplate redisTemplate;

    public RedisRateLimitStore(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public boolean tryAcquire(String key, int limit, Duration window) {
        long bucket = System.currentTimeMillis() / window.toMillis();
        String redisKey = "rl:" + key + ":" + bucket;
        Long count = redisTemplate.opsForValue().increment(redisKey);
        if (count != null && count == 1L) {
            redisTemplate.expire(redisKey, window.plusSeconds(5));
        }
        return count != null && count <= limit;
    }

    @Override
    public String name() {
        return "redis";
    }
}
