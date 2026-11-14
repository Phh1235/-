"""回调签名与 Java 侧算法的一致性测试。"""

import hashlib
import hmac

from app.callback import build_headers, sign
from app.schemas import CallbackEvent, CallbackPayload, dumps


def test_signature_matches_java_algorithm():
    secret = "callback-secret-demo"
    body = '{"eventId":"abc"}'
    ts, nonce = "1763000000", "n0nce"
    expected = hmac.new(
        secret.encode(), f"{ts}\n{nonce}\n{body}".encode(), hashlib.sha256
    ).hexdigest()
    assert sign(secret, ts, nonce, body) == expected


def test_headers_carry_all_three_parts():
    headers = build_headers("s", "{}")
    assert {"X-Agent-Timestamp", "X-Agent-Nonce", "X-Agent-Signature"} <= set(headers)
    assert len(headers["X-Agent-Signature"]) == 64


def test_event_serialisation_uses_camel_case_and_no_none():
    event = CallbackEvent(eventId="e1", taskId=7, eventType="STAGE", stage="FETCH", progress=45, message="抓取")
    body = dumps(event)
    assert '"eventId":"e1"' in body
    assert '"taskId":7' in body.replace(" ", "")
    assert "null" not in body
    assert "payload" not in body


def test_failed_event_payload_optional():
    event = CallbackEvent(eventId="e2", taskId=7, eventType="FAILED", message="boom")
    assert "payload" not in dumps(event)
    assert CallbackPayload().quotes == []
