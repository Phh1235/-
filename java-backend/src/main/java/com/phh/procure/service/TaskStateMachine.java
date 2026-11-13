package com.phh.procure.service;

import com.phh.procure.common.BizException;
import com.phh.procure.enums.TaskStage;
import com.phh.procure.enums.TaskStatus;

import java.util.Map;
import java.util.Set;

/**
 * 显式状态机。把"合法迁移"集中在一处，而不是散落在 Service 的 if-else 里——
 * 这是面试里区分"真写过状态机"和"拼接口"的关键点。
 */
public final class TaskStateMachine {

    private static final Map<TaskStatus, Set<TaskStatus>> ALLOWED = Map.of(
            TaskStatus.QUEUED, Set.of(TaskStatus.RUNNING, TaskStatus.CANCELED),
            TaskStatus.RUNNING, Set.of(TaskStatus.SUCCEEDED, TaskStatus.FAILED, TaskStatus.CANCELED),
            TaskStatus.SUCCEEDED, Set.of(),
            TaskStatus.FAILED, Set.of(TaskStatus.QUEUED),
            TaskStatus.CANCELED, Set.of(TaskStatus.QUEUED)
    );

    private TaskStateMachine() {
    }

    public static TaskStatus status(String value) {
        if (value == null || value.isBlank()) {
            throw BizException.badRequest("任务状态缺失");
        }
        try {
            return TaskStatus.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw BizException.badRequest("未知任务状态：" + value);
        }
    }

    public static void assertCanTransit(String from, String to) {
        TaskStatus source = status(from);
        TaskStatus target = status(to);
        if (source == target) {
            return;
        }
        if (!ALLOWED.getOrDefault(source, Set.of()).contains(target)) {
            throw BizException.conflict("非法状态迁移：" + source + " → " + target);
        }
    }

    /** 回调阶段只能前进；相同阶段视为重放（幂等），由调用方决定是否落库。 */
    public static void assertStageAdvance(String current, String next) {
        TaskStage from = TaskStage.parse(current);
        TaskStage to = TaskStage.parse(next);
        if (to.ordinal() < from.ordinal()) {
            throw BizException.conflict("任务阶段不能回退：" + from + " → " + to);
        }
    }
}
