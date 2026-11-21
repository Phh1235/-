package com.phh.procure.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.phh.procure.common.BizException;
import com.phh.procure.dto.PageResponse;
import com.phh.procure.dto.QuoteImportViews;
import com.phh.procure.entity.ImportBatch;
import com.phh.procure.entity.Supplier;
import com.phh.procure.entity.SupplierQuote;
import com.phh.procure.mapper.ImportBatchMapper;
import com.phh.procure.mapper.SupplierQuoteMapper;
import com.phh.procure.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 报价单导入：CSV（手写解析）与 XLSX（POI）。
 * <p>刻意不做"任意格式智能识别"：列名固定、必填固定，采购员下载模板照填。
 * 采购系统的导入功能一旦"猜列名"，出错时用户根本不知道该改哪一列。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QuoteImportService {

    /** 模板列顺序（含表头行）。改这里要同步改 {@link #templateCsv()} 的说明文字。 */
    private static final List<String> COLUMNS = List.of(
            "供应商名称", "统一社会信用代码", "品类", "规格", "含税单价", "币种", "单位",
            "起订量", "交期(天)", "是否含税", "有效期至", "联系人", "电话", "报价日期", "备注");
    private static final List<String> REQUIRED = List.of("供应商名称", "品类", "规格", "含税单价", "报价日期");

    private static final long MAX_BYTES = 2L * 1024 * 1024;
    private static final int MAX_ROWS = 2000;
    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            DateTimeFormatter.ofPattern("yyyy-M-d"),
            DateTimeFormatter.ofPattern("yyyy/M/d"),
            DateTimeFormatter.ofPattern("yyyy.M.d"),
            DateTimeFormatter.ofPattern("yyyyMMdd"));

    private final SupplierQuoteMapper quoteMapper;
    private final ImportBatchMapper batchMapper;
    private final SupplierService supplierService;

    /** 下载模板：UTF-8 BOM + 说明行（# 开头，解析时忽略）+ 一行示例。 */
    public byte[] templateCsv() {
        StringBuilder sb = new StringBuilder();
        sb.append("# ===== 采购报价单模板（Procure Agent）=====\n");
        sb.append("# 必填列：").append(String.join("、", REQUIRED)).append('\n');
        sb.append("# 选填列：").append(String.join("、", COLUMNS.stream()
                .filter(c -> !REQUIRED.contains(c)).toList())).append('\n');
        sb.append("# 金额支持 ¥28.50 / 1,280.00 写法；日期格式 2026-11-20；是否含税填 是/否\n");
        sb.append("# 以 # 开头的行会被忽略，下面的示例行请替换成真实数据后再上传\n");
        sb.append(String.join(",", COLUMNS)).append('\n');
        sb.append("恒信文具有限公司,91320500MA1XXXXX1X,办公用品,\"70g, 500张/包\",¥25.94,CNY,件,25,4,是,2026-12-31,张经理,13800001111,2026-11-20,示例行-请删除\n");
        sb.append("博文具业有限公司,,办公用品,\"70g, 500张/包\",27.93,CNY,件,48,11,是,2026-12-31,李经理,13900002222,2026-11-20,\n");

        byte[] body = sb.toString().getBytes(StandardCharsets.UTF_8);
        byte[] withBom = new byte[body.length + 3];
        withBom[0] = (byte) 0xEF;
        withBom[1] = (byte) 0xBB;
        withBom[2] = (byte) 0xBF;
        System.arraycopy(body, 0, withBom, 3, body.length);
        return withBom;
    }

    public QuoteImportViews.Preview preview(MultipartFile file) {
        Parsed parsed = analyze(file);
        return new QuoteImportViews.Preview(parsed.filename, parsed.rows.size(),
                (int) parsed.okCount(), parsed.rows.size() - (int) parsed.okCount(), parsed.rows);
    }

    /**
     * 正式入库。整批一个事务：任何一行抛异常都整批回滚，
     * 避免"导入到一半失败、用户不知道该不该重传"。
     */
    @Transactional
    public QuoteImportViews.Result commit(MultipartFile file) {
        Parsed parsed = analyze(file);
        ImportBatch batch = new ImportBatch();
        batch.setFilename(parsed.filename);
        batch.setRowTotal(parsed.rows.size());
        batch.setRowFailed(parsed.rows.size() - (int) parsed.okCount());
        batch.setRowOk((int) parsed.okCount());
        batch.setCreatedBy(SecurityUtils.currentUserId());
        batchMapper.insert(batch);

        int imported = 0;
        int duplicated = 0;
        for (QuoteImportViews.Row row : parsed.rows) {
            if (!row.ok()) {
                continue;
            }
            Map<String, String> d = row.data();
            Supplier supplier = supplierService.findOrCreateByName(
                    d.get("supplierName"), d.get("supplierCode"), d.get("category"));

            Long exists = quoteMapper.selectCount(Wrappers.<SupplierQuote>lambdaQuery()
                    .eq(SupplierQuote::getSupplierId, supplier.getId())
                    .eq(SupplierQuote::getCategory, d.get("category"))
                    .eq(SupplierQuote::getSpec, d.get("spec"))
                    .eq(SupplierQuote::getQuoteDate, LocalDate.parse(d.get("quoteDate"))));
            if (exists != null && exists > 0) {
                duplicated++;
                continue;
            }

            SupplierQuote quote = new SupplierQuote();
            quote.setBatchId(batch.getId());
            quote.setSupplierId(supplier.getId());
            quote.setSupplierName(supplier.getName());
            quote.setSupplierCode(supplier.getCode());
            quote.setCategory(d.get("category"));
            quote.setSpec(d.get("spec"));
            quote.setUnitPrice(new BigDecimal(d.get("unitPrice")));
            quote.setCurrency(d.getOrDefault("currency", "CNY"));
            quote.setUnit(d.getOrDefault("unit", "件"));
            quote.setMoq(parseDecimal(d.get("moq")));
            quote.setLeadTimeDays(parseInt(d.get("leadTimeDays")));
            quote.setTaxIncluded(parseBool(d.get("taxIncluded"), true));
            quote.setQuoteDate(LocalDate.parse(d.get("quoteDate")));
            quote.setValidUntil(parseDate(d.get("validUntil")));
            quote.setContactName(d.get("contactName"));
            quote.setContactPhone(maskPhone(d.get("contactPhone")));
            quote.setRemark(d.get("remark"));
            quote.setRowNo(row.rowNo());
            quote.setSourceRef(parsed.filename + "#L" + row.rowNo());
            quoteMapper.insert(quote);
            imported++;
        }

        batch.setRowOk(imported);
        batchMapper.updateById(batch);
        log.info("报价单导入完成 batch={} file={} 导入={} 重复={} 失败={}",
                batch.getId(), parsed.filename, imported, duplicated, batch.getRowFailed());
        return new QuoteImportViews.Result(batch.getId(), parsed.filename, parsed.rows.size(),
                imported, duplicated, batch.getRowFailed(), parsed.rows);
    }

    public List<ImportBatch> batches() {
        return batchMapper.selectList(Wrappers.<ImportBatch>lambdaQuery()
                .orderByDesc(ImportBatch::getId).last("limit 50"));
    }

    /** 撤销整批：删掉该批次导入的报价，供应商主数据保留（可能已被别处引用）。 */
    @Transactional
    public void revoke(Long batchId) {
        ImportBatch batch = batchMapper.selectById(batchId);
        if (batch == null) {
            throw BizException.notFound("导入批次");
        }
        Long userId = SecurityUtils.currentUserId();
        if (!"ADMIN".equals(SecurityUtils.currentUser().role())
                && batch.getCreatedBy() != null && !batch.getCreatedBy().equals(userId)) {
            throw BizException.forbidden("只能撤销自己导入的批次");
        }
        int removed = quoteMapper.delete(Wrappers.<SupplierQuote>lambdaQuery()
                .eq(SupplierQuote::getBatchId, batchId));
        batchMapper.deleteById(batchId);
        log.info("撤销导入批次 {}，删除报价 {} 条", batchId, removed);
    }

    public PageResponse<SupplierQuote> page(String category, long page, long size) {
        LambdaQueryWrapper<SupplierQuote> qw = Wrappers.lambdaQuery();
        if (StringUtils.hasText(category)) {
            qw.eq(SupplierQuote::getCategory, category.trim());
        }
        qw.orderByDesc(SupplierQuote::getQuoteDate).orderByAsc(SupplierQuote::getUnitPrice);
        return PageResponse.of(quoteMapper.selectPage(new Page<>(page, size), qw));
    }

    // ---------------------------------------------------------------- 解析

    private Parsed analyze(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw BizException.badRequest("请选择要导入的文件");
        }
        if (file.getSize() > MAX_BYTES) {
            throw BizException.badRequest("文件超过 2MB，请拆分后再导入");
        }
        String filename = StringUtils.hasText(file.getOriginalFilename())
                ? file.getOriginalFilename() : "quotes.csv";
        String lower = filename.toLowerCase(Locale.ROOT);
        List<List<String>> raw;
        try {
            byte[] bytes = file.getBytes();
            if (lower.endsWith(".xlsx")) {
                raw = readXlsx(bytes);
            } else if (lower.endsWith(".csv")) {
                raw = readCsv(new String(bytes, StandardCharsets.UTF_8));
            } else {
                throw BizException.badRequest("只支持 .csv 或 .xlsx 文件");
            }
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw BizException.badRequest("文件解析失败：" + e.getMessage());
        }

        if (raw.isEmpty() || raw.size() < 2) {
            throw BizException.badRequest("文件里没有数据行");
        }
        Map<String, Integer> header = new LinkedHashMap<>();
        List<String> head = raw.get(0);
        for (int i = 0; i < head.size(); i++) {
            header.put(head.get(i).trim(), i);
        }
        List<String> missing = REQUIRED.stream().filter(c -> !header.containsKey(c)).toList();
        if (!missing.isEmpty()) {
            throw BizException.badRequest("缺少必填列：" + String.join("、", missing) + "，请下载最新模板");
        }

        List<QuoteImportViews.Row> rows = new ArrayList<>();
        int limit = Math.min(raw.size(), MAX_ROWS + 1);
        for (int r = 1; r < limit; r++) {
            List<String> cells = raw.get(r);
            if (cells.stream().allMatch(c -> !StringUtils.hasText(c))) {
                continue;
            }
            rows.add(validate(r + 1, header, cells));
        }
        return new Parsed(filename, rows);
    }

    private QuoteImportViews.Row validate(int rowNo, Map<String, Integer> header, List<String> cells) {
        List<String> errors = new ArrayList<>();
        Map<String, String> data = new LinkedHashMap<>();

        String name = cell(header, cells, "供应商名称");
        String category = cell(header, cells, "品类");
        String spec = cell(header, cells, "规格");
        String priceRaw = cell(header, cells, "含税单价");
        String dateRaw = cell(header, cells, "报价日期");

        if (!StringUtils.hasText(name)) {
            errors.add("供应商名称必填");
        } else if (name.length() > 128) {
            errors.add("供应商名称超过 128 字");
        }
        if (!StringUtils.hasText(category)) {
            errors.add("品类必填");
        }
        if (!StringUtils.hasText(spec)) {
            errors.add("规格必填");
        }

        BigDecimal price = parseDecimal(priceRaw);
        if (!StringUtils.hasText(priceRaw)) {
            errors.add("含税单价必填");
        } else if (price == null) {
            errors.add("含税单价不是数字：" + priceRaw);
        } else if (price.compareTo(BigDecimal.ZERO) <= 0) {
            errors.add("含税单价必须大于 0");
        } else if (price.compareTo(new BigDecimal("10000000")) > 0) {
            errors.add("含税单价超过上限（1000 万），疑似填错");
        }

        LocalDate quoteDate = parseDate(dateRaw);
        if (!StringUtils.hasText(dateRaw)) {
            errors.add("报价日期必填");
        } else if (quoteDate == null) {
            errors.add("报价日期格式不对：" + dateRaw + "（应为 2026-11-20）");
        }

        LocalDate validUntil = parseDate(cell(header, cells, "有效期至"));
        if (StringUtils.hasText(cell(header, cells, "有效期至")) && validUntil == null) {
            errors.add("有效期至格式不对，应为 2026-12-31");
        }
        if (validUntil != null && quoteDate != null && validUntil.isBefore(quoteDate)) {
            errors.add("有效期早于报价日期");
        }

        String currency = cell(header, cells, "币种");
        currency = StringUtils.hasText(currency) ? currency.toUpperCase(Locale.ROOT) : "CNY";
        if (!currency.matches("[A-Z]{3}")) {
            errors.add("币种应为 3 位字母，如 CNY");
        }

        String moqRaw = cell(header, cells, "起订量");
        String leadRaw = cell(header, cells, "交期(天)");
        if (StringUtils.hasText(moqRaw) && parseDecimal(moqRaw) == null) {
            errors.add("起订量不是数字：" + moqRaw);
        }
        if (StringUtils.hasText(leadRaw) && parseInt(leadRaw) == null) {
            errors.add("交期(天)不是整数：" + leadRaw);
        }

        data.put("supplierName", name);
        data.put("supplierCode", emptyToNull(cell(header, cells, "统一社会信用代码")));
        data.put("category", category);
        data.put("spec", spec);
        data.put("unitPrice", price == null ? "" : price.toPlainString());
        data.put("currency", currency);
        data.put("unit", StringUtils.hasText(cell(header, cells, "单位")) ? cell(header, cells, "单位") : "件");
        data.put("moq", emptyToNull(moqRaw));
        data.put("leadTimeDays", emptyToNull(leadRaw));
        data.put("taxIncluded", String.valueOf(parseBool(cell(header, cells, "是否含税"), true)));
        data.put("quoteDate", quoteDate == null ? "" : quoteDate.toString());
        data.put("validUntil", validUntil == null ? "" : validUntil.toString());
        data.put("contactName", emptyToNull(cell(header, cells, "联系人")));
        data.put("contactPhone", emptyToNull(cell(header, cells, "电话")));
        data.put("remark", emptyToNull(cell(header, cells, "备注")));
        return new QuoteImportViews.Row(rowNo, errors.isEmpty(), List.copyOf(errors), data);
    }

    private String cell(Map<String, Integer> header, List<String> cells, String column) {
        Integer idx = header.get(column);
        if (idx == null || idx >= cells.size()) {
            return "";
        }
        String v = cells.get(idx);
        return v == null ? "" : v.trim();
    }

    private List<List<String>> readCsv(String text) {
        if (text.startsWith("\uFEFF")) {
            text = text.substring(1);
        }
        List<List<String>> rows = new ArrayList<>();
        List<String> current = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    field.append(c);
                }
            } else if (c == '"') {
                inQuotes = true;
            } else if (c == ',') {
                current.add(field.toString());
                field.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    i++;
                }
                current.add(field.toString());
                field.setLength(0);
                addLine(rows, current);
                current = new ArrayList<>();
            } else {
                field.append(c);
            }
        }
        if (field.length() > 0 || !current.isEmpty()) {
            current.add(field.toString());
            addLine(rows, current);
        }
        return rows;
    }

    private void addLine(List<List<String>> rows, List<String> line) {
        if (line.isEmpty()) {
            return;
        }
        String first = line.get(0);
        if (first != null && first.trim().startsWith("#")) {
            return; // 模板里的说明行
        }
        if (line.stream().allMatch(v -> !StringUtils.hasText(v))) {
            return;
        }
        rows.add(line);
    }

    private List<List<String>> readXlsx(byte[] bytes) throws Exception {
        List<List<String>> rows = new ArrayList<>();
        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet sheet = wb.getSheetAt(0);
            DataFormatter fmt = new DataFormatter();
            for (Row row : sheet) {
                List<String> cells = new ArrayList<>();
                for (int c = 0; c < row.getLastCellNum(); c++) {
                    Cell cell = row.getCell(c);
                    cells.add(cellText(cell, fmt));
                }
                addLine(rows, cells);
            }
        }
        return rows;
    }

    private String cellText(Cell cell, DataFormatter fmt) {
        if (cell == null) {
            return "";
        }
        if (cell.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)) {
            return cell.getLocalDateTimeCellValue().toLocalDate().toString();
        }
        if (cell.getCellType() == CellType.FORMULA) {
            return switch (cell.getCachedFormulaResultType()) {
                case NUMERIC -> {
                    if (DateUtil.isCellDateFormatted(cell)) {
                        yield cell.getLocalDateTimeCellValue().toLocalDate().toString();
                    }
                    yield BigDecimal.valueOf(cell.getNumericCellValue()).stripTrailingZeros().toPlainString();
                }
                case STRING -> cell.getStringCellValue();
                case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
                default -> "";
            };
        }
        return fmt.formatCellValue(cell).trim();
    }

    // ---------------------------------------------------------------- 基础解析

    private BigDecimal parseDecimal(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        String cleaned = raw.trim()
                .replace("¥", "").replace("￥", "").replace("$", "")
                .replace(",", "").replace("元", "").replace(" ", "");
        try {
            return new BigDecimal(cleaned);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Integer parseInt(String raw) {
        BigDecimal d = parseDecimal(raw);
        if (d == null) {
            return null;
        }
        try {
            return d.intValueExact();
        } catch (ArithmeticException e) {
            return null;
        }
    }

    private LocalDate parseDate(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        String v = raw.trim();
        for (DateTimeFormatter f : DATE_FORMATS) {
            try {
                return LocalDate.parse(v, f);
            } catch (DateTimeParseException ignored) {
                // 试下一种格式
            }
        }
        return null;
    }

    private Boolean parseBool(String raw, boolean defaultValue) {
        if (!StringUtils.hasText(raw)) {
            return defaultValue;
        }
        String v = raw.trim().toLowerCase(Locale.ROOT);
        if (Arrays.asList("是", "true", "1", "y", "yes", "含税").contains(v)) {
            return true;
        }
        if (Arrays.asList("否", "false", "0", "n", "no", "不含税").contains(v)) {
            return false;
        }
        return defaultValue;
    }

    private String emptyToNull(String v) {
        return StringUtils.hasText(v) ? v : null;
    }

    /** 电话只留前三后四：导入的是供应商联系方式，属于个人敏感信息。 */
    private String maskPhone(String phone) {
        if (!StringUtils.hasText(phone)) {
            return null;
        }
        String p = phone.trim();
        if (p.length() < 7) {
            return "***";
        }
        return p.substring(0, 3) + "****" + p.substring(p.length() - 4);
    }

    private record Parsed(String filename, List<QuoteImportViews.Row> rows) {
        long okCount() {
            return rows.stream().filter(QuoteImportViews.Row::ok).count();
        }
    }
}
