package com.phh.procure.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 下发给 Python Agent 的任务载荷。只传业务字段，不传任何密钥——
 * Python 侧的回调签名密钥通过环境变量注入，避免随请求扩散。
 */
public record AgentSubmitRequest(
        Long taskId,
        boolean mockMode,
        String callbackUrl,
        RequirementPayload requirement
) {

    public record RequirementPayload(
            Long id,
            String title,
            String category,
            String spec,
            BigDecimal quantity,
            String unit,
            BigDecimal budgetAmount,
            String currency,
            String deliveryCity,
            LocalDate deadline,
            String description
    ) {
    }
}
