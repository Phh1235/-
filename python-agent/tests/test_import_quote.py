"""导入报价单的转换与打分。

这一组测试对应"真实数据源"那条链路：供应商把报价单发给我们 → 采购员导入 →
比价时价格直接来自报价单，而不是抓页面或模型推断。
"""

from datetime import date
from decimal import Decimal

from app.pipeline import apply_risk_scores, quote_from_seed, score_quotes
from app.schemas import CandidateSupplier, LatestQuote

CTX = {"category": "办公用品", "spec": "70g", "unit": "件", "currency": "CNY"}


def candidate(**overrides) -> CandidateSupplier:
    fields = dict(
        unitPrice=Decimal("25.94"),
        currency="CNY",
        unit="件",
        moq=Decimal("25"),
        leadTimeDays=4,
        taxIncluded=True,
        quoteDate=date(2026, 11, 20),
        validUntil=date(2026, 12, 31),
        sourceRef="报价单-1121.csv#L2",
    )
    fields.update(overrides)
    return CandidateSupplier(
        id=1,
        code="SUP-001",
        name="恒信文具有限公司",
        rating=Decimal("4.6"),
        latestQuote=LatestQuote(**fields),
    )


def test_seed_becomes_verified_quote():
    quote = quote_from_seed(candidate(), CTX)

    assert quote.unitPrice == Decimal("25.94")
    assert quote.verified is True
    assert quote.needsReview is False
    assert quote.confidence == Decimal("0.95")
    assert quote.sourceUrl == "报价单-1121.csv#L2"
    assert quote.leadTimeDays == 4
    assert quote.fetchedAt.strftime("%Y-%m-%d") == "2026-11-20"


def test_seed_wins_over_page_price_when_scoring():
    imported = quote_from_seed(candidate(), CTX)

    # 同品类另一家更贵：价格分应按导入价算，而不是任何"抓来的价"
    other = candidate(unitPrice=Decimal("30.00"))
    other.id = 2
    other.name = "新元办公"

    scored = score_quotes([imported, quote_from_seed(other, CTX)], {1: 4.6, 2: 4.0})
    apply_risk_scores(scored, [])
    by_name = {s.quote.supplierName: s for s in scored}
    assert by_name["恒信文具有限公司"].total > by_name["新元办公"].total
    assert by_name["恒信文具有限公司"].price_score == 40.0


def test_missing_unit_falls_back_to_requirement_unit():
    ctx = {**CTX, "unit": "包"}
    quote = quote_from_seed(candidate(unit=None, currency=None), ctx)
    assert quote.unit == "包"
    assert quote.currency == "CNY"
