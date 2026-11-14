package com.phh.procure.service;

import com.phh.procure.common.BizException;
import com.phh.procure.common.ErrorCode;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SSE 一次性票据。
 * 背景：浏览器 EventSource 无法自定义 Header，不能带 Authorization；
 * 若把 JWT 拼到 URL 上，会被浏览器历史、代理日志、Referer 泄露。
 * 折中做法：先用 JWT 换一个 60 秒、一次性、绑定 taskId + userId 的短期票据，再用票据建流。
 * 若有 Redis，可把 Map 换成 SETEX，实现多实例共享。
 */
@Service
public class StreamTicketService {

    private static final long TTL_SECONDS = 60;

    private final Map<String, Ticket> tickets = new ConcurrentHashMap<>();

    public String issue(Long taskId, Long userId) {
        purgeExpired();
        String ticket = UUID.randomUUID().toString().replace("-", "");
        tickets.put(ticket, new Ticket(taskId, userId, Instant.now().plusSeconds(TTL_SECONDS)));
        return ticket;
    }

    public void consume(String ticket, Long taskId) {
        if (ticket == null || ticket.isBlank()) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "缺少流票据");
        }
        Ticket found = tickets.remove(ticket);
        if (found == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "流票据无效或已使用");
        }
        if (found.expiresAt().isBefore(Instant.now())) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "流票据已过期");
        }
        if (!found.taskId().equals(taskId)) {
            throw new BizException(ErrorCode.FORBIDDEN, "流票据与任务不匹配");
        }
    }

    private void purgeExpired() {
        Instant now = Instant.now();
        tickets.entrySet().removeIf(e -> e.getValue().expiresAt().isBefore(now));
    }

    private record Ticket(Long taskId, Long userId, Instant expiresAt) {
    }
}
