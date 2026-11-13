package com.phh.procure.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record SupplierRequest(
        @NotBlank(message = "供应商编码不能为空")
        @Pattern(regexp = "[A-Za-z0-9_-]{3,32}", message = "供应商编码仅支持字母、数字、下划线、短横线，长度 3-32")
        String code,

        @NotBlank(message = "供应商名称不能为空")
        @Size(max = 128, message = "供应商名称不能超过 128 字")
        String name,

        @NotBlank(message = "品类不能为空")
        @Size(max = 64, message = "品类不能超过 64 字")
        String category,

        @Size(max = 64, message = "所在地区不能超过 64 字")
        String region,

        @Size(max = 255, message = "官网地址不能超过 255 字")
        String website,

        @Size(max = 64, message = "联系人不能超过 64 字")
        String contactName,

        @Size(max = 32, message = "联系电话不能超过 32 字")
        String contactPhone,

        @Size(max = 255, message = "资质列表不能超过 255 字")
        String qualifications,

        @DecimalMin(value = "0.0", message = "评分不能小于 0")
        @DecimalMax(value = "5.0", message = "评分不能大于 5")
        BigDecimal rating,

        Boolean blacklisted,

        @Size(max = 500, message = "备注不能超过 500 字")
        String note
) {
}
