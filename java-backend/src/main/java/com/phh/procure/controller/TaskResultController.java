package com.phh.procure.controller;

import com.phh.procure.common.ApiResponse;
import com.phh.procure.dto.TaskResultView;
import com.phh.procure.service.ResultService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/api/tasks/{taskId}")
@RequiredArgsConstructor
public class TaskResultController {

    private final ResultService resultService;

    @GetMapping("/result")
    public ApiResponse<TaskResultView> result(@PathVariable Long taskId) {
        return ApiResponse.ok(resultService.get(taskId));
    }

    @GetMapping(value = "/report.md", produces = "text/markdown;charset=UTF-8")
    public ResponseEntity<String> report(@PathVariable Long taskId) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/markdown;charset=UTF-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"report-" + taskId + ".md\"")
                .body(resultService.reportMarkdown(taskId));
    }

    @GetMapping(value = "/export.csv", produces = "text/csv;charset=UTF-8")
    public ResponseEntity<byte[]> exportCsv(@PathVariable Long taskId) {
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"quotes-" + taskId + ".csv\"")
                .body(resultService.exportCsv(taskId));
    }
}
