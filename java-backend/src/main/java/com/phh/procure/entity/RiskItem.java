package com.phh.procure.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("risk_item")
public class RiskItem {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long taskId;
    private String supplierName;
    /** 风险类型：资质缺失 / 黑名单 / 报价异常 / 信息缺失 / 交期风险 */
    private String type;
    /** HIGH / MEDIUM / LOW */
    private String level;
    private String title;
    private String detail;
    private String sourceUrl;
    private Boolean needsReview;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
