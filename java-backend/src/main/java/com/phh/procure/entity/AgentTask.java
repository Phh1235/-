package com.phh.procure.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 任务表。status/stage 以字符串落库，枚举校验在应用层完成，避免 DB 方言差异。
 */
@Data
@TableName("agent_task")
public class AgentTask {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long requirementId;
    private String status;
    private String stage;
    private Integer progress;
    private Integer attempt;
    private Boolean mockMode;
    /** MOCK / IMPORT / FETCH —— 报价来源，决定 Agent 走模拟、导入报价单还是抓取页面 */
    private String quoteSource;
    private String externalTaskId;
    private String errorMessage;
    private String requestPayload;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private LocalDateTime lastHeartbeatAt;
    private Long createdBy;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
