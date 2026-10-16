"""HTTP 服务：`/health` `/crew-info` `/research`。

平台侧（content-ops-server 的 ExternalAgentController）会调 `/research`，
把返回的 `reportMarkdown` 回写知识库，形成「CrewAI 研究 → 平台数据面 → 可检索可评测」的闭环。
"""

from __future__ import annotations

import logging
from typing import List, Optional

from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field

from .client import ContentOpsError
from .config import load_config
from .crew import ROLES, crewai_available, run_research

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")
log = logging.getLogger("crewai.server")

app = FastAPI(title="ContentOps × CrewAI 研究服务", version="1.0.0")


class ResearchRequest(BaseModel):
    topic: str = Field(description="研究主题/方向，如「AI 提效工具」")
    niche: Optional[str] = Field(default=None, description="账号定位/领域")
    platforms: Optional[List[str]] = Field(default=None, description="目标平台代号列表")
    maxTopics: Optional[int] = Field(default=None, description="选题候选数量")
    executor: Optional[str] = Field(default=None, description="crewai / lite，默认按服务配置")


@app.get("/health")
def health() -> dict:
    cfg = load_config()
    return {
        "status": "ok",
        "crewaiAvailable": crewai_available(),
        "executor": cfg.executor,
        "llmReady": cfg.llm_ready(),
        "contentopsBaseUrl": cfg.contentops_base_url,
    }


@app.get("/crew-info")
def crew_info() -> dict:
    cfg = load_config()
    return {
        "roles": ROLES,
        "process": "sequential",
        "executor": cfg.executor,
        "crewaiAvailable": crewai_available(),
        "maxTopics": cfg.max_topics,
        "tools": ["hot_trends", "search_trends", "web_search", "kb_search", "kb_ask", "ingest_text"],
    }


@app.post("/research")
def research(request: ResearchRequest) -> dict:
    if not request.topic or not request.topic.strip():
        raise HTTPException(status_code=400, detail="topic 不能为空")
    try:
        result = run_research(topic=request.topic.strip(), niche=request.niche,
                              platforms=request.platforms, max_topics=request.maxTopics,
                              executor=request.executor)
        log.info("研究完成：topic=%s executor=%s topics=%d",
                 request.topic, result.get("executor"), len(result.get("topicCandidates") or []))
        return result
    except ContentOpsError as exc:
        raise HTTPException(status_code=502, detail=f"平台工具调用失败：{exc}") from exc
    except Exception as exc:  # noqa: BLE001
        log.exception("研究失败")
        raise HTTPException(status_code=500, detail=f"研究失败：{exc}") from exc


def main() -> None:
    import uvicorn

    cfg = load_config()
    uvicorn.run("app.server:app", host="0.0.0.0", port=cfg.port)


if __name__ == "__main__":
    main()
