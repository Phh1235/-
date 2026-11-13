package com.phh.procure.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("task_result")
public class TaskResult {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long taskId;
    private String recommendedSupplier;
    private String recommendation;
    private String summaryJson;
    private String reportMd;
    private Integer totalCandidates;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
