package com.phh.procure.enums;

/**
 * 任务状态。终态不可再迁移，只有 FAILED / CANCELED 允许通过 retry 回到 QUEUED。
 */
public enum TaskStatus {

    QUEUED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELED;

    public boolean terminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELED;
    }
}
