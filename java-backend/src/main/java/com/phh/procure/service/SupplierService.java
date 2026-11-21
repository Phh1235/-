package com.phh.procure.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.phh.procure.common.BizException;
import com.phh.procure.dto.PageResponse;
import com.phh.procure.dto.SupplierRequest;
import com.phh.procure.entity.Supplier;
import com.phh.procure.mapper.SupplierMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;

@Service
@RequiredArgsConstructor
public class SupplierService {

    private final SupplierMapper supplierMapper;

    public PageResponse<Supplier> page(String keyword, String category, long page, long size) {
        LambdaQueryWrapper<Supplier> qw = Wrappers.lambdaQuery();
        if (StringUtils.hasText(keyword)) {
            String kw = keyword.trim();
            qw.and(w -> w.like(Supplier::getName, kw).or().like(Supplier::getCode, kw));
        }
        if (StringUtils.hasText(category)) {
            qw.eq(Supplier::getCategory, category.trim());
        }
        qw.orderByDesc(Supplier::getRating).orderByAsc(Supplier::getId);
        return PageResponse.of(supplierMapper.selectPage(new Page<>(page, size), qw));
    }

    public Supplier get(Long id) {
        Supplier supplier = supplierMapper.selectById(id);
        if (supplier == null) {
            throw BizException.notFound("供应商");
        }
        return supplier;
    }

    @Transactional
    public Supplier create(SupplierRequest request) {
        if (findByCode(request.code()) != null) {
            throw BizException.conflict("供应商编码已存在：" + request.code());
        }
        Supplier supplier = new Supplier();
        apply(supplier, request);
        supplierMapper.insert(supplier);
        return supplier;
    }

    @Transactional
    public Supplier update(Long id, SupplierRequest request) {
        Supplier supplier = get(id);
        Supplier sameCode = findByCode(request.code());
        if (sameCode != null && !sameCode.getId().equals(id)) {
            throw BizException.conflict("供应商编码已存在：" + request.code());
        }
        apply(supplier, request);
        supplierMapper.updateById(supplier);
        return supplier;
    }

    @Transactional
    public void delete(Long id) {
        get(id);
        supplierMapper.deleteById(id);
    }

    public Supplier findByCode(String code) {
        return supplierMapper.selectOne(Wrappers.<Supplier>lambdaQuery().eq(Supplier::getCode, code));
    }

    /**
     * 报价单导入时按名称找供应商：找到就复用，找不到就建一条"待补充资质"的草稿供应商。
     * <p>这就是"新公司没有供应商库也能用"的关键一步——采购员导入一张报价单，
     * 供应商主数据就自动长出来了，而不是要求他先去手工录 10 家公司。</p>
     */
    @Transactional
    public Supplier findOrCreateByName(String name, String code, String category) {
        String normalized = name == null ? "" : name.trim();
        if (normalized.isEmpty()) {
            throw BizException.badRequest("供应商名称不能为空");
        }
        if (StringUtils.hasText(code)) {
            Supplier byCode = findByCode(code.trim());
            if (byCode != null) {
                return byCode;
            }
        }
        Supplier existing = supplierMapper.selectOne(Wrappers.<Supplier>lambdaQuery()
                .eq(Supplier::getName, normalized)
                .last("limit 1"));
        if (existing != null) {
            return existing;
        }

        Supplier supplier = new Supplier();
        supplier.setCode(StringUtils.hasText(code) ? code.trim() : nextImportedCode());
        supplier.setName(normalized);
        supplier.setCategory(StringUtils.hasText(category) ? category.trim() : "未分类");
        supplier.setWebsite("");
        supplier.setRating(new BigDecimal("3.50"));
        supplier.setBlacklisted(false);
        supplier.setNote("由报价单导入自动创建，资质待补充");
        supplierMapper.insert(supplier);
        return supplier;
    }

    private String nextImportedCode() {
        Long count = supplierMapper.selectCount(Wrappers.<Supplier>lambdaQuery()
                .likeRight(Supplier::getCode, "SUP-IMP-"));
        return String.format("SUP-IMP-%03d", (count == null ? 0 : count) + 1);
    }

    private void apply(Supplier supplier, SupplierRequest r) {
        supplier.setCode(r.code().trim());
        supplier.setName(r.name().trim());
        supplier.setCategory(r.category().trim());
        supplier.setRegion(r.region());
        supplier.setWebsite(r.website());
        supplier.setContactName(r.contactName());
        supplier.setContactPhone(r.contactPhone());
        supplier.setQualifications(r.qualifications());
        supplier.setRating(r.rating());
        supplier.setBlacklisted(Boolean.TRUE.equals(r.blacklisted()));
        supplier.setNote(r.note());
    }
}
