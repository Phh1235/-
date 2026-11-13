package com.phh.procure.dto;

import com.baomidou.mybatisplus.core.metadata.IPage;

import java.util.List;

/**
 * 统一分页响应。items 为当前页数据，total 为命中总数。
 */
public record PageResponse<T>(List<T> items, long total, long page, long size) {

    public static <T> PageResponse<T> of(IPage<T> page) {
        return new PageResponse<>(page.getRecords(), page.getTotal(), page.getCurrent(), page.getSize());
    }
}
