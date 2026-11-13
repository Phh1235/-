package com.phh.procure.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 阶段事件：SSE 断线重连时按 seq 回放，保证进度不丢。
 */
@Data
@TableName("task_stage_event")
public class TaskStageEvent {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long taskId;
    private String stage;
    private Integer seq;
    private String eventStatus;
    private String message;
    private String payload;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
