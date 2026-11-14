package com.phh.procure.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.phh.procure.client.CallbackSigner;
import com.phh.procure.common.ApiResponse;
import com.phh.procure.dto.AgentCallbackRequest;
import com.phh.procure.service.AgentCallbackService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 内部回调入口，不走 JWT（Python 没有用户身份），改用 HMAC 签名 + 时间戳窗口 + nonce 防重放。
 * 该路径只应在内网暴露；生产环境建议再加一层网关 IP 白名单。
 */
@RestController
@RequestMapping("/internal/agent")
@RequiredArgsConstructor
public class InternalCallbackController {

    private final CallbackSigner callbackSigner;
    private final AgentCallbackService callbackService;
    private final ObjectMapper objectMapper;

    @PostMapping("/callback")
    public ApiResponse<Void> callback(
            @RequestBody String rawBody,
            @RequestHeader(value = "X-Agent-Timestamp", required = false) String timestamp,
            @RequestHeader(value = "X-Agent-Nonce", required = false) String nonce,
            @RequestHeader(value = "X-Agent-Signature", required = false) String signature) throws Exception {
        callbackSigner.verify(rawBody, timestamp, nonce, signature);
        AgentCallbackRequest request = objectMapper.readValue(rawBody, AgentCallbackRequest.class);
        callbackService.handle(request, nonce, rawBody);
        return ApiResponse.ok();
    }
}
