package com.phh.procure.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * SSE 连接注册表。按 taskId 保存多个订阅者（同一任务可以多人同时看进度）。
 * 断线由浏览器 EventSource 自动重连，重连后通过 /events 接口补齐历史阶段。
 */
@Slf4j
@Service
public class SseService {

    private static final long TIMEOUT_MS = 10 * 60 * 1000L;

    private final Map<Long, List<SseEmitter>> emitters = new ConcurrentHashMap<>();

    public SseEmitter subscribe(Long taskId) {
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        emitters.computeIfAbsent(taskId, key -> new CopyOnWriteArrayList<>()).add(emitter);
        emitter.onCompletion(() -> remove(taskId, emitter));
        emitter.onTimeout(() -> {
            remove(taskId, emitter);
            emitter.complete();
        });
        emitter.onError(e -> remove(taskId, emitter));
        return emitter;
    }

    public void publish(Long taskId, String eventName, Object data) {
        List<SseEmitter> list = emitters.get(taskId);
        if (list == null || list.isEmpty()) {
            return;
        }
        for (SseEmitter emitter : list) {
            try {
                emitter.send(SseEmitter.event().name(eventName).data(data));
            } catch (IOException | IllegalStateException e) {
                log.debug("SSE 推送失败，移除连接 taskId={}", taskId);
                remove(taskId, emitter);
            }
        }
    }

    public int subscriberCount(Long taskId) {
        List<SseEmitter> list = emitters.get(taskId);
        return list == null ? 0 : list.size();
    }

    private void remove(Long taskId, SseEmitter emitter) {
        List<SseEmitter> list = emitters.get(taskId);
        if (list != null) {
            list.remove(emitter);
            if (list.isEmpty()) {
                emitters.remove(taskId);
            }
        }
    }
}
