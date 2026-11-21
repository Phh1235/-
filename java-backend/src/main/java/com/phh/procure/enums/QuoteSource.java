package com.phh.procure.enums;

import java.util.Locale;

/** 报价数据来源。枚举化是为了让"任务从哪拿价格"在数据库里可查、在接口上可校验。 */
public enum QuoteSource {

    /** 内置模拟数据，断网也能演示 */
    MOCK,
    /** 供应商报价单导入（推荐的真实用法） */
    IMPORT,
    /** 按白名单抓取公开页面 */
    FETCH;

    public static QuoteSource normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return MOCK;
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("quoteSource 只能是 MOCK / IMPORT / FETCH");
        }
    }
}
