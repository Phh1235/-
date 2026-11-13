package com.phh.procure.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.phh.procure.common.BizException;
import com.phh.procure.dto.PageResponse;
import com.phh.procure.dto.RequirementRequest;
import com.phh.procure.entity.AgentTask;
import com.phh.procure.entity.PurchaseRequirement;
import com.phh.procure.mapper.AgentTaskMapper;
import com.phh.procure.mapper.PurchaseRequirementMapper;
import com.phh.procure.security.LoginUser;
import com.phh.procure.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class RequirementService {

    private final PurchaseRequirementMapper requirementMapper;
    private final AgentTaskMapper taskMapper;

    public PageResponse<PurchaseRequirement> page(String keyword, long page, long size) {
        LoginUser user = SecurityUtils.currentUser();
        LambdaQueryWrapper<PurchaseRequirement> qw = Wrappers.lambdaQuery();
        if (!"ADMIN".equals(user.role())) {
            qw.eq(PurchaseRequirement::getCreatedBy, user.id());
        }
        if (StringUtils.hasText(keyword)) {
            String kw = keyword.trim();
            qw.and(w -> w.like(PurchaseRequirement::getTitle, kw).or().like(PurchaseRequirement::getSpec, kw));
        }
        qw.orderByDesc(PurchaseRequirement::getId);
        return PageResponse.of(requirementMapper.selectPage(new Page<>(page, size), qw));
    }

    public PurchaseRequirement get(Long id) {
        PurchaseRequirement requirement = requirementMapper.selectById(id);
        if (requirement == null) {
            throw BizException.notFound("需求单");
        }
        LoginUser user = SecurityUtils.currentUser();
        if (!"ADMIN".equals(user.role()) && !user.id().equals(requirement.getCreatedBy())) {
            throw BizException.forbidden("无权访问该需求单");
        }
        return requirement;
    }

    @Transactional
    public PurchaseRequirement create(RequirementRequest request) {
        PurchaseRequirement requirement = new PurchaseRequirement();
        apply(requirement, request);
        requirement.setCreatedBy(SecurityUtils.currentUserId());
        requirementMapper.insert(requirement);
        return requirement;
    }

    @Transactional
    public PurchaseRequirement update(Long id, RequirementRequest request) {
        PurchaseRequirement requirement = get(id);
        apply(requirement, request);
        requirementMapper.updateById(requirement);
        return requirement;
    }

    @Transactional
    public void delete(Long id) {
        get(id);
        Long taskCount = taskMapper.selectCount(Wrappers.<AgentTask>lambdaQuery().eq(AgentTask::getRequirementId, id));
        if (taskCount != null && taskCount > 0) {
            throw BizException.conflict("该需求单已有 " + taskCount + " 个比价任务，禁止删除（保留审计链路）");
        }
        requirementMapper.deleteById(id);
    }

    private void apply(PurchaseRequirement requirement, RequirementRequest r) {
        requirement.setTitle(r.title().trim());
        requirement.setCategory(r.category().trim());
        requirement.setSpec(r.spec());
        requirement.setQuantity(r.quantity());
        requirement.setUnit(StringUtils.hasText(r.unit()) ? r.unit() : "件");
        requirement.setBudgetAmount(r.budgetAmount());
        requirement.setCurrency(StringUtils.hasText(r.currency()) ? r.currency() : "CNY");
        requirement.setDeliveryCity(r.deliveryCity());
        requirement.setDeadline(r.deadline());
        requirement.setDescription(r.description());
    }
}
