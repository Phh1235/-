package com.phh.procure.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 一次报价单导入。撤销导入 = 删掉这个 batch 下的所有 supplier_quote，
 * 供应商的报价自然回退到上一批，不需要"逐条回滚"。
 */
@Data
@TableName("import_batch")
public class ImportBatch {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String filename;
    private Integer rowTotal;
    private Integer rowOk;
    private Integer rowFailed;
    private Long createdBy;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
