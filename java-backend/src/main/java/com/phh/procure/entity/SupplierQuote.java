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

/**
 * 供应商报价（导入的原始报价单行）。
 * <p>和 {@code quote} 的区别：quote 是"某次任务算出来的快照"，这张表是"供应商报给我们的原始数据"，
 * 因此可以跨任务复用、可以保留历史、可以画价格趋势。</p>
 */
@Data
@TableName("supplier_quote")
public class SupplierQuote {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long batchId;
    private Long supplierId;
    private String supplierName;
    private String supplierCode;
    private String category;
    private String spec;
    private BigDecimal unitPrice;
    private String currency;
    private String unit;
    private BigDecimal moq;
    private Integer leadTimeDays;
    private Boolean taxIncluded;
    private LocalDate quoteDate;
    private LocalDate validUntil;
    private String contactName;
    private String contactPhone;
    private String remark;
    private Integer rowNo;
    private String sourceRef;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
