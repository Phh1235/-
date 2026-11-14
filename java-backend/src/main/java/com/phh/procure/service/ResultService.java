package com.phh.procure.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.phh.procure.common.BizException;
import com.phh.procure.dto.TaskResultView;
import com.phh.procure.entity.AgentTask;
import com.phh.procure.entity.Quote;
import com.phh.procure.entity.RiskItem;
import com.phh.procure.entity.TaskResult;
import com.phh.procure.mapper.QuoteMapper;
import com.phh.procure.mapper.RiskItemMapper;
import com.phh.procure.mapper.TaskResultMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ResultService {

    private final TaskService taskService;
    private final QuoteMapper quoteMapper;
    private final RiskItemMapper riskItemMapper;
    private final TaskResultMapper taskResultMapper;

    public TaskResultView get(Long taskId) {
        AgentTask task = taskService.get(taskId);
        List<Quote> quotes = quoteMapper.selectList(Wrappers.<Quote>lambdaQuery()
                .eq(Quote::getTaskId, taskId)
                .orderByAsc(Quote::getUnitPrice)
                .orderByAsc(Quote::getId));
        List<RiskItem> risks = riskItemMapper.selectList(Wrappers.<RiskItem>lambdaQuery()
                .eq(RiskItem::getTaskId, taskId)
                .orderByAsc(RiskItem::getId));
        TaskResult result = taskResultMapper.selectOne(Wrappers.<TaskResult>lambdaQuery()
                .eq(TaskResult::getTaskId, taskId)
                .last("LIMIT 1"));
        return new TaskResultView(task, quotes, risks, result);
    }

    public String reportMarkdown(Long taskId) {
        TaskResultView view = get(taskId);
        if (view.result() == null || view.result().getReportMd() == null
                || view.result().getReportMd().isBlank()) {
            throw BizException.notFound("比价报告（任务尚未成功或未生成报告）");
        }
        return view.result().getReportMd();
    }

    /** 导出 CSV：用 \uFEFF BOM 让 Excel 正确识别 UTF-8 中文。 */
    public byte[] exportCsv(Long taskId) {
        TaskResultView view = get(taskId);
        StringBuilder sb = new StringBuilder();
        sb.append("供应商,单价,币种,单位,起订量,交期(天),含税,来源URL,抓取时间,置信度,已核实,待人工核实\n");
        for (Quote q : view.quotes()) {
            sb.append(csv(q.getSupplierName())).append(',')
                    .append(q.getUnitPrice() == null ? "" : q.getUnitPrice().toPlainString()).append(',')
                    .append(csv(q.getCurrency())).append(',')
                    .append(csv(q.getUnit())).append(',')
                    .append(q.getMoq() == null ? "" : q.getMoq().toPlainString()).append(',')
                    .append(q.getLeadTimeDays() == null ? "" : q.getLeadTimeDays()).append(',')
                    .append(Boolean.TRUE.equals(q.getTaxIncluded()) ? "是" : "否").append(',')
                    .append(csv(q.getSourceUrl())).append(',')
                    .append(q.getFetchedAt() == null ? "" : q.getFetchedAt()).append(',')
                    .append(q.getConfidence() == null ? "" : q.getConfidence().toPlainString()).append(',')
                    .append(Boolean.TRUE.equals(q.getVerified()) ? "是" : "否").append(',')
                    .append(Boolean.TRUE.equals(q.getNeedsReview()) ? "是" : "否").append('\n');
        }
        byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        byte[] content = sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] out = new byte[bom.length + content.length];
        System.arraycopy(bom, 0, out, 0, bom.length);
        System.arraycopy(content, 0, out, bom.length, content.length);
        return out;
    }

    private String csv(String value) {
        if (value == null) {
            return "";
        }
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}
