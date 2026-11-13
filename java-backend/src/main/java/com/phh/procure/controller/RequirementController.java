package com.phh.procure.controller;

import com.phh.procure.common.ApiResponse;
import com.phh.procure.dto.PageResponse;
import com.phh.procure.dto.RequirementRequest;
import com.phh.procure.entity.PurchaseRequirement;
import com.phh.procure.service.RequirementService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/requirements")
@RequiredArgsConstructor
public class RequirementController {

    private final RequirementService requirementService;

    @GetMapping
    public ApiResponse<PageResponse<PurchaseRequirement>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") @Min(1) long page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(100) long size) {
        return ApiResponse.ok(requirementService.page(keyword, page, size));
    }

    @GetMapping("/{id}")
    public ApiResponse<PurchaseRequirement> detail(@PathVariable Long id) {
        return ApiResponse.ok(requirementService.get(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<PurchaseRequirement> create(@Valid @RequestBody RequirementRequest request) {
        return ApiResponse.ok(requirementService.create(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<PurchaseRequirement> update(@PathVariable Long id,
                                                   @Valid @RequestBody RequirementRequest request) {
        return ApiResponse.ok(requirementService.update(id, request));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        requirementService.delete(id);
    }
}
