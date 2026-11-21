package com.phh.procure.controller;

import com.phh.procure.common.ApiResponse;
import com.phh.procure.dto.PageResponse;
import com.phh.procure.dto.QuoteImportViews;
import com.phh.procure.entity.ImportBatch;
import com.phh.procure.entity.SupplierQuote;
import com.phh.procure.security.ratelimit.RateLimit;
import com.phh.procure.service.QuoteImportService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 报价单导入。整条链路：下载模板 → 上传预览 → 确认入库 → 需要时整批撤销。
 */
@RestController
@RequestMapping("/api/quotes")
@RequiredArgsConstructor
public class QuoteImportController {

    private static final MediaType TEXT_CSV_UTF8 = new MediaType("text", "csv", StandardCharsets.UTF_8);

    private final QuoteImportService quoteImportService;

    /** 模板下载：说明行写明必填/选填，采购员照着填就不会错列。 */
    @GetMapping("/template.csv")
    @RateLimit(name = "quote-template", limit = 30, windowSeconds = 60)
    public ResponseEntity<byte[]> template() {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"quote-template.csv\"; filename*=UTF-8''%E6%8A%A5%E4%BB%B7%E5%8D%95%E6%A8%A1%E6%9D%BF.csv")
                .contentType(TEXT_CSV_UTF8)
                .body(quoteImportService.templateCsv());
    }

    /** 只校验不落库：把"哪一行错、错在哪"直接回给前端渲染。 */
    @PostMapping(value = "/import/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @RateLimit(name = "quote-import", limit = 20, windowSeconds = 60)
    public ApiResponse<QuoteImportViews.Preview> preview(@RequestPart("file") MultipartFile file) {
        return ApiResponse.ok(quoteImportService.preview(file));
    }

    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @RateLimit(name = "quote-import", limit = 20, windowSeconds = 60)
    public ApiResponse<QuoteImportViews.Result> commit(@RequestPart("file") MultipartFile file) {
        return ApiResponse.ok(quoteImportService.commit(file));
    }

    @GetMapping("/import/batches")
    public ApiResponse<List<ImportBatch>> batches() {
        return ApiResponse.ok(quoteImportService.batches());
    }

    @DeleteMapping("/import/batches/{id}")
    public ResponseEntity<Void> revoke(@PathVariable Long id) {
        quoteImportService.revoke(id);
        return ResponseEntity.noContent().build();
    }

    /** 已入库的供应商报价，按报价日期倒序、价格升序。 */
    @GetMapping
    public ApiResponse<PageResponse<SupplierQuote>> page(
            @RequestParam(required = false) String category,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size) {
        return ApiResponse.ok(quoteImportService.page(category, page, size));
    }
}
