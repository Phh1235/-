"""FastAPI 入口：/v1/tasks 系列接口 + 健康检查。

职责边界：这里只做"接单 → 起后台任务 → 立刻返回"，不做任何阻塞式抓取；
真正的状态机、持久化、鉴权、SSE 全在 Java 侧。
"""

from __future__ import annotations

import hmac
import logging
from contextlib import asynccontextmanager

import httpx
from fastapi import Depends, FastAPI, Header, HTTPException, status
from fastapi.responses import JSONResponse

from app.config import get_settings
from app.llm import LlmClient
from app.pipeline import extract_quote
from app.schemas import AgentSubmitRequest, InspectRequest, jsonable
from app.security import DomainLimiter, SecurityError, UrlGuard, sanitize_untrusted
from app.tasks import TaskManager
from app.tools import html_to_text

logging.basicConfig(
    level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s - %(message)s"
)
logger = logging.getLogger("agent")

settings = get_settings()


@asynccontextmanager
async def lifespan(app: FastAPI):
    http = httpx.AsyncClient(follow_redirects=False, limits=httpx.Limits(max_connections=20))
    llm = LlmClient(settings)
    manager = TaskManager(
        settings,
        http,
        llm,
        UrlGuard(settings.allowed_domains),
        DomainLimiter(settings.domain_min_interval),
    )
    app.state.http = http
    app.state.llm = llm
    app.state.manager = manager
    logger.info(
        "Agent 启动：mock=%s llm=%s 白名单=%s",
        settings.mock_mode,
        "on" if settings.llm_active else "off(规则引擎兜底)",
        ",".join(settings.allowed_domains),
    )
    try:
        yield
    finally:
        await manager.shutdown()
        await http.aclose()


app = FastAPI(
    title="Procure Agent (Python)",
    version="1.0.0",
    description="采购比价与供应商尽调 Agent：需求解析 / 多源取证 / 清洗归一化 / 打分 / 报告",
    lifespan=lifespan,
)


def require_internal_token(x_internal_token: str | None = Header(default=None)) -> None:
    """只让 Java 侧调用。共享密钥走环境变量，不进代码库。"""
    if not hmac.compare_digest(x_internal_token or "", settings.internal_token):
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="invalid internal token")


@app.get("/health")
async def health() -> dict:
    llm: LlmClient = app.state.llm
    return {
        "status": "UP",
        "mockMode": settings.mock_mode,
        "llmActive": llm.active,
        "llmModel": settings.llm_model,
        "llmBaseUrl": settings.llm_base_url,
        "llmKeyless": settings.llm_allow_keyless and not settings.llm_api_key,
        "llmCalls": llm.calls,
        "llmLastError": llm.last_error,
        "allowedDomains": list(settings.allowed_domains),
        "maxPages": settings.max_pages,
    }


@app.post("/v1/tasks", status_code=status.HTTP_202_ACCEPTED, dependencies=[Depends(require_internal_token)])
async def submit_task(req: AgentSubmitRequest) -> dict:
    manager: TaskManager = app.state.manager
    record = await manager.submit(req)
    logger.info("接收任务 task=%s external=%s mock=%s", req.taskId, record.external_id[:8], req.mockMode)
    return {"taskId": record.external_id, "status": record.status}


@app.get("/v1/tasks/{external_id}", dependencies=[Depends(require_internal_token)])
async def get_task(external_id: str) -> dict:
    manager: TaskManager = app.state.manager
    record = manager.get(external_id)
    if record is None:
        raise HTTPException(status_code=404, detail="task not found")
    return record.view()


@app.delete("/v1/tasks/{external_id}", dependencies=[Depends(require_internal_token)])
async def cancel_task(external_id: str) -> dict:
    manager: TaskManager = app.state.manager
    ok = await manager.cancel(external_id)
    if not ok:
        raise HTTPException(status_code=404, detail="task not found")
    return {"taskId": external_id, "status": "CANCELED"}


@app.post("/v1/inspect", dependencies=[Depends(require_internal_token)])
async def inspect(req: InspectRequest) -> dict:
    """单页真实取证：给一个白名单内的 URL，真抓、真清洗、真抽取，返回字段与来源。

    只读、不落库、不留缓存；抓不到价格就如实返回 ok=false，绝不编造。
    """
    manager: TaskManager = app.state.manager
    llm: LlmClient = app.state.llm
    result = await manager.fetcher.fetch(req.url)
    if not result.ok:
        return {
            "ok": False,
            "url": req.url,
            "fetchedAt": None,
            "method": "none",
            "reason": result.note or "抓取失败",
            "fields": None,
        }

    text = html_to_text(result.text)
    clean = sanitize_untrusted(text)
    ctx = {
        "category": req.category or "未填写",
        "spec": req.spec or "",
        "unit": req.unit or "件",
    }
    outcome = await extract_quote(clean.text, ctx, llm, None)
    return {
        "ok": outcome.fields is not None,
        "url": req.url,
        "fetchedAt": jsonable(result.fetched_at),
        "method": outcome.method,
        "reason": outcome.rejected_reason,
        "fields": jsonable(outcome.fields) if outcome.fields else None,
        "textLength": len(text),
        "suspiciousFlags": clean.flags,
        "llmUsed": llm.used,
        "llmError": llm.last_error,
    }


@app.exception_handler(Exception)
async def unhandled(_request, exc: Exception) -> JSONResponse:
    logger.exception("未捕获异常")
    return JSONResponse(status_code=500, content={"detail": f"{type(exc).__name__}: {str(exc)[:200]}"})
