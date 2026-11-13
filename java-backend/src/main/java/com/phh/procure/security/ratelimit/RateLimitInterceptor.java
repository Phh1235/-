package com.phh.procure.security.ratelimit;

import com.phh.procure.common.BizException;
import com.phh.procure.common.ErrorCode;
import com.phh.procure.config.AppProperties;
import com.phh.procure.security.LoginUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.Duration;

/**
 * 注解式限流拦截器。超限抛 {@link ErrorCode#RATE_LIMITED}，由全局异常处理器转 429。
 */
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(RateLimitInterceptor.class);

    private final RateLimitStore store;
    private final AppProperties properties;

    public RateLimitInterceptor(RateLimitStore store, AppProperties properties) {
        this.store = store;
        this.properties = properties;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!properties.getRateLimit().isEnabled() || !(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }
        RateLimit rateLimit = handlerMethod.getMethodAnnotation(RateLimit.class);
        if (rateLimit == null) {
            return true;
        }
        String key = rateLimit.name() + ":" + subject(request);
        boolean allowed = store.tryAcquire(key, rateLimit.limit(), Duration.ofSeconds(rateLimit.windowSeconds()));
        if (!allowed) {
            log.warn("触发限流 key={} limit={}/{}s store={}", key, rateLimit.limit(), rateLimit.windowSeconds(),
                    store.name());
            throw new BizException(ErrorCode.RATE_LIMITED);
        }
        return true;
    }

    private String subject(HttpServletRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof LoginUser user) {
            return "u" + user.id();
        }
        return "ip" + clientIp(request);
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
