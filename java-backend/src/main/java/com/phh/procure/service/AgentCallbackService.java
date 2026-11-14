package com.phh.procure.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.phh.procure.common.BizException;
import com.phh.procure.common.ErrorCode;
import com.phh.procure.dto.AgentCallbackRequest;
import com.phh.procure.entity.AgentCallbackLog;
import com.phh.procure.entity.AgentTask;
import com.phh.procure.entity.Quote;
import com.phh.procure.entity.RiskItem;
import com.phh.procure.entity.TaskResult;
import com.phh.procure.enums.TaskStage;
import com.phh.procure.enums.TaskStatus;
import com.phh.procure.mapper.AgentCallbackLogMapper;
import com.phh.procure.mapper.AgentTaskMapper;
import com.phh.procure.mapper.QuoteMapper;
import com.phh.procure.mapper.RiskItemMapper;
import com.phh.procure.mapper.TaskResultMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;

/**
 * 回调落库。三件事必须做对：
 * <ol>
 *   <li>幂等：eventId 唯一，重复投递直接返回成功（否则 Python 重试会把结果写两遍）；</li>
 *   <li>防重放：nonce 只能用一次，配合时间戳窗口；</li>
 *   <li>状态机兜底：已取消的任务收到"成功"回调时不能把状态改回去。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentCallbackService {

    private final AgentCallbackLogMapper callbackLogMapper;
    private final AgentTaskMapper taskMapper;
    private final QuoteMapper quoteMapper;
    private final RiskItemMapper riskItemMapper;
    private final TaskResultMapper taskResultMapper;
    private final TaskEventService eventService;
    private final TaskDispatcher dispatcher;

    @Transactional
    public void handle(AgentCallbackRequest request, String nonce, String rawBody) {
        if (request.eventId() == null || request.eventId().isBlank()) {
            throw BizException.badRequest("eventId 不能为空");
        }
        if (request.taskId() == null) {
            throw BizException.badRequest("taskId 不能为空");
        }
        AgentTask task = taskMapper.selectById(request.taskId());
        if (task == null) {
            throw BizException.notFound("任务");
        }

        if (callbackLogMapper.selectCount(Wrappers.<AgentCallbackLog>lambdaQuery()
                .eq(AgentCallbackLog::getEventId, request.eventId())) > 0) {
            log.info("重复回调，已忽略 eventId={}", request.eventId());
            return;
        }
        if (callbackLogMapper.selectCount(Wrappers.<AgentCallbackLog>lambdaQuery()
                .eq(AgentCallbackLog::getNonce, nonce)) > 0) {
            throw new BizException(ErrorCode.AGENT_CALLBACK_INVALID, "nonce 已被使用（疑似重放）");
        }

        AgentCallbackLog record = new AgentCallbackLog();
        record.setEventId(request.eventId());
        record.setNonce(nonce);
        record.setTaskId(request.taskId());
        record.setEventType(request.eventType());
        record.setBodyHash(sha256(rawBody));
        record.setReceivedAt(LocalDateTime.now());
        try {
            callbackLogMapper.insert(record);
        } catch (DuplicateKeyException e) {
            log.info("并发重复回调，已忽略 eventId={}", request.eventId());
            return;
        }

        String type = request.eventType() == null ? "" : request.eventType().trim().toUpperCase();
        switch (type) {
            case "STAGE" -> applyStage(task, request);
            case "SUCCEEDED" -> complete(task, request);
            case "FAILED" -> dispatcher.fail(task.getId(),
                    request.message() == null ? "Agent 执行失败" : request.message());
            default -> throw BizException.badRequest("未知回调类型：" + request.eventType());
        }
    }

    private void applyStage(AgentTask task, AgentCallbackRequest request) {
        if (TaskStatus.valueOf(task.getStatus()).terminal()) {
            log.info("任务已结束，忽略阶段回调 taskId={} status={}", task.getId(), task.getStatus());
            return;
        }
        TaskStateMachine.assertStageAdvance(task.getStage(), request.stage());
        TaskStage stage = TaskStage.parse(request.stage());
        int progress = request.progress() != null ? request.progress() : stage.progress();
        task.setStage(stage.name());
        task.setProgress(progress);
        task.setLastHeartbeatAt(LocalDateTime.now());
        taskMapper.updateById(task);
        eventService.record(task.getId(), stage.name(), task.getStatus(), progress,
                request.message() == null ? "阶段推进：" + stage.name() : request.message());
    }

    private void complete(AgentTask task, AgentCallbackRequest request) {
        if (TaskStatus.CANCELED.name().equals(task.getStatus())) {
            log.warn("任务已取消，丢弃成功回调 taskId={}", task.getId());
            return;
        }
        TaskStateMachine.assertCanTransit(task.getStatus(), TaskStatus.SUCCEEDED.name());

        AgentCallbackRequest.Payload payload = request.payload() == null
                ? new AgentCallbackRequest.Payload(List.of(), List.of(), null)
                : request.payload();

        List<AgentCallbackRequest.QuoteItem> quotes = payload.quotes() == null ? List.of() : payload.quotes();
        for (AgentCallbackRequest.QuoteItem item : quotes) {
            Quote quote = new Quote();
            quote.setTaskId(task.getId());
            quote.setSupplierId(item.supplierId());
            quote.setSupplierName(item.supplierName());
            quote.setUnitPrice(item.unitPrice());
            quote.setCurrency(item.currency() == null ? "CNY" : item.currency());
            quote.setUnit(item.unit());
            quote.setMoq(item.moq());
            quote.setLeadTimeDays(item.leadTimeDays());
            quote.setTaxIncluded(Boolean.TRUE.equals(item.taxIncluded()));
            quote.setSourceUrl(item.sourceUrl() == null ? "" : item.sourceUrl());
            quote.setFetchedAt(item.fetchedAt() == null ? LocalDateTime.now() : item.fetchedAt());
            quote.setConfidence(item.confidence());
            quote.setVerified(Boolean.TRUE.equals(item.verified()));
            // 没有来源 URL 或未经验证的报价一律标"待人工核实"，这是可信度的底线
            quote.setNeedsReview(!Boolean.TRUE.equals(item.verified())
                    || item.sourceUrl() == null || item.sourceUrl().isBlank()
                    || Boolean.TRUE.equals(item.needsReview()));
            quote.setRawSnippet(item.rawSnippet());
            quoteMapper.insert(quote);
        }

        List<AgentCallbackRequest.RiskItem> risks = payload.risks() == null ? List.of() : payload.risks();
        for (AgentCallbackRequest.RiskItem item : risks) {
            RiskItem risk = new RiskItem();
            risk.setTaskId(task.getId());
            risk.setSupplierName(item.supplierName());
            risk.setType(item.type());
            risk.setLevel(item.level());
            risk.setTitle(item.title());
            risk.setDetail(item.detail());
            risk.setSourceUrl(item.sourceUrl() == null ? "" : item.sourceUrl());
            risk.setNeedsReview(item.needsReview() == null || item.needsReview()
                    || item.sourceUrl() == null || item.sourceUrl().isBlank());
            riskItemMapper.insert(risk);
        }

        AgentCallbackRequest.Result result = payload.result();
        TaskResult taskResult = new TaskResult();
        taskResult.setTaskId(task.getId());
        if (result != null) {
            taskResult.setRecommendedSupplier(result.recommendedSupplier());
            taskResult.setRecommendation(result.recommendation());
            taskResult.setSummaryJson(result.summaryJson());
            taskResult.setReportMd(result.reportMd());
            taskResult.setTotalCandidates(result.totalCandidates() == null ? quotes.size() : result.totalCandidates());
        } else {
            taskResult.setTotalCandidates(quotes.size());
        }
        taskResultMapper.insert(taskResult);

        task.setStatus(TaskStatus.SUCCEEDED.name());
        task.setStage(TaskStage.DONE.name());
        task.setProgress(100);
        task.setFinishedAt(LocalDateTime.now());
        task.setLastHeartbeatAt(LocalDateTime.now());
        taskMapper.updateById(task);
        eventService.record(task.getId(), TaskStage.DONE.name(), TaskStatus.SUCCEEDED.name(), 100,
                "比价完成，共 " + quotes.size() + " 条报价");
    }

    private String sha256(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest((raw == null ? "" : raw).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
