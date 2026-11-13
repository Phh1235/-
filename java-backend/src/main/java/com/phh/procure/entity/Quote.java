package com.phh.procure.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 报价事实表：每条报价必须带来源 URL、抓取时间、置信度、核验状态。
 * needs_review=1 表示该字段无法自动验证，UI 上显示"待人工核实"。
 */
@Data
@TableName("quote")
public class Quote {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long taskId;
    private Long supplierId;
    private String supplierName;
    private BigDecimal unitPrice;
    private String currency;
    private String unit;
    private BigDecimal moq;
    private Integer leadTimeDays;
    private Boolean taxIncluded;
    private String sourceUrl;
    private LocalDateTime fetchedAt;
    private BigDecimal confidence;
    private Boolean verified;
    private Boolean needsReview;
    private String rawSnippet;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
