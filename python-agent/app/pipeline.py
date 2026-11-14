"""抽取 → 归一化 → 打分 → 风险 → 报告。

这里刻意把"模型能做什么"限制得很死：
- 模型只负责从一段已消毒的文本里抽字段，价格必须能在原文中回查到数字，否则作废；
- 排序和总分是纯函数计算（可单测、可复现），模型不参与算数；
- 报告正文里的数字全部来自事实表，模型只允许写不含数字的定性描述，写完再做一次数字回查。
"""

from __future__ import annotations

import json
import re
import statistics
from dataclasses import dataclass, field
from datetime import datetime
from decimal import Decimal, InvalidOperation

from app.llm import LlmClient
from app.schemas import CandidateSupplier, QuoteItem, RequirementPayload, RiskItem, jsonable
from app.security import mask_sensitive

# ---------------------------------------------------------------- 需求解析

_SPLIT = re.compile(r"[\s,，。;；、/|（）()【】\[\]{}:：]+")


def fmt_num(value: object) -> str:
    """把 Decimal('200.0') 这类值显示成 200，避免报告里出现 200.0 件。"""
    if value is None or value == "":
        return "未填写"
    try:
        d = Decimal(str(value))
    except (InvalidOperation, ValueError):
        return str(value)
    if d == d.to_integral_value():
        return str(d.quantize(Decimal("1")))
    return format(d.normalize(), "f")


def parse_requirement(req: RequirementPayload) -> dict:
    """规则解析：品类、规格、单位、数量、预算单价、关键词。

    先用确定性规则拿到"骨架"，模型只用来补语义标签（可选），
    这样即使模型不可用，下游也不用改代码。
    """
    unit = (req.unit or "件").strip() or "件"
    quantity = req.quantity or Decimal("0")
    budget_unit = None
    if req.budgetAmount and quantity and quantity > 0:
        budget_unit = (req.budgetAmount / quantity).quantize(Decimal("0.01"))

    text = " ".join(filter(None, [req.title, req.spec, req.description]))
    tokens = [t for t in _SPLIT.split(text) if 2 <= len(t) <= 12]
    keywords: list[str] = []
    for t in tokens:
        if t not in keywords:
            keywords.append(t)
    if req.category and req.category not in keywords:
        keywords.insert(0, req.category)

    return {
        "category": req.category,
        "spec": req.spec or "",
        "unit": unit,
        "quantity": quantity,
        "budget_unit_price": budget_unit,
        "keywords": keywords[:8],
        "delivery_city": req.deliveryCity or "",
    }


# ---------------------------------------------------------------- 报价抽取

_PRICE_PATTERNS = (
    re.compile(r"[¥￥]\s*([0-9][0-9,]*(?:\.[0-9]{1,2})?)"),
    re.compile(r"(?:含税单价|单价|报价|价格)\s*[:：]?\s*[¥￥]?\s*([0-9][0-9,]*(?:\.[0-9]{1,2})?)"),
    re.compile(r"([0-9][0-9,]*(?:\.[0-9]{1,2})?)\s*元\s*/\s*[^\s，,。;；]{1,4}"),
)
_MOQ_PATTERN = re.compile(r"(?:起订量|最小起订量|MOQ)[^0-9]{0,12}([0-9][0-9,]*)", re.I)
_LEAD_PATTERN = re.compile(r"(?:交货周期|交货期|交期|货期|交付周期)\s*[:：]?\s*([0-9]{1,3})\s*(?:个)?(?:工作)?[天日]")
_UNIT_PATTERN = re.compile(r"[¥￥]?\s*[0-9][0-9,]*(?:\.[0-9]{1,2})?\s*元?\s*/\s*([^\s，,。;；]{1,4})")


def _to_decimal(raw: str) -> Decimal | None:
    try:
        return Decimal(raw.replace(",", "")).quantize(Decimal("0.01"))
    except (InvalidOperation, AttributeError):
        return None


def _price_forms(price: Decimal) -> set[str]:
    """价格在原文里可能的写法：28.5 / 28.50 / 1,280.00。"""
    plain = f"{price:.2f}"
    trimmed = plain.rstrip("0").rstrip(".")
    with_comma = f"{price:,.2f}"
    return {plain, trimmed, with_comma, with_comma.rstrip("0").rstrip(".")}


def verify_price_in_text(price: Decimal, text: str) -> bool:
    """反编造的关键一步：价格必须能在原文里找到同值数字。"""
    return any(form in text for form in _price_forms(price))


@dataclass
class ExtractOutcome:
    fields: dict | None
    method: str  # regex | llm | none
    rejected_reason: str = ""


# 上下文权重：页面里"原价/市场价"这类数字不能当成交价
_PRICE_CONTEXT = (
    ("含税单价", 3),
    ("现价", 3),
    ("单价", 2),
    ("报价", 2),
    ("价格", 1),
    ("元/", 1),
    ("原价", -4),
    ("市场价", -4),
    ("吊牌价", -4),
    ("参考价", -2),
)


def _price_candidates(text: str) -> list[tuple[int, int, Decimal, int, int]]:
    """返回 (上下文得分, 位置, 价格, start, end)，按得分降序、位置升序排列。"""
    found: dict[tuple[int, Decimal], tuple[int, int, Decimal, int, int]] = {}
    for pattern in _PRICE_PATTERNS:
        for m in pattern.finditer(text):
            value = _to_decimal(m.group(1))
            if value is None:
                continue
            window = text[max(0, m.start() - 18) : m.end() + 8]
            score = 0
            for keyword, weight in _PRICE_CONTEXT:
                if keyword in window:
                    score += weight
            key = (value, m.end())
            if key not in found:
                found[key] = (score, m.start(), value, m.start(), m.end())
    return sorted(found.values(), key=lambda item: (-item[0], item[1]))


async def extract_quote(
    page_text: str,
    ctx: dict,
    llm: LlmClient,
    supplier: CandidateSupplier,
) -> ExtractOutcome:
    price: Decimal | None = None
    snippet = ""
    candidates = _price_candidates(page_text)
    if candidates:
        _score, _pos, price, start, end = candidates[0]
        snippet = page_text[max(0, start - 30) : end + 60].replace("\n", " ").strip()

    moq_m = _MOQ_PATTERN.search(page_text)
    lead_m = _LEAD_PATTERN.search(page_text)
    unit_m = _UNIT_PATTERN.search(page_text)
    tax_included = _tax_flag(page_text)

    fields: dict = {
        "unitPrice": price,
        "currency": "CNY" if price is not None else None,
        "unit": (unit_m.group(1) if unit_m else ctx["unit"]),
        "moq": _to_decimal(moq_m.group(1)) if moq_m else None,
        "leadTimeDays": int(lead_m.group(1)) if lead_m else None,
        "taxIncluded": tax_included,
        "priceSnippet": snippet or None,
    }

    if price is None and llm.active:
        data = await llm.extract_quote_fields(
            page_text, ctx["category"], ctx["spec"], ctx["unit"]
        )
        if data and data.get("unitPrice") is not None:
            candidate_price = _to_decimal(str(data["unitPrice"]))
            llm_snippet = str(data.get("priceSnippet") or "")
            if candidate_price is not None and verify_price_in_text(candidate_price, page_text):
                price = candidate_price
                fields.update(
                    unitPrice=price,
                    currency=str(data.get("currency") or "CNY"),
                    unit=str(data.get("unit") or ctx["unit"]),
                    moq=_to_decimal(str(data["moq"])) if data.get("moq") is not None else fields["moq"],
                    leadTimeDays=data.get("leadTimeDays") or fields["leadTimeDays"],
                    taxIncluded=data.get("taxIncluded") if data.get("taxIncluded") is not None else tax_included,
                    priceSnippet=llm_snippet or snippet or None,
                )
                return ExtractOutcome(fields, "llm")

    if price is None:
        return ExtractOutcome(None, "none", "页面未出现可回查的价格数字")

    # 规则抽到的价格也要做一次回查（防御正则误匹配到"原价 999"之类）
    if not verify_price_in_text(price, page_text):
        return ExtractOutcome(None, "none", "价格未能在原文中回查")

    return ExtractOutcome(fields, "regex")


def _tax_flag(text: str) -> bool | None:
    if "不含税" in text:
        return False
    if "含税" in text or "税率" in text:
        return True
    return None


# ---------------------------------------------------------------- 打分


@dataclass
class ScoredQuote:
    quote: QuoteItem
    rating: float
    price_score: float = 0.0
    lead_score: float = 0.0
    qualification_score: float = 0.0
    risk_score: float = 0.0
    total: float = 0.0
    flags: list[str] = field(default_factory=list)


def score_quotes(quotes: list[QuoteItem], ratings: dict[int, float]) -> list[ScoredQuote]:
    """确定性打分：价格 40 / 交期 20 / 资质 20 / 风险 20，权重写在代码里可审计。"""
    if not quotes:
        return []
    min_price = min(q.unitPrice for q in quotes)
    scored: list[ScoredQuote] = []
    for q in quotes:
        price_score = float(min_price / q.unitPrice * 40) if q.unitPrice > 0 else 0.0
        lead = q.leadTimeDays if q.leadTimeDays is not None else 999
        lead_score = 20.0 if lead <= 7 else max(0.0, 20.0 - (lead - 7) * 0.6)
        rating = ratings.get(q.supplierId, 3.5)
        qualification_score = max(0.0, min(20.0, rating / 5.0 * 20.0))
        scored.append(
            ScoredQuote(
                quote=q,
                rating=rating,
                price_score=round(price_score, 2),
                lead_score=round(lead_score, 2),
                qualification_score=round(qualification_score, 2),
            )
        )
    return scored


def apply_risk_scores(scored: list[ScoredQuote], risks: list[RiskItem]) -> None:
    penalty = {"HIGH": 6.0, "MEDIUM": 3.0, "LOW": 1.0}
    by_supplier: dict[str, float] = {}
    for r in risks:
        by_supplier[r.supplierName] = by_supplier.get(r.supplierName, 0.0) + penalty.get(r.level, 1.0)
    for item in scored:
        penalty_value = by_supplier.get(item.quote.supplierName, 0.0)
        item.risk_score = round(max(0.0, 20.0 - penalty_value), 2)
        item.total = round(
            item.price_score + item.lead_score + item.qualification_score + item.risk_score, 2
        )


# ---------------------------------------------------------------- 风险


def build_risks(
    quotes: list[QuoteItem],
    candidates: list[CandidateSupplier],
    mock_mode: bool,
) -> list[RiskItem]:
    risks: list[RiskItem] = []
    by_id = {c.id: c for c in candidates}
    prices = [q.unitPrice for q in quotes]
    median = statistics.median(prices) if prices else None

    for q in quotes:
        cand = by_id.get(q.supplierId)
        if cand and cand.blacklisted:
            risks.append(
                RiskItem(
                    supplierName=q.supplierName,
                    type="合规",
                    level="HIGH",
                    title="供应商在黑名单内",
                    detail="主数据中该供应商已被标记为黑名单，不得直接下单，需走例外审批。",
                    sourceUrl="",
                    needsReview=False,
                )
            )
        if not q.sourceUrl:
            risks.append(
                RiskItem(
                    supplierName=q.supplierName,
                    type="可信度",
                    level="MEDIUM",
                    title="缺少报价来源",
                    detail="该报价没有可回溯的来源 URL，按规则标为待人工核实。",
                    sourceUrl="",
                    needsReview=True,
                )
            )
        if median and q.unitPrice < median * Decimal("0.4"):
            risks.append(
                RiskItem(
                    supplierName=q.supplierName,
                    type="报价异常",
                    level="HIGH",
                    title="报价显著低于中位数（<40%）",
                    detail=f"单价 {q.unitPrice}，中位数 {median:.2f}，需核对规格、税率与是否漏项。",
                    sourceUrl=q.sourceUrl,
                    needsReview=True,
                )
            )
        if q.leadTimeDays is not None and q.leadTimeDays > 30:
            risks.append(
                RiskItem(
                    supplierName=q.supplierName,
                    type="交付",
                    level="LOW",
                    title="交期超过 30 天",
                    detail=f"页面标注交期 {q.leadTimeDays} 天，可能影响需求方时间计划。",
                    sourceUrl=q.sourceUrl,
                    needsReview=False,
                )
            )
        if cand and cand.rating is not None and float(cand.rating) < 3.5:
            risks.append(
                RiskItem(
                    supplierName=q.supplierName,
                    type="资质",
                    level="MEDIUM",
                    title="历史评分偏低",
                    detail=f"主数据评分 {cand.rating}（满分 5），建议补充资质材料。",
                    sourceUrl="",
                    needsReview=False,
                )
            )
        if q.taxIncluded is None:
            risks.append(
                RiskItem(
                    supplierName=q.supplierName,
                    type="商务条款",
                    level="LOW",
                    title="未标注是否含税",
                    detail="页面未出现含税/税率信息，比价时需统一到含税口径。",
                    sourceUrl=q.sourceUrl,
                    needsReview=True,
                )
            )

    if mock_mode:
        risks.insert(
            0,
            RiskItem(
                supplierName="全局",
                type="可信度",
                level="MEDIUM",
                title="当前为 MOCK 模式（模拟数据）",
                detail="所有报价由本地 mock 数据源生成，仅用于功能演示，不可用于真实采购决策。",
                sourceUrl="",
                needsReview=True,
            ),
        )
    return risks


# ---------------------------------------------------------------- 报告

_NUMBER = re.compile(r"\d+(?:\.\d+)?")


def numbers_in(text: str) -> set[str]:
    return {m.group(0).rstrip("0").rstrip(".") for m in _NUMBER.finditer(text or "")}


def narrative_guard(narrative: str, facts: dict) -> str | None:
    """文本闸：正文里出现的数字必须在事实表中存在，否则整段丢弃。"""
    allowed = numbers_in(json.dumps(jsonable(facts), ensure_ascii=False))
    for token in numbers_in(narrative):
        if token not in allowed and f"{token}.0" not in allowed:
            return None
    return narrative


def build_report(
    req: RequirementPayload,
    ctx: dict,
    scored: list[ScoredQuote],
    risks: list[RiskItem],
    mock_mode: bool,
    narrative: str | None = None,
) -> tuple[str, str, str, str]:
    """返回 (report_md, summary_json, recommended_supplier, recommendation)。"""
    ordered = sorted(scored, key=lambda s: s.total, reverse=True)
    best = ordered[0] if ordered else None
    recommended = best.quote.supplierName if best else ""
    now = datetime.now().strftime("%Y-%m-%d %H:%M:%S")

    if best:
        recommendation = (
            f"综合价格、交期、资质与风险四项加权，推荐 {best.quote.supplierName}"
            f"（综合得分 {best.total:.2f}，含税单价 {best.quote.unitPrice:.2f} 元/{best.quote.unit}，"
            f"交期 {best.quote.leadTimeDays if best.quote.leadTimeDays is not None else '未标注'} 天）。"
        )
        if any(r.level == "HIGH" for r in risks if r.supplierName == best.quote.supplierName):
            recommendation += "该供应商存在高风险项，签约前必须完成人工复核。"
    else:
        recommendation = "没有拿到任何可回查的报价，无法给出推荐结论，请补充数据源后重试。"

    if narrative:
        guarded = narrative_guard(narrative, {"quotes": [jsonable(s.quote) for s in ordered], "recommended": recommended})
        if guarded:
            recommendation = guarded

    lines: list[str] = []
    lines.append(f"# 采购比价报告：{mask_sensitive(req.title)}")
    lines.append("")
    lines.append(f"- 生成时间：{now}")
    lines.append(f"- 数据模式：{'MOCK（模拟数据）' if mock_mode else '真实抓取'}")
    lines.append(f"- 候选供应商：{len(scored)} 家（按主数据名单取证）")
    lines.append("")
    lines.append("## 一、需求摘要")
    lines.append("")
    lines.append(f"- 品类：{req.category}；规格：{req.spec or '未填写'}")
    lines.append(f"- 数量：{fmt_num(req.quantity)} {ctx['unit']}；预算：{fmt_num(req.budgetAmount)} {req.currency or 'CNY'}")
    if ctx.get("budget_unit_price"):
        lines.append(f"- 预算折算单价：{ctx['budget_unit_price']} 元/{ctx['unit']}（用于后续价格偏离判断）")
    lines.append(f"- 交付地：{req.deliveryCity or '未填写'}")
    lines.append("")
    lines.append("## 二、比价表")
    lines.append("")
    lines.append("| 排名 | 供应商 | 含税单价 | 起订量 | 交期(天) | 价格分 | 交期分 | 资质分 | 风险分 | 总分 | 数据来源 |")
    lines.append("| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |")
    for idx, item in enumerate(ordered, start=1):
        q = item.quote
        source = "待人工核实" if q.needsReview else "已验证"
        lines.append(
            f"| {idx} | {q.supplierName} | {q.unitPrice:.2f} {q.currency}/{q.unit} | "
            f"{q.moq or '-'} | {q.leadTimeDays if q.leadTimeDays is not None else '-'} | "
            f"{item.price_score:.1f} | {item.lead_score:.1f} | {item.qualification_score:.1f} | "
            f"{item.risk_score:.1f} | **{item.total:.2f}** | {source} |"
        )
    lines.append("")
    lines.append("## 三、资质与风险")
    lines.append("")
    if risks:
        for r in risks:
            flag = "（待人工核实）" if r.needsReview else ""
            lines.append(f"- [{r.level}] {r.supplierName} · {r.type} · {r.title}{flag}")
            lines.append(f"  - {r.detail}")
    else:
        lines.append("- 未发现规则库覆盖的风险项（不代表没有风险，规则未覆盖的情形仍需人工判断）。")
    lines.append("")
    lines.append("## 四、推荐结论")
    lines.append("")
    lines.append(recommendation)
    lines.append("")
    lines.append("## 五、数据可信度")
    lines.append("")
    for item in ordered:
        q = item.quote
        lines.append(f"- {q.supplierName}：{q.sourceUrl or '无来源'} ，抓取/生成时间 {q.fetchedAt:%Y-%m-%d %H:%M:%S}，置信度 {float(q.confidence):.2f}")
    lines.append("")
    lines.append("## 六、方法与局限")
    lines.append("")
    lines.append("- 价格只来自页面文本抽取，且回查原文数字；模型不参与报价生成。")
    lines.append("- 总分为确定性加权公式（价格 40 / 交期 20 / 资质 20 / 风险 20），同一输入结果可复现。")
    if mock_mode:
        lines.append("- 本报告使用 MOCK 数据，价格与交期均为模拟值，禁止用于真实采购。")
    lines.append("- 未覆盖：账期、付款方式、售后条款、实地验厂——这些必须线下确认。")
    lines.append("")

    summary = {
        "task": {"title": req.title, "category": req.category, "quantity": str(req.quantity or "")},
        "recommended": recommended,
        "quotes": [
            {
                "supplierId": s.quote.supplierId,
                "supplierName": s.quote.supplierName,
                "unitPrice": float(s.quote.unitPrice),
                "currency": s.quote.currency,
                "unit": s.quote.unit,
                "leadTimeDays": s.quote.leadTimeDays,
                "moq": float(s.quote.moq) if s.quote.moq is not None else None,
                "verified": s.quote.verified,
                "needsReview": s.quote.needsReview,
                "sourceUrl": s.quote.sourceUrl,
                "score": s.total,
            }
            for s in ordered
        ],
        "riskCounts": {
            level: sum(1 for r in risks if r.level == level) for level in ("HIGH", "MEDIUM", "LOW")
        },
    }
    return "\n".join(lines), json.dumps(summary, ensure_ascii=False), recommended, recommendation
