package com.phh.procure.enums;

/**
 * 任务阶段。ordinal 即推进顺序，回调只能前进不能回退。
 */
public enum TaskStage {

    PARSE,
    DISCOVER,
    FETCH,
    NORMALIZE,
    SCORE,
    RISK,
    REPORT,
    DONE;

    public static TaskStage parse(String value) {
        if (value == null || value.isBlank()) {
            return PARSE;
        }
        return TaskStage.valueOf(value.trim().toUpperCase());
    }

    public boolean after(TaskStage other) {
        return this.ordinal() > other.ordinal();
    }

    /** 进度百分比：按阶段均分，便于前端画进度条。 */
    public int progress() {
        return Math.round(ordinal() * 100f / (values().length - 1));
    }
}
