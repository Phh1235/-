package com.phh.procure.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

public record RequirementRequest(
        @NotBlank(message = "需求标题不能为空")
        @Size(max = 128, message = "需求标题不能超过 128 字")
        String title,

        @NotBlank(message = "品类不能为空")
        @Size(max = 64, message = "品类不能超过 64 字")
        String category,

        @Size(max = 255, message = "规格描述不能超过 255 字")
        String spec,

        @DecimalMin(value = "0.01", message = "数量必须大于 0")
        @DecimalMax(value = "10000000", message = "数量超出可处理范围")
        BigDecimal quantity,

        @Size(max = 16, message = "单位不能超过 16 字")
        String unit,

        @DecimalMin(value = "0", message = "预算不能为负数")
        BigDecimal budgetAmount,

        @Pattern(regexp = "[A-Z]{3}", message = "币种需为 3 位大写字母，如 CNY")
        String currency,

        @Size(max = 64, message = "交付地不能超过 64 字")
        String deliveryCity,

        LocalDate deadline,

        @Size(max = 1000, message = "补充说明不能超过 1000 字")
        String description
) {
}
