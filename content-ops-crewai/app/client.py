"""ContentOps 平台 HTTP 客户端：crew 通过它调用平台的工具，而不是直连数据库。

设计约束（与主项目一致）：

* 走 `/api/**` 统一入口 → 租户隔离、RBAC、限流、审计、成本统计都由平台侧统管；
* 用服务账号登录拿 JWT，token 过期自动重登一次；
* 响应统一解包 `AgentResponse{success,data,error}`，失败抛 `ContentOpsError`。
"""

from __future__ import annotations

import logging
import threading
from typing import Any, Dict, Iterable, List, Optional

import requests

from .config import Config

log = logging.getLogger(__name__)

_LIST_KEYS = ("items", "trends", "list", "records", "results", "hotspots", "hits",
              "comments", "data", "angles", "topics")


class ContentOpsError(RuntimeError):
    """平台接口调用失败。"""


def extract_items(data: Any) -> List[Dict[str, Any]]:
    """从平台返回体里尽力取出列表（不同接口的包装键不完全一致）。

    只取第一层列表，取不到就返回空列表——宁可少给模型证据，也不要抛异常打断 crew。
    """

    if data is None:
        return []
    if isinstance(data, list):
        return [item for item in data if isinstance(item, dict)]
    if isinstance(data, dict):
        for key in _LIST_KEYS:
            value = data.get(key)
            if isinstance(value, list):
                return [item for item in value if isinstance(item, dict)]
        for value in data.values():
            if isinstance(value, list) and value and isinstance(value[0], dict):
                return [item for item in value if isinstance(item, dict)]
    return []


class ContentOpsClient:
    """平台接口客户端（线程安全：token 用锁保护）。"""

    def __init__(self, cfg: Config, session: Optional[requests.Session] = None) -> None:
        self._cfg = cfg
        self._session = session or requests.Session()
        self._token: Optional[str] = None
        self._lock = threading.Lock()

    # ─────────────── 基础请求 ───────────────

    def login(self) -> str:
        """服务账号登录，返回 JWT。"""

        body = self._session.post(
            f"{self._cfg.contentops_base_url}/api/v1/auth/login",
            json={"username": self._cfg.contentops_username,
                  "password": self._cfg.contentops_password},
            timeout=self._cfg.request_timeout,
        ).json()
        data = self._unwrap(body)
        token = (data or {}).get("token") if isinstance(data, dict) else None
        if not token:
            raise ContentOpsError("登录失败：未拿到 token（检查 CONTENTOPS_USERNAME/PASSWORD）")
        with self._lock:
            self._token = token
        return token

    def _auth_header(self) -> Dict[str, str]:
        with self._lock:
            token = self._token
        if not token:
            token = self.login()
        return {"Authorization": f"Bearer {token}"}

    def _request(self, method: str, path: str, params: Optional[Dict[str, Any]] = None,
                 json_body: Optional[Dict[str, Any]] = None) -> Any:
        url = f"{self._cfg.contentops_base_url}{path}"
        for attempt in (1, 2):
            response = self._session.request(
                method, url, params=params, json=json_body,
                headers=self._auth_header(), timeout=self._cfg.request_timeout,
            )
            if response.status_code in (401, 403) and attempt == 1:
                # token 过期/无效 → 重新登录一次
                log.info("token 失效（HTTP %s），重新登录后重试 %s", response.status_code, path)
                self.login()
                continue
            if response.status_code >= 400:
                raise ContentOpsError(f"{method} {path} 失败：HTTP {response.status_code} {response.text[:200]}")
            try:
                return self._unwrap(response.json())
            except ValueError as exc:  # 非 JSON
                raise ContentOpsError(f"{method} {path} 返回非 JSON：{response.text[:200]}") from exc
        raise ContentOpsError(f"{method} {path} 重试后仍失败")

    @staticmethod
    def _unwrap(body: Any) -> Any:
        if isinstance(body, dict) and "success" in body:
            if not body.get("success"):
                raise ContentOpsError(body.get("error") or body.get("message") or "平台接口返回失败")
            return body.get("data")
        return body

    def get(self, path: str, params: Optional[Dict[str, Any]] = None) -> Any:
        return self._request("GET", path, params=params)

    def post(self, path: str, payload: Optional[Dict[str, Any]] = None) -> Any:
        return self._request("POST", path, json_body=payload)

    # ─────────────── 平台工具（crew 的"手脚"） ───────────────

    def hot_trends(self, platform: Optional[str] = None, limit: int = 10,
                   burst: bool = False) -> List[Dict[str, Any]]:
        """实时热榜（可选突发热点）。"""

        params: Dict[str, Any] = {"limit": limit}
        if platform:
            params["platform"] = platform
        if burst:
            params["burst"] = True
        return extract_items(self.get("/api/v1/trends", params))

    def keyword_hits(self, keyword: str, limit: int = 10) -> List[Dict[str, Any]]:
        """关键词命中记录（历史热度曲线用）。"""

        return extract_items(self.get("/api/v1/trends/keyword-hits",
                                      {"keyword": keyword, "limit": limit}))

    def search_trends(self, query: str, limit: int = 10) -> List[Dict[str, Any]]:
        """按主题搜热榜（垂类主题用全局热榜往往不相关，必须能搜）。"""

        return extract_items(self.get("/api/v1/trends/search", {"q": query, "limit": limit}))

    def web_search(self, query: str, limit: int = 5) -> List[Dict[str, Any]]:
        """全网搜索（Tavily，未配置时返回空）。"""

        return extract_items(self.get("/api/v1/trends/web-search", {"q": query, "limit": limit}))

    def kb_search(self, query: str, top_k: int = 5, niche: Optional[str] = None) -> List[Dict[str, Any]]:
        """知识库检索（历史作品/资料，避免与老内容重复）。"""

        params: Dict[str, Any] = {"query": query, "topK": top_k}
        if niche:
            params["niche"] = niche
        return extract_items(self.get("/api/v1/rag/search", params))

    def kb_ask(self, question: str, top_k: int = 5) -> Dict[str, Any]:
        """知识库问答（带引用与忠实度校验）。"""

        data = self.post("/api/v1/kb/qa/ask", {"question": question, "topK": top_k})
        return data if isinstance(data, dict) else {}

    def ingest_text(self, content: str, title: str, doc_type: str = "research_report",
                    niche: Optional[str] = None) -> Dict[str, Any]:
        """把产出回写知识库（进 RAG 与评测面）。"""

        payload: Dict[str, Any] = {"content": content, "title": title, "type": doc_type}
        if niche:
            payload["niche"] = niche
        data = self.post("/api/v1/rag/text", payload)
        return data if isinstance(data, dict) else {}
