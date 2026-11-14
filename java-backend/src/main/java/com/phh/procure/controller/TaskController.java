package com.phh.procure.controller;

import com.phh.procure.common.ApiResponse;
import com.phh.procure.dto.PageResponse;
import com.phh.procure.entity.AgentTask;
import com.phh.procure.entity.TaskStageEvent;
import com.phh.procure.security.SecurityUtils;
import com.phh.procure.service.SseService;
import com.phh.procure.service.StreamTicketService;
import com.phh.procure.service.TaskEventService;
import com.phh.procure.service.TaskService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/tasks")
@RequiredArgsConstructor
public class TaskController {

    private final TaskService taskService;
    private final TaskEventService eventService;
    private final SseService sseService;
    private final StreamTicketService ticketService;

    @GetMapping
    public ApiResponse<PageResponse<AgentTask>> list(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") @Min(1) long page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(100) long size) {
        return ApiResponse.ok(taskService.page(status, page, size));
    }

    @GetMapping("/{id}")
    public ApiResponse<AgentTask> detail(@PathVariable Long id) {
        return ApiResponse.ok(taskService.get(id));
    }

    /** 阶段事件回放：SSE 断线重连后靠这个补齐进度，避免"进度条卡在 0%"。 */
    @GetMapping("/{id}/events")
    public ApiResponse<List<TaskStageEvent>> events(@PathVariable Long id) {
        return ApiResponse.ok(taskService.events(id));
    }

    @PostMapping("/{id}/cancel")
    public ApiResponse<AgentTask> cancel(@PathVariable Long id) {
        return ApiResponse.ok(taskService.cancel(id));
    }

    @PostMapping("/{id}/retry")
    public ApiResponse<AgentTask> retry(@PathVariable Long id) {
        return ApiResponse.ok(taskService.retry(id));
    }

    /**
     * 用 JWT 换一次性 SSE 票据。EventSource 不能带 Header，直接拼 JWT 会泄露到日志与 Referer。
     */
    @PostMapping("/{id}/stream-ticket")
    public ApiResponse<Map<String, Object>> streamTicket(@PathVariable Long id) {
        taskService.get(id);
        String ticket = ticketService.issue(id, SecurityUtils.currentUserId());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ticket", ticket);
        data.put("expiresInSeconds", 60);
        return ApiResponse.ok(data);
    }

    @GetMapping(value = "/{id}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable Long id, @RequestParam(required = false) String ticket) {
        ticketService.consume(ticket, id);
        SseEmitter emitter = sseService.subscribe(id);

        // 重连后先回放历史阶段，再接收实时推送
        for (TaskStageEvent event : eventService.list(id)) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("taskId", event.getTaskId());
            data.put("stage", event.getStage());
            data.put("status", event.getEventStatus());
            data.put("message", event.getMessage());
            data.put("seq", event.getSeq());
            data.put("at", event.getCreatedAt());
            try {
                emitter.send(SseEmitter.event().name("stage").data(data));
            } catch (IOException | IllegalStateException e) {
                log.debug("SSE 回放中断 taskId={}", id);
                break;
            }
        }
        return emitter;
    }
}
