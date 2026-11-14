"""只读取证工具：真实抓取（走白名单/限频/体积上限）与确定性 mock 数据源。

设计约束（对应简历里的"安全设计"与"可信度设计"）：
- 只发 GET，不发任何写请求；不跟随跨域名重定向
- 单页体积上限、超时、按域名限频，缺一不可
- mock 数据必须显式标记 [MOCK]，且和真实数据走完全相同的抽取/校验链路，
  避免"mock 能跑通、真数据跑不通"
"""

from __future__ import annotations

import hashlib
import html as html_lib
import re
from dataclasses import dataclass
from datetime import datetime

import httpx

from app.security import DomainLimiter, SecurityError, UrlGuard

_UA = "ProcureAgent/1.0 (+read-only research bot; contact: demo@example.com)"
_ALLOWED_CONTENT_TYPES = ("text/html", "text/plain", "application/xhtml+xml")


@dataclass(frozen=True)
class FetchResult:
    url: str
    ok: bool
    status: int | None
    text: str
    fetched_at: datetime
    note: str = ""

    @property
    def failed(self) -> bool:
        return not self.ok


class SafeFetcher:
    def __init__(
        self,
        client: httpx.AsyncClient,
        guard: UrlGuard,
        limiter: DomainLimiter,
        timeout: float,
        max_bytes: int,
    ) -> None:
        self._client = client
        self._guard = guard
        self._limiter = limiter
        self._timeout = timeout
        self._max_bytes = max_bytes

    async def fetch(self, url: str) -> FetchResult:
        now = datetime.now()
        try:
            host = self._guard.check(url)
        except SecurityError as exc:
            return FetchResult(url, False, None, "", now, f"安全拒绝：{exc}")

        await self._limiter.acquire(host)
        try:
            async with self._client.stream(
                "GET",
                url,
                headers={"User-Agent": _UA, "Accept": "text/html,text/plain;q=0.9"},
                timeout=self._timeout,
                follow_redirects=False,
            ) as resp:
                if resp.is_redirect:
                    location = resp.headers.get("location", "")
                    return FetchResult(
                        url, False, resp.status_code, "", now, f"拒绝跟随重定向：{location[:120]}"
                    )
                if resp.status_code >= 400:
                    return FetchResult(url, False, resp.status_code, "", now, "HTTP 错误")
                ctype = (resp.headers.get("content-type") or "").split(";")[0].strip().lower()
                if ctype and ctype not in _ALLOWED_CONTENT_TYPES:
                    return FetchResult(url, False, resp.status_code, "", now, f"不支持的响应类型：{ctype}")

                chunks: list[bytes] = []
                size = 0
                async for chunk in resp.aiter_bytes():
                    size += len(chunk)
                    if size > self._max_bytes:
                        chunks.append(chunk[: max(0, self._max_bytes - (size - len(chunk)))])
                        break
                    chunks.append(chunk)
                raw = b"".join(chunks)
                encoding = resp.encoding or "utf-8"
                try:
                    body = raw.decode(encoding, errors="replace")
                except LookupError:
                    body = raw.decode("utf-8", errors="replace")
                note = "已截断（超出体积上限）" if size > self._max_bytes else ""
                return FetchResult(url, True, resp.status_code, body, now, note)
        except (httpx.HTTPError, OSError) as exc:
            return FetchResult(url, False, None, "", now, f"抓取失败：{type(exc).__name__}")


_SCRIPT_STYLE = re.compile(r"(?is)<(script|style|noscript)[^>]*>.*?</\1>")
_TAG = re.compile(r"(?s)<[^>]+>")


def html_to_text(body: str, max_chars: int = 6000) -> str:
    text = _SCRIPT_STYLE.sub(" ", body)
    text = re.sub(r"(?i)<br\s*/?>|</p>|</div>|</tr>|</li>", "\n", text)
    text = _TAG.sub(" ", text)
    text = html_lib.unescape(text)
    text = re.sub(r"[ \t\u00a0]+", " ", text)
    text = re.sub(r"\n\s*\n+", "\n", text)
    return text.strip()[:max_chars]


# ---------------------------------------------------------------- mock 数据源

_CATEGORY_BASE_PRICE: dict[str, float] = {
    "办公用品": 28.5,
    "IT设备": 3200.0,
    "工业耗材": 45.0,
    "包装材料": 3.8,
    "物流服务": 1200.0,
    "安防设备": 860.0,
}

_MOCK_TEMPLATE = (
    "[MOCK] 模拟报价页（非真实数据，仅用于断网演示）\n"
    "供应商：{name}\n"
    "商品：{category} / {spec}\n"
    "含税单价：¥{price:.2f}/{unit}\n"
    "起订量（MOQ）：{moq} {unit}\n"
    "交货周期：{lead} 天\n"
    "税率：13%\n"
    "报价有效期：30 天\n"
    "备注：本页由 mock 数据源生成，价格随供应商编号确定性变化，未连接任何真实站点。"
)


class MockSource:
    """确定性的模拟报价：同一供应商 + 同一需求永远得到同一价格，便于测试断言。"""

    def ground_truth(self, supplier_id: int, category: str) -> dict:
        """生成页面前就知道的"真值"，评测脚本用它做抽检对照。"""
        base = _CATEGORY_BASE_PRICE.get(category, 100.0)
        seed = int(hashlib.sha256(f"{supplier_id}:{category}".encode()).hexdigest()[:8], 16)
        drift = (seed % 23) - 11  # -11% .. +11%
        return {
            "unitPrice": round(base * (1 + drift / 100.0), 2),
            "moq": 20 + (seed % 180),
            "leadTimeDays": 3 + (seed % 22),
        }

    def quote_page(self, supplier_id: int, name: str, category: str, spec: str, unit: str) -> str:
        truth = self.ground_truth(supplier_id, category)
        return _MOCK_TEMPLATE.format(
            name=name,
            category=category,
            spec=spec or "标准规格",
            price=truth["unitPrice"],
            unit=unit or "件",
            moq=truth["moq"],
            lead=truth["leadTimeDays"],
        )
