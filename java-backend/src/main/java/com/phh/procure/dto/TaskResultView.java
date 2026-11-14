package com.phh.procure.dto;

import com.phh.procure.entity.AgentTask;
import com.phh.procure.entity.Quote;
import com.phh.procure.entity.RiskItem;
import com.phh.procure.entity.TaskResult;

import java.util.List;

/**
 * 任务结果视图：任务 + 报价表 + 风险清单 + 推荐结论。
 * 报价按单价升序返回，前端直接渲染即可。
 */
public record TaskResultView(
        AgentTask task,
        List<Quote> quotes,
        List<RiskItem> risks,
        TaskResult result
) {
}
