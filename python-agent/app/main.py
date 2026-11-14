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
from app.schemas import AgentSubmitRequest
from app.security import DomainLimiter, UrlGuard
from app.tasks import TaskManager

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


@app.exception_handler(Exception)
async def unhandled(_request, exc: Exception) -> JSONResponse:
    logger.exception("未捕获异常")
    return JSONResponse(status_code=500, content={"detail": f"{type(exc).__name__}: {str(exc)[:200]}"})
