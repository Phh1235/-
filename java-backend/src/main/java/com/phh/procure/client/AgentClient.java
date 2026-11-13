package com.phh.procure.client;

import com.phh.procure.dto.AgentSubmitRequest;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;

import java.util.Map;

/**
 * 调用 Python Agent 的内部客户端。所有请求携带 X-Internal-Token，
 * Python 侧用同一环境变量校验，避免内网端口被随意调用。
 */
@FeignClient(name = "procure-agent-py", url = "${app.agent.base-url}")
public interface AgentClient {

    @PostMapping("/v1/tasks")
    AgentSubmitResponse submit(@RequestHeader("X-Internal-Token") String token,
                              @RequestBody AgentSubmitRequest request);

    @GetMapping("/v1/tasks/{taskId}")
    AgentStatusResponse status(@RequestHeader("X-Internal-Token") String token,
                              @PathVariable("taskId") String taskId);

    @DeleteMapping("/v1/tasks/{taskId}")
    void cancel(@RequestHeader("X-Internal-Token") String token,
                @PathVariable("taskId") String taskId);

    @GetMapping("/health")
    Map<String, Object> health();

    record AgentSubmitResponse(String taskId, String status) {
    }

    record AgentStatusResponse(String taskId, String status, String stage, Integer progress, String error) {
    }
}
