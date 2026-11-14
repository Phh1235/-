"""回调 Java：HMAC 签名 + 重试。签名串与 Java 侧 CallbackSigner 严格一致。

Java 侧校验：HMAC_SHA256(secret, timestamp + "\\n" + nonce + "\\n" + rawBody)，时间戳 ±5 分钟，
nonce 唯一索引防重放。这里必须用"发给对方的那份字节"参与签名，所以序列化只做一次。
"""

from __future__ import annotations

import asyncio
import hashlib
import hmac
import logging
import time
from uuid import uuid4

import httpx

from app.config import Settings
from app.schemas import CallbackEvent, dumps

logger = logging.getLogger(__name__)


def sign(secret: str, timestamp: str, nonce: str, body: str) -> str:
    message = f"{timestamp}\n{nonce}\n{body}".encode("utf-8")
    return hmac.new(secret.encode("utf-8"), message, hashlib.sha256).hexdigest()


def build_headers(secret: str, body: str) -> dict[str, str]:
    ts = str(int(time.time()))
    nonce = uuid4().hex
    return {
        "Content-Type": "application/json; charset=utf-8",
        "X-Agent-Timestamp": ts,
        "X-Agent-Nonce": nonce,
        "X-Agent-Signature": sign(secret, ts, nonce, body),
    }


class CallbackClient:
    def __init__(self, settings: Settings, client: httpx.AsyncClient) -> None:
        self._settings = settings
        self._client = client

    async def send(self, event: CallbackEvent, url: str | None = None) -> bool:
        body = dumps(event)
        target = url or self._settings.callback_url
        delays = (0.5, 1.5, 3.0)
        for attempt, delay in enumerate((0.0, *delays)):
            if delay:
                await asyncio.sleep(delay)
            try:
                resp = await self._client.post(
                    target,
                    content=body.encode("utf-8"),
                    headers=build_headers(self._settings.callback_secret, body),
                    timeout=10.0,
                )
            except httpx.HTTPError as exc:
                logger.warning("回调网络错误（第 %d 次）：%s", attempt + 1, type(exc).__name__)
                continue
            if resp.status_code < 400:
                return True
            # 4xx 是契约/鉴权问题，重试无意义；429 与 5xx 才值得重试
            if resp.status_code != 429 and resp.status_code < 500:
                logger.error("回调被拒绝：%s %s", resp.status_code, resp.text[:200])
                return False
            logger.warning("回调返回 %s（第 %d 次），继续重试", resp.status_code, attempt + 1)
        return False
