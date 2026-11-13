package com.phh.procure.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 应用配置。所有密钥类配置均支持环境变量覆盖，默认值只用于本地演示。
 */
@Data
@Component
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    private Jwt jwt = new Jwt();
    private Agent agent = new Agent();
    private RateLimit rateLimit = new RateLimit();
    private Cors cors = new Cors();

    @Data
    public static class Jwt {
        private String secret = "procure-agent-demo-secret-key-change-me-2026-please";
        private long expireSeconds = 7200;
    }

    @Data
    public static class Agent {
        private String baseUrl = "http://127.0.0.1:8000";
        private String internalToken = "internal-token-demo";
        private String callbackSecret = "callback-secret-demo";
        private String callbackUrl = "http://127.0.0.1:8080/internal/agent/callback";
        private boolean mockMode = true;
        private int connectTimeoutMs = 2000;
        private int readTimeoutMs = 60000;
    }

    @Data
    public static class RateLimit {
        private boolean enabled = true;
        /** auto：能连上 Redis 就用 Redis，否则退化到本地内存 */
        private String mode = "auto";
    }

    @Data
    public static class Cors {
        private List<String> allowedOrigins = List.of("http://localhost:8080");
    }
}
