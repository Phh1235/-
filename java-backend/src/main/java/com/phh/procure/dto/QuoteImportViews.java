package com.phh.procure.dto;

import java.util.List;
import java.util.Map;

/** 报价单导入的返回结构（预览与正式导入共用同一套行结构）。 */
public final class QuoteImportViews {

    private QuoteImportViews() {
    }

    /**
     * @param rowNo  原始文件中的行号（含表头，方便用户对着 Excel 找）
     * @param ok     该行是否通过校验
     * @param errors 失败原因（人类可读，直接展示给采购员）
     * @param data   归一化后的字段，前端预览表格直接用它渲染
     */
    public record Row(int rowNo, boolean ok, List<String> errors, Map<String, String> data) {
    }

    public record Preview(String filename, int total, int okCount, int failedCount, List<Row> rows) {
    }

    public record Result(Long batchId, String filename, int total, int imported, int duplicated, int failed,
                         List<Row> rows) {
    }
}
