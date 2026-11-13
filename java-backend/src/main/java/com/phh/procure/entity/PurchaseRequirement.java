package com.phh.procure.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("purchase_requirement")
public class PurchaseRequirement {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String title;
    private String category;
    private String spec;
    private BigDecimal quantity;
    private String unit;
    private BigDecimal budgetAmount;
    private String currency;
    private String deliveryCity;
    private LocalDate deadline;
    private String description;
    private Long createdBy;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
