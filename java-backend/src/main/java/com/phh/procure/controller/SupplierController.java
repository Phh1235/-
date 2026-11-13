package com.phh.procure.controller;

import com.phh.procure.common.ApiResponse;
import com.phh.procure.dto.PageResponse;
import com.phh.procure.dto.SupplierRequest;
import com.phh.procure.entity.Supplier;
import com.phh.procure.service.SupplierService;
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
@RequestMapping("/api/suppliers")
@RequiredArgsConstructor
public class SupplierController {

    private final SupplierService supplierService;

    @GetMapping
    public ApiResponse<PageResponse<Supplier>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String category,
            @RequestParam(defaultValue = "1") @Min(1) long page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(100) long size) {
        return ApiResponse.ok(supplierService.page(keyword, category, page, size));
    }

    @GetMapping("/{id}")
    public ApiResponse<Supplier> detail(@PathVariable Long id) {
        return ApiResponse.ok(supplierService.get(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<Supplier> create(@Valid @RequestBody SupplierRequest request) {
        return ApiResponse.ok(supplierService.create(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<Supplier> update(@PathVariable Long id, @Valid @RequestBody SupplierRequest request) {
        return ApiResponse.ok(supplierService.update(id, request));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        supplierService.delete(id);
    }
}
