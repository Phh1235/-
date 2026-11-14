package com.phh.procure.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Python Agent 回调载荷。eventType：STAGE | SUCCEEDED | FAILED。
 * 注意：报价只能来自抓取抽取结果，payload 里没有"由模型生成的总结价格"字段。
 */
public record AgentCallbackRequest(
        String eventId,
        Long taskId,
        String eventType,
        String stage,
        Integer progress,
        String message,
        Payload payload
) {

    public record Payload(
            List<QuoteItem> quotes,
            List<RiskItem> risks,
            Result result
    ) {
    }

    public record QuoteItem(
            Long supplierId,
            String supplierName,
            BigDecimal unitPrice,
            String currency,
            String unit,
            BigDecimal moq,
            Integer leadTimeDays,
            Boolean taxIncluded,
            String sourceUrl,
            LocalDateTime fetchedAt,
            BigDecimal confidence,
            Boolean verified,
            Boolean needsReview,
            String rawSnippet
    ) {
    }

    public record RiskItem(
            String supplierName,
            String type,
            String level,
            String title,
            String detail,
            String sourceUrl,
            Boolean needsReview
    ) {
    }

    public record Result(
            String recommendedSupplier,
            String recommendation,
            Integer totalCandidates,
            String summaryJson,
            String reportMd
    ) {
    }
}
