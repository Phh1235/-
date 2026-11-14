package com.phh.procure.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.phh.procure.client.AgentClient;
import com.phh.procure.common.BizException;
import com.phh.procure.config.AppProperties;
import com.phh.procure.dto.PageResponse;
import com.phh.procure.entity.AgentTask;
import com.phh.procure.entity.PurchaseRequirement;
import com.phh.procure.entity.TaskStageEvent;
import com.phh.procure.enums.TaskStage;
import com.phh.procure.enums.TaskStatus;
import com.phh.procure.mapper.AgentTaskMapper;
import com.phh.procure.security.LoginUser;
import com.phh.procure.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class TaskService {

    private final AgentTaskMapper taskMapper;
    private final RequirementService requirementService;
    private final TaskDispatcher dispatcher;
    private final TaskEventService eventService;
    private final AgentClient agentClient;
    private final AppProperties props;

    @Transactional
    public AgentTask create(Long requirementId, Boolean mockMode) {
        PurchaseRequirement requirement = requirementService.get(requirementId);
        Long active = taskMapper.selectCount(Wrappers.<AgentTask>lambdaQuery()
                .eq(AgentTask::getRequirementId, requirementId)
                .in(AgentTask::getStatus, TaskStatus.QUEUED.name(), TaskStatus.RUNNING.name()));
        if (active != null && active > 0) {
            throw BizException.conflict("该需求单已有进行中的比价任务，请等待完成或先取消");
        }

        AgentTask task = new AgentTask();
        task.setRequirementId(requirement.getId());
        task.setStatus(TaskStatus.QUEUED.name());
        task.setStage(TaskStage.PARSE.name());
        task.setProgress(0);
        task.setAttempt(1);
        task.setMockMode(mockMode != null ? mockMode : props.getAgent().isMockMode());
        task.setCreatedBy(SecurityUtils.currentUserId());
        taskMapper.insert(task);

        eventService.record(task.getId(), TaskStage.PARSE.name(), TaskStatus.QUEUED.name(), 0, "任务已创建，等待调度");
        dispatchAfterCommit(task.getId());
        return task;
    }

    /**
     * 必须等事务提交后再异步下发：@Async 线程用的是另一个数据库连接，
     * 在事务未提交时 selectById 读不到刚插入的任务，会静默 return（任务永远卡在 QUEUED）。
     */
    private void dispatchAfterCommit(Long taskId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    dispatcher.dispatch(taskId);
                }
            });
        } else {
            dispatcher.dispatch(taskId);
        }
    }

    public PageResponse<AgentTask> page(String status, long page, long size) {
        LoginUser user = SecurityUtils.currentUser();
        LambdaQueryWrapper<AgentTask> qw = Wrappers.lambdaQuery();
        if (!"ADMIN".equals(user.role())) {
            qw.eq(AgentTask::getCreatedBy, user.id());
        }
        if (StringUtils.hasText(status)) {
            qw.eq(AgentTask::getStatus, TaskStateMachine.status(status).name());
        }
        qw.orderByDesc(AgentTask::getId);
        return PageResponse.of(taskMapper.selectPage(new Page<>(page, size), qw));
    }

    public AgentTask get(Long id) {
        AgentTask task = taskMapper.selectById(id);
        if (task == null) {
            throw BizException.notFound("任务");
        }
        LoginUser user = SecurityUtils.currentUser();
        if (!"ADMIN".equals(user.role()) && !user.id().equals(task.getCreatedBy())) {
            throw BizException.forbidden("无权访问该任务");
        }
        return task;
    }

    @Transactional
    public AgentTask cancel(Long id) {
        AgentTask task = get(id);
        TaskStatus current = TaskStateMachine.status(task.getStatus());
        if (current.terminal()) {
            throw BizException.conflict("任务已结束（" + current + "），不能取消");
        }
        TaskStateMachine.assertCanTransit(task.getStatus(), TaskStatus.CANCELED.name());
        task.setStatus(TaskStatus.CANCELED.name());
        task.setFinishedAt(LocalDateTime.now());
        taskMapper.updateById(task);

        if (task.getExternalTaskId() != null) {
            try {
                agentClient.cancel(props.getAgent().getInternalToken(), task.getExternalTaskId());
            } catch (Exception e) {
                log.warn("通知 Python 取消任务失败（任务已本地取消）taskId={}", id, e);
            }
        }
        eventService.record(id, task.getStage(), TaskStatus.CANCELED.name(),
                task.getProgress() == null ? 0 : task.getProgress(), "用户取消任务");
        return task;
    }

    @Transactional
    public AgentTask retry(Long id) {
        AgentTask task = get(id);
        TaskStatus current = TaskStateMachine.status(task.getStatus());
        TaskStateMachine.assertCanTransit(task.getStatus(), TaskStatus.QUEUED.name());
        if (current == TaskStatus.SUCCEEDED) {
            throw BizException.conflict("任务已成功，无需重试");
        }

        task.setStatus(TaskStatus.QUEUED.name());
        task.setStage(TaskStage.PARSE.name());
        task.setProgress(0);
        task.setAttempt(task.getAttempt() == null ? 1 : task.getAttempt() + 1);
        task.setErrorMessage(null);
        task.setExternalTaskId(null);
        task.setStartedAt(null);
        task.setFinishedAt(null);
        task.setLastHeartbeatAt(null);
        taskMapper.updateById(task);

        eventService.record(id, TaskStage.PARSE.name(), TaskStatus.QUEUED.name(), 0,
                "第 " + task.getAttempt() + " 次重试");
        dispatchAfterCommit(id);
        return task;
    }

    public List<TaskStageEvent> events(Long id) {
        get(id);
        return eventService.list(id);
    }
}
