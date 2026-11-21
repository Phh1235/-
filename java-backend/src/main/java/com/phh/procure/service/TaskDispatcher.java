package com.phh.procure.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.phh.procure.client.AgentClient;
import com.phh.procure.config.AppProperties;
import com.phh.procure.dto.AgentSubmitRequest;
import com.phh.procure.entity.AgentTask;
import com.phh.procure.entity.PurchaseRequirement;
import com.phh.procure.entity.Supplier;
import com.phh.procure.entity.SupplierQuote;
import com.phh.procure.enums.QuoteSource;
import com.phh.procure.enums.TaskStage;
import com.phh.procure.enums.TaskStatus;
import com.phh.procure.mapper.AgentTaskMapper;
import com.phh.procure.mapper.PurchaseRequirementMapper;
import com.phh.procure.mapper.SupplierMapper;
import com.phh.procure.mapper.SupplierQuoteMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 异步下发器：把"调用 Python"从 HTTP 请求线程里摘出去。
 * 单独成 Bean 是为了让 @Async 真正生效（同类内部调用不走代理）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskDispatcher {

    private static final int MAX_ERROR_LENGTH = 480;

    private final AgentTaskMapper taskMapper;
    private final PurchaseRequirementMapper requirementMapper;
    private final SupplierMapper supplierMapper;
    private final SupplierQuoteMapper supplierQuoteMapper;
    private final AgentClient agentClient;
    private final AppProperties props;
    private final TaskEventService eventService;

    @Async("agentExecutor")
    public void dispatch(Long taskId) {
        AgentTask task = taskMapper.selectById(taskId);
        if (task == null) {
            log.warn("下发时任务不存在（可能事务未提交或已被删除）taskId={}", taskId);
            return;
        }
        if (!TaskStatus.QUEUED.name().equals(task.getStatus())) {
            log.info("任务状态为 {}，跳过下发 taskId={}", task.getStatus(), taskId);
            return;
        }
        PurchaseRequirement requirement = requirementMapper.selectById(task.getRequirementId());
        if (requirement == null) {
            fail(taskId, "需求单已被删除，任务取消下发");
            return;
        }

        task.setStatus(TaskStatus.RUNNING.name());
        task.setStage(TaskStage.PARSE.name());
        task.setStartedAt(LocalDateTime.now());
        task.setLastHeartbeatAt(LocalDateTime.now());
        taskMapper.updateById(task);
        eventService.record(taskId, TaskStage.PARSE.name(), TaskStatus.RUNNING.name(), 0, "已提交至 Agent 解析需求");

        String quoteSource = QuoteSource.normalize(task.getQuoteSource()).name();
        List<AgentSubmitRequest.CandidateSupplier> candidates = candidates(requirement.getCategory(), quoteSource);
        if (candidates.isEmpty() && QuoteSource.IMPORT.name().equals(quoteSource)) {
            fail(taskId, "品类「" + requirement.getCategory() + "」还没有导入报价单，请先到「报价单导入」上传，或改用 MOCK 模式");
            return;
        }

        AgentSubmitRequest request = new AgentSubmitRequest(
                task.getId(),
                Boolean.TRUE.equals(task.getMockMode()),
                quoteSource,
                props.getAgent().getCallbackUrl(),
                new AgentSubmitRequest.RequirementPayload(
                        requirement.getId(), requirement.getTitle(), requirement.getCategory(),
                        requirement.getSpec(), requirement.getQuantity(), requirement.getUnit(),
                        requirement.getBudgetAmount(), requirement.getCurrency(),
                        requirement.getDeliveryCity(), requirement.getDeadline(), requirement.getDescription()),
                candidates);

        try {
            AgentClient.AgentSubmitResponse response =
                    agentClient.submit(props.getAgent().getInternalToken(), request);
            // 只做定点更新并排除 CANCELED：整实体回写会把用户在等待期间点下的"取消"覆盖掉
            taskMapper.update(null, Wrappers.<AgentTask>lambdaUpdate()
                    .eq(AgentTask::getId, taskId)
                    .ne(AgentTask::getStatus, TaskStatus.CANCELED.name())
                    .set(AgentTask::getExternalTaskId, response == null ? null : response.taskId())
                    .set(AgentTask::getLastHeartbeatAt, LocalDateTime.now()));
        } catch (Exception e) {
            log.warn("下发 Agent 失败 taskId={}", taskId, e);
            fail(taskId, "调用 Python Agent 失败：" + rootMessage(e));
        }
    }

    /**
     * 候选人名单由 Java 主数据决定：同品类优先，没有同品类就退回评分最高的 20 家。
     * 黑名单供应商仍然下发（Python 负责标记风险），由打分公式扣分而不是直接隐藏——
     * 否则"为什么这家没出现"无法解释。
     */
    private List<AgentSubmitRequest.CandidateSupplier> candidates(String category, String quoteSource) {
        if (QuoteSource.IMPORT.name().equals(quoteSource)) {
            return importedCandidates(category);
        }
        List<Supplier> matched = supplierMapper.selectList(Wrappers.<Supplier>lambdaQuery()
                .eq(category != null && !category.isBlank(), Supplier::getCategory, category)
                .orderByDesc(Supplier::getRating));
        if (matched.isEmpty()) {
            matched = supplierMapper.selectList(Wrappers.<Supplier>lambdaQuery()
                    .orderByDesc(Supplier::getRating)
                    .last("LIMIT 20"));
        }
        return matched.stream().map(s -> toCandidate(s, null)).toList();
    }

    /**
     * IMPORT 模式：候选名单就是"这个品类下报过价的供应商"，
     * 每个供应商只带最新且未过期的一条报价，历史报价留在库里做趋势与追溯。
     */
    private List<AgentSubmitRequest.CandidateSupplier> importedCandidates(String category) {
        if (category == null || category.isBlank()) {
            return List.of();
        }
        LocalDate today = LocalDate.now();
        List<SupplierQuote> quotes = supplierQuoteMapper.selectList(Wrappers.<SupplierQuote>lambdaQuery()
                .eq(SupplierQuote::getCategory, category)
                .orderByDesc(SupplierQuote::getQuoteDate)
                .orderByDesc(SupplierQuote::getId));
        Map<Long, SupplierQuote> latest = new LinkedHashMap<>();
        for (SupplierQuote q : quotes) {
            if (q.getValidUntil() != null && q.getValidUntil().isBefore(today)) {
                continue;
            }
            latest.putIfAbsent(q.getSupplierId(), q);
        }
        if (latest.isEmpty()) {
            return List.of();
        }
        Map<Long, Supplier> suppliers = supplierMapper.selectBatchIds(latest.keySet()).stream()
                .collect(Collectors.toMap(Supplier::getId, s -> s));
        List<AgentSubmitRequest.CandidateSupplier> result = new ArrayList<>();
        latest.forEach((supplierId, quote) -> {
            Supplier supplier = suppliers.get(supplierId);
            if (supplier == null) {
                return;
            }
            AgentSubmitRequest.LatestQuote seed = new AgentSubmitRequest.LatestQuote(
                    quote.getUnitPrice(), quote.getCurrency(), quote.getUnit(), quote.getMoq(),
                    quote.getLeadTimeDays(), quote.getTaxIncluded(), quote.getQuoteDate(),
                    quote.getValidUntil(), quote.getSourceRef());
            result.add(toCandidate(supplier, seed));
        });
        return result;
    }

    private AgentSubmitRequest.CandidateSupplier toCandidate(Supplier s, AgentSubmitRequest.LatestQuote quote) {
        return new AgentSubmitRequest.CandidateSupplier(
                s.getId(), s.getCode(), s.getName(), s.getWebsite(),
                s.getCategory(), s.getRating(), Boolean.TRUE.equals(s.getBlacklisted()), quote);
    }

    public void fail(Long taskId, String message) {
        AgentTask task = taskMapper.selectById(taskId);
        if (task == null || TaskStatus.SUCCEEDED.name().equals(task.getStatus())
                || TaskStatus.CANCELED.name().equals(task.getStatus())) {
            return;
        }
        TaskStatus current = TaskStatus.valueOf(task.getStatus());
        if (current != TaskStatus.FAILED) {
            TaskStateMachine.assertCanTransit(task.getStatus(), TaskStatus.FAILED.name());
        }
        String trimmed = message == null ? "未知错误" : message.substring(0, Math.min(message.length(), MAX_ERROR_LENGTH));
        task.setStatus(TaskStatus.FAILED.name());
        task.setErrorMessage(trimmed);
        task.setFinishedAt(LocalDateTime.now());
        taskMapper.updateById(task);
        eventService.record(taskId, task.getStage(), TaskStatus.FAILED.name(),
                task.getProgress() == null ? 0 : task.getProgress(), trimmed);
    }

    private String rootMessage(Throwable e) {
        Throwable cursor = e;
        while (cursor.getCause() != null && cursor.getCause() != cursor) {
            cursor = cursor.getCause();
        }
        String msg = cursor.getClass().getSimpleName() + ": " + cursor.getMessage();
        return msg.length() > 300 ? msg.substring(0, 300) : msg;
    }
}
