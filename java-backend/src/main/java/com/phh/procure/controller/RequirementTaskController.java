package com.phh.procure.controller;

import com.phh.procure.common.ApiResponse;
import com.phh.procure.entity.AgentTask;
import com.phh.procure.service.TaskService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 在需求单下创建比价任务。返回 202：任务已受理，真正的执行是异步的。
 */
@RestController
@RequestMapping("/api/requirements/{requirementId}/tasks")
@RequiredArgsConstructor
public class RequirementTaskController {

    private final TaskService taskService;

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<AgentTask> create(@PathVariable Long requirementId,
                                         @RequestParam(required = false) Boolean mockMode,
                                         @RequestParam(defaultValue = "MOCK") String quoteSource) {
        return ApiResponse.ok(taskService.create(requirementId, mockMode, quoteSource));
    }
}
