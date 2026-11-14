"""与 Java 侧 DTO 一一对应的载荷模型。

字段命名与 java-backend 的 AgentSubmitRequest / AgentCallbackRequest 保持一致（camelCase），
改这里就要同步改 Java，两侧契约靠这两个文件对齐。
"""

from __future__ import annotations

import json
from datetime import date, datetime
from decimal import Decimal
from typing import Any, Literal

from pydantic import BaseModel, Field


class RequirementPayload(BaseModel):
    id: int | None = None
    title: str
    category: str
    spec: str | None = None
    quantity: Decimal | None = None
    unit: str | None = None
    budgetAmount: Decimal | None = None
    currency: str | None = None
    deliveryCity: str | None = None
    deadline: date | None = None
    description: str | None = None


class CandidateSupplier(BaseModel):
    id: int
    code: str | None = None
    name: str
    website: str | None = None
    category: str | None = None
    rating: Decimal | None = None
    blacklisted: bool = False


class AgentSubmitRequest(BaseModel):
    taskId: int
    mockMode: bool = False
    callbackUrl: str | None = None
    requirement: RequirementPayload
    candidates: list[CandidateSupplier] = Field(default_factory=list)


class QuoteItem(BaseModel):
    supplierId: int
    supplierName: str
    unitPrice: Decimal
    currency: str = "CNY"
    unit: str = "件"
    moq: Decimal | None = None
    leadTimeDays: int | None = None
    taxIncluded: bool | None = None
    sourceUrl: str = ""
    fetchedAt: datetime
    confidence: Decimal = Decimal("0.5")
    verified: bool = False
    needsReview: bool = True
    rawSnippet: str | None = None


class RiskItem(BaseModel):
    supplierName: str
    type: str
    level: Literal["HIGH", "MEDIUM", "LOW"]
    title: str
    detail: str
    sourceUrl: str = ""
    needsReview: bool = True


class ResultPayload(BaseModel):
    recommendedSupplier: str
    recommendation: str
    totalCandidates: int
    summaryJson: str
    reportMd: str


class CallbackPayload(BaseModel):
    quotes: list[QuoteItem] = Field(default_factory=list)
    risks: list[RiskItem] = Field(default_factory=list)
    result: ResultPayload | None = None


class CallbackEvent(BaseModel):
    eventId: str
    taskId: int
    eventType: Literal["STAGE", "SUCCEEDED", "FAILED"]
    stage: str | None = None
    progress: int | None = None
    message: str | None = None
    payload: CallbackPayload | None = None


def jsonable(value: Any) -> Any:
    """把 Decimal/date/datetime 转成 Java Jackson 能直接吃的形态。

    Decimal 转 float：金额在这个量级（分）不会丢精度，且避免 Jackson 收到字符串要多一层兼容。
    datetime 输出不带时区偏移的 ISO 串，对应 Java 侧 LocalDateTime。
    """
    if isinstance(value, Decimal):
        return float(value)
    if isinstance(value, datetime):
        return value.strftime("%Y-%m-%dT%H:%M:%S")
    if isinstance(value, date):
        return value.isoformat()
    if isinstance(value, dict):
        return {k: jsonable(v) for k, v in value.items()}
    if isinstance(value, (list, tuple)):
        return [jsonable(v) for v in value]
    return value


def dumps(model: BaseModel) -> str:
    """序列化成紧凑 JSON —— 回调签名对这个字符串做 HMAC，两边必须字节一致。"""
    return json.dumps(
        jsonable(model.model_dump(exclude_none=True)),
        ensure_ascii=False,
        separators=(",", ":"),
    )
