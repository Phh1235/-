"""任务编排：把一次比价拆成 7 个阶段，每个阶段推一次回调，最后推结果。

关键取舍：Python 侧无状态（进程内内存表，重启即失），真正的状态机在 Java。
这里存在的意义只是"长任务不阻塞 HTTP 线程"和"阶段可观测"，不做重试与持久化。
"""

from __future__ import annotations

import asyncio
import logging
from dataclasses import dataclass, field
from datetime import datetime
from decimal import Decimal
from uuid import uuid4

import httpx

from app.callback import CallbackClient
from app.config import Settings
from app.llm import LlmClient
from app.pipeline import (
    ScoredQuote,
    apply_risk_scores,
    build_report,
    build_risks,
    extract_quote,
    parse_requirement,
    score_quotes,
)
from app.schemas import (
    AgentSubmitRequest,
    CallbackEvent,
    CallbackPayload,
    CandidateSupplier,
    QuoteItem,
    ResultPayload,
)
from app.security import DomainLimiter, UrlGuard, mask_sensitive, sanitize_untrusted
from app.tools import MockSource, SafeFetcher, html_to_text

logger = logging.getLogger(__name__)

# 阶段与进度必须与 Java 的 TaskStage 枚举对齐（PARSE..REPORT），进度单调递增
STAGE_PROGRESS = {
    "PARSE": 10,
    "DISCOVER": 25,
    "FETCH": 45,
    "NORMALIZE": 65,
    "SCORE": 80,
    "RISK": 90,
    "REPORT": 100,
}

_UNIT_ALIASES = {"个": "件", "只": "件", "pcs": "件", "PCS": "件", "台": "台"}


@dataclass
class TaskRecord:
    external_id: str
    task_id: int
    status: str = "QUEUED"
    stage: str = "QUEUED"
    progress: int = 0
    error: str | None = None
    created_at: datetime = field(default_factory=datetime.now)
    updated_at: datetime = field(default_factory=datetime.now)
    canceled: bool = False
    history: list[dict] = field(default_factory=list)

    def view(self) -> dict:
        return {
            "taskId": self.external_id,
            "javaTaskId": self.task_id,
            "status": self.status,
            "stage": self.stage,
            "progress": self.progress,
            "error": self.error,
            "history": self.history,
        }


class TaskManager:
    def __init__(
        self,
        settings: Settings,
        http: httpx.AsyncClient,
        llm: LlmClient,
        guard: UrlGuard,
        limiter: DomainLimiter,
    ) -> None:
        self._settings = settings
        self._llm = llm
        self._guard = guard
        self._fetcher = SafeFetcher(
            http, guard, limiter, settings.fetch_timeout, settings.fetch_max_bytes
        )
        self._mock = MockSource()
        self._callbacks = CallbackClient(settings, http)
        self._tasks: dict[str, TaskRecord] = {}
        self._runners: dict[str, asyncio.Task] = {}

    # ------------------------------------------------------------ 对外接口

    @property
    def fetcher(self) -> SafeFetcher:
        """给 /v1/inspect 这类"单页取证"接口复用同一套抓取器（同样的白名单/限频/只读）。"""
        return self._fetcher

    async def submit(self, req: AgentSubmitRequest) -> TaskRecord:
        record = TaskRecord(external_id=uuid4().hex, task_id=req.taskId)
        self._tasks[record.external_id] = record
        self._runners[record.external_id] = asyncio.create_task(
            self._run(record, req), name=f"agent-{record.external_id[:8]}"
        )
        return record

    def get(self, external_id: str) -> TaskRecord | None:
        return self._tasks.get(external_id)

    async def cancel(self, external_id: str) -> bool:
        record = self._tasks.get(external_id)
        if record is None:
            return False
        record.canceled = True
        runner = self._runners.get(external_id)
        if runner and not runner.done():
            runner.cancel()
        record.status = "CANCELED"
        record.updated_at = datetime.now()
        return True

    async def shutdown(self) -> None:
        for runner in self._runners.values():
            if not runner.done():
                runner.cancel()

    # ------------------------------------------------------------ 主流程

    async def _run(self, record: TaskRecord, req: AgentSubmitRequest) -> None:
        record.status = "RUNNING"
        mock_mode = req.mockMode or self._settings.mock_mode
        self._llm.reset_task_budget()
        try:
            await self._stage(record, "PARSE", "解析采购需求")
            quote_ctx = parse_requirement(req.requirement)
            spec_attrs: dict | None = None
            # 预算 ≥2 次时拆成两次调用：PARSE 阶段做规格归一化，REPORT 阶段写结论。
            # 免密钥网关只有 1 次配额，会走合并的单次调用。
            split_calls = self._settings.llm_max_calls_per_task >= 2
            if split_calls and self._llm.active:
                spec_attrs = await self._llm.normalize_spec(
                    req.requirement.category, req.requirement.title, req.requirement.spec or ""
                )

            await self._stage(record, "DISCOVER", "确认候选供应商名单")
            candidates = req.candidates[:20]
            if not candidates:
                raise RuntimeError("Java 未下发候选供应商名单")

            await self._stage(record, "FETCH", f"抓取 {len(candidates)} 家供应商报价页")
            quotes, fetch_risks = await self._collect(quote_ctx, candidates, mock_mode, record)

            await self._stage(record, "NORMALIZE", "清洗并归一化报价字段")
            ratings = {
                c.id: float(c.rating) if c.rating is not None else 3.5 for c in candidates
            }

            scored = score_quotes(quotes, ratings)
            risks = build_risks(quotes, candidates, mock_mode) + fetch_risks
            apply_risk_scores(scored, risks)

            await self._stage(record, "SCORE", "按价格/交期/资质/风险加权打分")
            await self._stage(record, "RISK", f"识别 {len(risks)} 条风险项")

            # REPORT 阶段才让大模型写结论：它只复述事实，不参与任何算钱。
            # 预算只有 1 次时，规格归一化与结论合并成一次调用，避免低配额网关被自己打满。
            ordered = sorted(scored, key=lambda s: s.total, reverse=True)
            narrative = None
            if self._llm.active and ordered:
                best = ordered[0]
                facts = {
                    "recommended": best.quote.supplierName,
                    "unitPrice": float(best.quote.unitPrice),
                    "unit": best.quote.unit,
                    "leadTimeDays": best.quote.leadTimeDays,
                    "totalScore": round(best.total, 2),
                    "candidateCount": len(ordered),
                    "highRisks": [r.title for r in risks if r.level == "HIGH"][:3],
                    "top3": [
                        {
                            "supplierName": s.quote.supplierName,
                            "unitPrice": float(s.quote.unitPrice),
                            "score": round(s.total, 2),
                        }
                        for s in ordered[:3]
                    ],
                }
                if split_calls:
                    narrative = await self._llm.write_recommendation(facts)
                else:
                    outcome = await self._llm.analyze_and_recommend(
                        req.requirement.category, req.requirement.title,
                        req.requirement.spec or "", facts,
                    )
                    if outcome:
                        spec_attrs = outcome.get("specAttributes") or spec_attrs
                        narrative = outcome.get("recommendation")

            engine = {
                "llmUsed": self._llm.used,
                "llmModel": self._llm.model_label if self._llm.used else None,
                "llmStages": "规格结构化 + 推荐结论撰写" if (spec_attrs and narrative) else (
                    "推荐结论撰写" if narrative else ("规格结构化" if spec_attrs else "")
                ),
                "llmCalls": self._llm.calls,
                "llmError": self._llm.last_error,
            }
            report_md, summary_json, recommended, recommendation = build_report(
                req.requirement, quote_ctx, scored, risks, mock_mode,
                narrative=narrative, spec_attributes=spec_attrs, engine=engine,
            )
            await self._stage(record, "REPORT", "生成比价报告")

            if record.canceled:
                return

            payload = CallbackPayload(
                quotes=[s.quote for s in sorted(scored, key=lambda s: s.total, reverse=True)],
                risks=risks,
                result=ResultPayload(
                    recommendedSupplier=recommended,
                    recommendation=recommendation,
                    totalCandidates=len(scored),
                    summaryJson=summary_json,
                    reportMd=report_md,
                ),
            )
            record.status = "SUCCEEDED"
            record.progress = 100
            record.stage = "DONE"
            record.updated_at = datetime.now()
            await self._send(record, "SUCCEEDED", "DONE", 100, "比价完成", payload)
            logger.info("任务 %s 完成：%d 条报价", record.external_id[:8], len(scored))
        except asyncio.CancelledError:
            record.status = "CANCELED"
            record.updated_at = datetime.now()
            await self._send(record, "FAILED", record.stage, record.progress, "任务已取消")
            raise
        except Exception as exc:  # 任何异常都要让 Java 侧看到失败原因，而不是卡在 RUNNING
            record.status = "FAILED"
            record.error = f"{type(exc).__name__}: {str(exc)[:300]}"
            record.updated_at = datetime.now()
            logger.exception("任务 %s 失败", record.external_id[:8])
            await self._send(record, "FAILED", record.stage, record.progress, record.error)

    async def _stage(self, record: TaskRecord, stage: str, message: str) -> None:
        record.stage = stage
        record.progress = STAGE_PROGRESS.get(stage, record.progress)
        record.updated_at = datetime.now()
        record.history.append({"stage": stage, "progress": record.progress, "at": datetime.now().isoformat(timespec="seconds")})
        await self._send(record, "STAGE", stage, record.progress, message)
        if self._settings.stage_delay_ms > 0:
            await asyncio.sleep(self._settings.stage_delay_ms / 1000.0)

    async def _send(
        self,
        record: TaskRecord,
        event_type: str,
        stage: str,
        progress: int,
        message: str,
        payload: CallbackPayload | None = None,
    ) -> None:
        event = CallbackEvent(
            eventId=uuid4().hex,
            taskId=record.task_id,
            eventType=event_type,
            stage=stage,
            progress=progress,
            message=message,
            payload=payload,
        )
        ok = await self._callbacks.send(event)
        if not ok:
            logger.error("回调 Java 失败：task=%s event=%s", record.task_id, event_type)

    # ------------------------------------------------------------ 取证

    async def _collect(
        self,
        ctx: dict,
        candidates: list[CandidateSupplier],
        mock_mode: bool,
        record: TaskRecord,
    ):
        quotes: list[QuoteItem] = []
        fetch_risks = []
        from app.schemas import RiskItem

        for cand in candidates:
            if record.canceled:
                break
            if cand.blacklisted:
                fetch_risks.append(
                    RiskItem(
                        supplierName=cand.name,
                        type="合规",
                        level="HIGH",
                        title="黑名单供应商已跳过取证",
                        detail="主数据标记为黑名单，不下单也不纳入比价。",
                        sourceUrl="",
                        needsReview=False,
                    )
                )
                continue

            url = (cand.website or f"https://example.com/suppliers/{cand.code or cand.id}").strip()
            if mock_mode:
                page = self._mock.quote_page(
                    cand.id, cand.name, ctx["category"], ctx["spec"], ctx["unit"]
                )
                fetched_at = datetime.now()
                snippet_source = "mock"
            else:
                result = await self._fetcher.fetch(url)
                if not result.ok:
                    fetch_risks.append(
                        RiskItem(
                            supplierName=cand.name,
                            type="数据源",
                            level="LOW",
                            title="报价页抓取失败",
                            detail=f"{result.note or '未知原因'}，该供应商本次无报价数据。",
                            sourceUrl=url,
                            needsReview=True,
                        )
                    )
                    continue
                page = html_to_text(result.text)
                fetched_at = result.fetched_at
                snippet_source = "web"

            clean = sanitize_untrusted(page)
            outcome = await extract_quote(clean.text, ctx, self._llm, cand)
            if outcome.fields is None:
                fetch_risks.append(
                    RiskItem(
                        supplierName=cand.name,
                        type="可信度",
                        level="MEDIUM",
                        title="未抽取到可回查的报价",
                        detail=outcome.rejected_reason or "页面无价格字段",
                        sourceUrl=url,
                        needsReview=True,
                    )
                )
                continue

            price: Decimal = outcome.fields["unitPrice"]
            confidence = {"mock": Decimal("0.60"), "regex": Decimal("0.85"), "llm": Decimal("0.75")}[
                snippet_source if snippet_source == "mock" else outcome.method
            ]
            verified = snippet_source == "web" and outcome.method in {"regex", "llm"}
            unit = _UNIT_ALIASES.get(str(outcome.fields.get("unit") or ctx["unit"]), str(outcome.fields.get("unit") or ctx["unit"]))
            quotes.append(
                QuoteItem(
                    supplierId=cand.id,
                    supplierName=cand.name,
                    unitPrice=price,
                    currency=str(outcome.fields.get("currency") or "CNY"),
                    unit=unit,
                    moq=outcome.fields.get("moq"),
                    leadTimeDays=outcome.fields.get("leadTimeDays"),
                    taxIncluded=outcome.fields.get("taxIncluded"),
                    sourceUrl=url,
                    fetchedAt=fetched_at,
                    confidence=confidence,
                    verified=verified,
                    needsReview=not verified,
                    rawSnippet=mask_sensitive(str(outcome.fields.get("priceSnippet") or ""))[:300]
                    or None,
                )
            )
        return quotes, fetch_risks
