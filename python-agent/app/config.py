"""运行配置：全部来自环境变量 / .env，代码里不出现任何密钥。"""

from __future__ import annotations

import os
from dataclasses import dataclass
from functools import lru_cache
from pathlib import Path

BASE_DIR = Path(__file__).resolve().parent.parent
ENV_FILE = BASE_DIR / ".env"


def _load_env_file(path: Path) -> None:
    """.env 只做最简单的 KEY=VALUE 解析，避免为此引入 python-dotenv 依赖。

    已存在的系统环境变量优先，不被文件覆盖。
    """
    if not path.exists():
        return
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        key = key.strip()
        value = value.strip().strip('"').strip("'")
        if key and key not in os.environ:
            os.environ[key] = value


def _bool(name: str, default: bool) -> bool:
    raw = os.environ.get(name)
    if raw is None or raw == "":
        return default
    return raw.strip().lower() in {"1", "true", "yes", "on"}


def _int(name: str, default: int) -> int:
    try:
        return int(os.environ.get(name, default))
    except (TypeError, ValueError):
        return default


def _float(name: str, default: float) -> float:
    try:
        return float(os.environ.get(name, default))
    except (TypeError, ValueError):
        return default


@dataclass(frozen=True)
class Settings:
    port: int
    mock_mode: bool
    stage_delay_ms: int

    java_base_url: str
    callback_url: str
    callback_secret: str
    internal_token: str

    allowed_domains: tuple[str, ...]
    fetch_timeout: float
    fetch_max_bytes: int
    domain_min_interval: float
    max_pages: int

    llm_enabled: bool
    llm_api_key: str
    llm_base_url: str
    llm_model: str
    llm_timeout: float

    @property
    def llm_active(self) -> bool:
        return self.llm_enabled and bool(self.llm_api_key)


@lru_cache(maxsize=1)
def get_settings() -> Settings:
    _load_env_file(ENV_FILE)
    domains = tuple(
        d.strip().lower()
        for d in os.environ.get(
            "FETCH_ALLOWED_DOMAINS", "example.com,www.example.com,open.example.org"
        ).split(",")
        if d.strip()
    )
    return Settings(
        port=_int("AGENT_PORT", 8000),
        mock_mode=_bool("AGENT_MOCK_MODE", True),
        stage_delay_ms=_int("AGENT_STAGE_DELAY_MS", 350),
        java_base_url=os.environ.get("JAVA_BASE_URL", "http://127.0.0.1:8080").rstrip("/"),
        callback_url=os.environ.get(
            "AGENT_CALLBACK_URL", "http://127.0.0.1:8080/internal/agent/callback"
        ),
        callback_secret=os.environ.get("AGENT_CALLBACK_SECRET", "callback-secret-demo"),
        internal_token=os.environ.get("AGENT_INTERNAL_TOKEN", "internal-token-demo"),
        allowed_domains=domains,
        fetch_timeout=_float("FETCH_TIMEOUT_SECONDS", 8.0),
        fetch_max_bytes=_int("FETCH_MAX_BYTES", 512 * 1024),
        domain_min_interval=_float("FETCH_DOMAIN_MIN_INTERVAL", 1.0),
        max_pages=_int("FETCH_MAX_PAGES", 20),
        llm_enabled=_bool("LLM_ENABLED", True),
        llm_api_key=os.environ.get("LLM_API_KEY", "").strip(),
        llm_base_url=os.environ.get("LLM_BASE_URL", "https://api.deepseek.com").rstrip("/"),
        llm_model=os.environ.get("LLM_MODEL", "deepseek-chat"),
        llm_timeout=_float("LLM_TIMEOUT_SECONDS", 20.0),
    )
