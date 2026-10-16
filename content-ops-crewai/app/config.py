"""服务配置（全部来自环境变量，便于容器部署）。"""

from __future__ import annotations

import os
from dataclasses import dataclass
from typing import Optional


def _env(name: str, default: Optional[str] = None) -> Optional[str]:
    value = os.environ.get(name)
    if value is None:
        return default
    value = value.strip()
    return value if value else default


@dataclass
class Config:
    """运行期配置。"""

    contentops_base_url: str
    contentops_username: str
    contentops_password: str
    llm_base_url: str
    llm_api_key: str
    llm_model: str
    llm_temperature: float
    llm_max_tokens: int
    executor: str
    max_topics: int
    request_timeout: int
    port: int

    def llm_ready(self) -> bool:
        return bool(self.llm_api_key) and bool(self.llm_base_url) and bool(self.llm_model)


def load_config() -> Config:
    """从环境变量装载配置。"""

    return Config(
        contentops_base_url=(_env("CONTENTOPS_BASE_URL", "http://localhost:8080") or "").rstrip("/"),
        contentops_username=_env("CONTENTOPS_USERNAME", "crew_service") or "crew_service",
        contentops_password=_env("CONTENTOPS_PASSWORD", "") or "",
        llm_base_url=(_env("LLM_BASE_URL", "") or "").rstrip("/"),
        llm_api_key=_env("LLM_API_KEY", "") or "",
        llm_model=_env("LLM_MODEL", "gpt-4o-mini") or "gpt-4o-mini",
        llm_temperature=float(_env("LLM_TEMPERATURE", "0.4") or 0.4),
        llm_max_tokens=int(_env("LLM_MAX_TOKENS", "4096") or 4096),
        executor=(_env("CREW_EXECUTOR", "auto") or "auto").lower(),
        max_topics=int(_env("CREW_MAX_TOPICS", "3") or 3),
        request_timeout=int(_env("CREW_REQUEST_TIMEOUT", "180") or 180),
        port=int(_env("CREW_PORT", "8000") or 8000),
    )
