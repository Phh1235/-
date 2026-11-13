package com.phh.procure;

import com.phh.procure.common.BizException;
import com.phh.procure.enums.TaskStage;
import com.phh.procure.service.TaskStateMachine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("单元：任务状态机")
class TaskStateMachineTest {

    @Test
    @DisplayName("合法迁移不抛异常")
    void legalTransitions() {
        TaskStateMachine.assertCanTransit("QUEUED", "RUNNING");
        TaskStateMachine.assertCanTransit("RUNNING", "SUCCEEDED");
        TaskStateMachine.assertCanTransit("RUNNING", "FAILED");
        TaskStateMachine.assertCanTransit("FAILED", "QUEUED");
        TaskStateMachine.assertCanTransit("CANCELED", "QUEUED");
        TaskStateMachine.assertCanTransit("QUEUED", "QUEUED");
    }

    @Test
    @DisplayName("终态不可回退到运行中")
    void terminalCannotGoBack() {
        assertThatThrownBy(() -> TaskStateMachine.assertCanTransit("SUCCEEDED", "RUNNING"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("非法状态迁移");
        assertThatThrownBy(() -> TaskStateMachine.assertCanTransit("CANCELED", "RUNNING"))
                .isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("未知状态返回 400 而不是 500")
    void unknownStatus() {
        assertThatThrownBy(() -> TaskStateMachine.status("DONE_MAYBE"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("未知任务状态");
    }

    @Test
    @DisplayName("阶段只能前进，不能回退")
    void stageMonotonic() {
        TaskStateMachine.assertStageAdvance("PARSE", "FETCH");
        TaskStateMachine.assertStageAdvance("SCORE", "SCORE");
        assertThatThrownBy(() -> TaskStateMachine.assertStageAdvance("REPORT", "PARSE"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不能回退");
    }

    @Test
    @DisplayName("阶段进度百分比覆盖 0 与 100")
    void stageProgress() {
        assertThat(TaskStage.PARSE.progress()).isZero();
        assertThat(TaskStage.DONE.progress()).isEqualTo(100);
        assertThat(TaskStage.SCORE.progress()).isBetween(1, 99);
    }
}
