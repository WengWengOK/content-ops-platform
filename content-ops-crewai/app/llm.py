"""极简 OpenAI 兼容对话客户端（不引入 SDK，方便容器与本地共用一个实现）。"""

from __future__ import annotations

import logging
from typing import List, Optional

import requests

from .config import Config

log = logging.getLogger(__name__)


def chat(cfg: Config, system: str, user: str, temperature: Optional[float] = None,
         max_tokens: Optional[int] = None) -> str:
    """调用 /chat/completions，返回纯文本内容。

    推理型模型（返回 `reasoning_content`）常常把预算全花在思考上、`content` 为空且
    `finish_reason=length`。这里做了两层兜底：放宽 max_tokens（默认 4096），
    以及 content 为空时回落到 reasoning_content，避免整条 crew 链路静默产出空结果。
    """

    if not cfg.llm_ready():
        raise RuntimeError("LLM 未配置：需要 LLM_BASE_URL / LLM_API_KEY / LLM_MODEL")
    messages: List[dict] = []
    if system:
        messages.append({"role": "system", "content": system})
    messages.append({"role": "user", "content": user})
    response = requests.post(
        f"{cfg.llm_base_url}/chat/completions",
        headers={"Authorization": f"Bearer {cfg.llm_api_key}", "Content-Type": "application/json"},
        json={
            "model": cfg.llm_model,
            "messages": messages,
            "temperature": cfg.llm_temperature if temperature is None else temperature,
            "max_tokens": max_tokens or cfg.llm_max_tokens,
        },
        timeout=cfg.request_timeout,
    )
    if response.status_code >= 400:
        raise RuntimeError(f"LLM 调用失败：HTTP {response.status_code} {response.text[:200]}")
    body = response.json()
    choices = body.get("choices") or []
    if not choices:
        raise RuntimeError(f"LLM 返回没有 choices：{str(body)[:200]}")
    choice = choices[0]
    message = choice.get("message") or {}
    content = (message.get("content") or "").strip()
    if content:
        return content
    reasoning = (message.get("reasoning_content") or "").strip()
    if reasoning:
        log.warning("模型未给出正文（finish_reason=%s），回落使用 reasoning_content（%d 字）",
                    choice.get("finish_reason"), len(reasoning))
        return reasoning
    log.warning("模型返回空内容：finish_reason=%s usage=%s", choice.get("finish_reason"), body.get("usage"))
    return ""
