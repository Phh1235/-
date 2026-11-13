package com.phh.procure.config;

import com.phh.procure.security.ratelimit.MemoryRateLimitStore;
import com.phh.procure.security.ratelimit.RateLimitStore;
import com.phh.procure.security.ratelimit.RedisRateLimitStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 限流存储选择：优先 Redis（多实例一致），连不上则自动降级为本地内存，保证断网也能演示。
 */
@Configuration
public class RateLimitConfig {

    private static final Logger log = LoggerFactory.getLogger(RateLimitConfig.class);

    @Bean
    public RateLimitStore rateLimitStore(AppProperties properties,
                                         ObjectProvider<StringRedisTemplate> redisTemplates) {
        String mode = properties.getRateLimit().getMode();
        StringRedisTemplate redisTemplate = redisTemplates.getIfAvailable();
        if (!"memory".equalsIgnoreCase(mode) && redisTemplate != null) {
            RedisConnection connection = null;
            try {
                connection = redisTemplate.getConnectionFactory().getConnection();
                connection.ping();
                log.info("限流计数器使用 Redis 实现");
                return new RedisRateLimitStore(redisTemplate);
            } catch (Exception ex) {
                log.warn("Redis 不可用（{}），限流降级为本地内存实现", ex.getMessage());
            } finally {
                if (connection != null) {
                    try {
                        connection.close();
                    } catch (Exception ignored) {
                        // 关闭失败不影响降级
                    }
                }
            }
        }
        log.info("限流计数器使用本地内存实现");
        return new MemoryRateLimitStore();
    }
}
