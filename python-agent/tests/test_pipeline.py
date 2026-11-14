"""抽取 / 打分 / 风险 / 报告的单元测试 —— 重点验证"不编造"和"可复现"。"""

from datetime import datetime
from decimal import Decimal

import pytest

from app.config import get_settings
from app.llm import LlmClient
from app.pipeline import (
    apply_risk_scores,
    build_report,
    build_risks,
    extract_quote,
    narrative_guard,
    numbers_in,
    parse_requirement,
    score_quotes,
    verify_price_in_text,
)
from app.schemas import CandidateSupplier, QuoteItem, RequirementPayload
from app.tools import MockSource

SETTINGS = get_settings()
LLM = LlmClient(SETTINGS)  # 本地无 Key → active=False，自动走规则引擎

REQ = RequirementPayload(
    id=1,
    title="A4 复印纸 70g 采购",
    category="办公用品",
    spec="A4 70g 500 张/包",
    quantity=Decimal("100"),
    unit="件",
    budgetAmount=Decimal("3000"),
    currency="CNY",
    deliveryCity="苏州",
)


def _quote(supplier_id: int, name: str, price: str, lead: int = 7) -> QuoteItem:
    return QuoteItem(
        supplierId=supplier_id,
        supplierName=name,
        unitPrice=Decimal(price),
        currency="CNY",
        unit="件",
        moq=Decimal("50"),
        leadTimeDays=lead,
        taxIncluded=True,
        sourceUrl=f"https://example.com/s/{supplier_id}",
        fetchedAt=datetime(2026, 11, 14, 9, 0, 0),
        confidence=Decimal("0.85"),
        verified=True,
        needsReview=False,
    )


def test_parse_requirement_computes_budget_unit_price():
    ctx = parse_requirement(REQ)
    assert ctx["unit"] == "件"
    assert ctx["budget_unit_price"] == Decimal("30.00")
    assert "办公用品" in ctx["keywords"]


@pytest.mark.asyncio
async def test_extract_from_mock_page_keeps_source_fields():
    page = MockSource().quote_page(1, "苏州晨光文具", "办公用品", "A4 70g", "件")
    ctx = parse_requirement(REQ)
    outcome = await extract_quote(page, ctx, LLM, CandidateSupplier(id=1, name="苏州晨光文具"))
    assert outcome.method == "regex"
    assert outcome.fields["unitPrice"] > 0
    assert outcome.fields["moq"] is not None
    assert outcome.fields["leadTimeDays"] is not None
    assert outcome.fields["priceSnippet"]


@pytest.mark.asyncio
async def test_no_price_means_no_quote():
    """没有价格的页面必须抽不出报价 —— 这是"禁止大模型编造报价"的第一道闸。"""
    page = "供应商简介：本公司成立于 2001 年，主营办公用品，欢迎来电洽谈。"
    ctx = parse_requirement(REQ)
    outcome = await extract_quote(page, ctx, LLM, CandidateSupplier(id=2, name="某公司"))
    assert outcome.fields is None
    assert "价格" in outcome.rejected_reason


def test_price_must_be_verifiable_in_text():
    assert verify_price_in_text(Decimal("12.50"), "单价 ¥12.50/件")
    assert verify_price_in_text(Decimal("1280.00"), "报价 1,280.00 元")
    assert not verify_price_in_text(Decimal("12.50"), "单价 ¥99.00/件")


@pytest.mark.asyncio
async def test_price_outside_text_is_rejected():
    """正则命中但数字对不上（例如命中了"原价"以外的片段）→ 直接作废。"""
    page = "折扣说明：满 100 件享 9 折。"
    ctx = parse_requirement(REQ)
    outcome = await extract_quote(page, ctx, LLM, CandidateSupplier(id=3, name="某公司"))
    assert outcome.fields is None


def test_scoring_is_deterministic_and_price_dominant():
    quotes = [_quote(1, "甲", "25.00"), _quote(2, "乙", "30.00"), _quote(3, "丙", "40.00")]
    ratings = {1: 4.0, 2: 4.8, 3: 3.0}

    first = score_quotes(quotes, ratings)
    second = score_quotes(quotes, ratings)
    assert [round(s.price_score, 2) for s in first] == [round(s.price_score, 2) for s in second]

    cheapest = next(s for s in first if s.quote.supplierId == 1)
    priciest = next(s for s in first if s.quote.supplierId == 3)
    assert cheapest.price_score == 40.0
    assert priciest.price_score < 30.0


def test_risk_penalty_lowers_total():
    quotes = [_quote(1, "甲", "25.00")]
    scored = score_quotes(quotes, {1: 5.0})
    risk = build_risks(quotes, [CandidateSupplier(id=1, name="甲")], mock_mode=False)
    apply_risk_scores(scored, risk)
    base = scored[0].price_score + scored[0].lead_score + scored[0].qualification_score
    assert scored[0].total == pytest.approx(base + scored[0].risk_score, abs=0.01)
    assert scored[0].risk_score <= 20.0


def test_blacklisted_and_mock_risks():
    quotes = [_quote(1, "甲", "25.00")]
    cands = [CandidateSupplier(id=1, name="甲", blacklisted=True)]
    risks = build_risks(quotes, cands, mock_mode=True)
    types = {(r.type, r.level) for r in risks}
    assert ("合规", "HIGH") in types
    assert any("MOCK" in r.title for r in risks)


def test_price_outlier_detected():
    quotes = [_quote(1, "甲", "100.00"), _quote(2, "乙", "95.00"), _quote(3, "丙", "10.00")]
    risks = build_risks(quotes, [], mock_mode=False)
    outlier = [r for r in risks if r.type == "报价异常"]
    assert len(outlier) == 1 and outlier[0].supplierName == "丙"


def test_report_contains_ranked_table_and_disclaimer():
    quotes = [_quote(1, "甲", "25.00"), _quote(2, "乙", "30.00", lead=15)]
    ctx = parse_requirement(REQ)
    scored = score_quotes(quotes, {1: 4.0, 2: 4.8})
    risks = build_risks(quotes, [], mock_mode=True)
    apply_risk_scores(scored, risks)

    md, summary_json, recommended, recommendation = build_report(REQ, ctx, scored, risks, True)
    assert "| 排名 |" in md
    assert recommended in md
    assert "[MOCK]" in md or "MOCK" in md
    assert "禁止用于真实采购" in md
    assert '"recommended"' in summary_json
    assert recommended == "甲"  # 25 < 30 且交期更短
    assert "甲" in recommendation


def test_narrative_guard_blocks_unknown_numbers():
    facts = {"recommended": "甲", "quotes": [{"unitPrice": 25.0}]}
    assert narrative_guard("推荐甲，单价 25 元。", facts) is not None
    assert narrative_guard("推荐甲，预计节省 37 元。", facts) is None


def test_numbers_in_normalises_trailing_zero():
    assert "12.5" in numbers_in("单价 12.50 元")
