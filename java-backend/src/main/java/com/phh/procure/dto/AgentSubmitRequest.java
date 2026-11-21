package com.phh.procure.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 下发给 Python Agent 的任务载荷。只传业务字段，不传任何密钥——
 * Python 侧的回调签名密钥通过环境变量注入，避免随请求扩散。
 * <p>候选人一并下发：供应商主数据归 Java 管，Python 只按名单去公开页面取证，
 * 这样"供应商 ID 由谁产生"不会两边各写一套。</p>
 */
public record AgentSubmitRequest(
        Long taskId,
        boolean mockMode,
        /** MOCK / IMPORT / FETCH：报价从哪来，决定 Python 走模拟、用导入的报价单还是真去抓页面 */
        String quoteSource,
        String callbackUrl,
        RequirementPayload requirement,
        List<CandidateSupplier> candidates
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

    public record CandidateSupplier(
            Long id,
            String code,
            String name,
            String website,
            String category,
            BigDecimal rating,
            boolean blacklisted,
            /** 该供应商在该品类下"最新且未过期"的一条导入报价；没有则为 null */
            LatestQuote latestQuote
    ) {
    }

    /**
     * 导入的报价单行。Python 拿到它就不要再猜价格了——这是供应商自己报的，
     * 比任何抓取/模型推断都可信，因此 verified=true。
     */
    public record LatestQuote(
            BigDecimal unitPrice,
            String currency,
            String unit,
            BigDecimal moq,
            Integer leadTimeDays,
            Boolean taxIncluded,
            LocalDate quoteDate,
            LocalDate validUntil,
            String sourceRef
    ) {
    }
}
