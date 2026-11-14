"""安全原语：域名白名单、提示词注入过滤、敏感信息脱敏、按域名限频。

这些不是"演示用的装饰"，每一个都在抓取链路上真正卡口：
- UrlGuard      : 只允许白名单域名 + 只允许 http(s) + 拒绝 IP 直连 / 私网 / 带 userinfo 的 URL
- sanitize      : 外部文本进模型前，剥离控制字符、截断、识别注入话术并打标
- mask_sensitive: 报告/日志出站前，手机号/邮箱/身份证/银行卡打码
- DomainLimiter : 同一域名最小请求间隔，避免把对方站点打挂（也降低被封风险）
"""

from __future__ import annotations

import asyncio
import ipaddress
import re
import time
from dataclasses import dataclass
from urllib.parse import urlsplit

# ---------------------------------------------------------------- 域名白名单


class SecurityError(ValueError):
    """抓取链路上的安全拒绝。调用方应把它转成"跳过该数据源"而不是整单失败。"""


class UrlGuard:
    """只读抓取的第一道闸：白名单域名 + 协议 + 私网地址。

    白名单匹配规则：host 等于名单项，或以 ".名单项" 结尾（即允许子域名）。
    """

    def __init__(self, allowed_domains: tuple[str, ...]) -> None:
        self._allowed = tuple(d.strip().lower() for d in allowed_domains if d.strip())

    @property
    def allowed_domains(self) -> tuple[str, ...]:
        return self._allowed

    def check(self, url: str) -> str:
        if not url:
            raise SecurityError("URL 为空")
        parts = urlsplit(url)
        if parts.scheme not in {"http", "https"}:
            raise SecurityError(f"仅允许 http/https，收到 {parts.scheme or '(空)'}")
        if parts.username or parts.password:
            raise SecurityError("URL 不允许携带 userinfo")
        host = (parts.hostname or "").lower().rstrip(".")
        if not host:
            raise SecurityError("URL 缺少主机名")
        if _is_ip_literal(host):
            raise SecurityError(f"禁止直连 IP：{host}")
        if not self._match(host):
            raise SecurityError(f"域名不在白名单：{host}")
        return host

    def _match(self, host: str) -> bool:
        for allowed in self._allowed:
            if host == allowed or host.endswith("." + allowed):
                return True
        return False


def _is_ip_literal(host: str) -> bool:
    try:
        ipaddress.ip_address(host)
        return True
    except ValueError:
        return False


# ---------------------------------------------------------------- 注入过滤

# 只做"识别 + 打标 + 隔离"，不做"正则清洗后就相信" —— 真正防注入靠的是
# 1) 内容包在 <untrusted_data> 里并明确告知模型这是数据不是指令
# 2) 输出走结构化抽取 + 数值回查原文，模型没有自由发挥空间
_INJECTION_PATTERNS: tuple[re.Pattern[str], ...] = (
    re.compile(r"ignore\s+(all\s+)?(previous|above|prior)\s+instructions?", re.I),
    re.compile(r"disregard\s+(all\s+)?(previous|above)", re.I),
    re.compile(r"you\s+are\s+now\s+(a|an|the)\b", re.I),
    re.compile(r"(system|developer)\s*(prompt|message)", re.I),
    re.compile(r"忽略(以上|之前|前面|上述)(的)?(所有)?(指令|要求|提示)"),
    re.compile(r"(你现在|从现在开始)(是|扮演|作为)"),
    re.compile(r"(输出|打印|泄露|告诉我).{0,10}(系统提示|提示词|密钥|password|api\s*key)", re.I),
    re.compile(r"</?untrusted_data>", re.I),
    re.compile(r"<\|im_start\|>|<\|im_end\|>"),
)

_CONTROL_CHARS = re.compile(r"[\x00-\x08\x0b\x0c\x0e-\x1f\x7f]")


@dataclass(frozen=True)
class SanitizedText:
    text: str
    flags: tuple[str, ...]

    @property
    def suspicious(self) -> bool:
        return bool(self.flags)


def sanitize_untrusted(raw: str, max_chars: int = 4000) -> SanitizedText:
    text = _CONTROL_CHARS.sub(" ", raw or "")
    text = re.sub(r"[ \t\u00a0]+", " ", text)
    text = re.sub(r"\n{3,}", "\n\n", text).strip()
    flags = [p.pattern for p in _INJECTION_PATTERNS if p.search(text)]
    return SanitizedText(text=text[:max_chars], flags=tuple(flags))


def wrap_untrusted(text: str) -> str:
    """把外部内容包进显式边界，配合系统提示"边界内只当数据"。"""
    return f"<untrusted_data>\n{text}\n</untrusted_data>"


# ---------------------------------------------------------------- 脱敏

_PHONE = re.compile(r"(?<!\d)(1[3-9]\d)(\d{4})(\d{4})(?!\d)")
_EMAIL = re.compile(r"([A-Za-z0-9._%+-]{1,3})[A-Za-z0-9._%+-]*(@[A-Za-z0-9.-]+\.[A-Za-z]{2,})")
_ID_CARD = re.compile(r"(?<!\d)(\d{4})\d{10}(\d{2}[\dXx])(?!\d)")
_BANK_CARD = re.compile(r"(?<!\d)(\d{4})\d{8,11}(\d{4})(?!\d)")


def mask_sensitive(text: str) -> str:
    if not text:
        return text
    text = _ID_CARD.sub(r"\1**********\2", text)
    text = _BANK_CARD.sub(r"\1********\2", text)
    text = _PHONE.sub(r"\1****\3", text)
    text = _EMAIL.sub(lambda m: f"{m.group(1)}***{m.group(2)}", text)
    return text


# ---------------------------------------------------------------- 限频


class DomainLimiter:
    """按域名串行限频：同一 host 两次请求之间至少间隔 min_interval 秒。

    用 asyncio.Lock 保证并发任务不会同时穿透，等待时不阻塞事件循环。
    """

    def __init__(self, min_interval: float) -> None:
        self._min_interval = max(0.0, min_interval)
        self._last: dict[str, float] = {}
        self._locks: dict[str, asyncio.Lock] = {}

    async def acquire(self, host: str) -> None:
        if self._min_interval <= 0:
            return
        lock = self._locks.setdefault(host, asyncio.Lock())
        async with lock:
            last = self._last.get(host, 0.0)
            wait = self._min_interval - (time.monotonic() - last)
            if wait > 0:
                await asyncio.sleep(wait)
            self._last[host] = time.monotonic()
