package com.phh.procure.controller;

import com.phh.procure.common.ApiResponse;
import com.phh.procure.dto.PageResponse;
import com.phh.procure.entity.AgentTask;
import com.phh.procure.entity.TaskStageEvent;
import com.phh.procure.service.TaskService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/tasks")
@RequiredArgsConstructor
public class TaskController {

    private final TaskService taskService;

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
}
