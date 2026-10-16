"""纯函数：把平台返回的数据整理成证据文本、把模型输出规整成结构化结果、拼装报告。

刻意不依赖 crewai / 网络，方便单测（Python 3.9 也能跑）。
"""

from __future__ import annotations

import json
import re
from typing import Any, Dict, List, Optional

_FENCE = re.compile(r"```(?:json)?\s*(.*?)```", re.DOTALL)


def pick(item: Dict[str, Any], *keys: str, default: str = "") -> str:
    """按候选键名取值（不同接口字段名不完全一致）。"""

    for key in keys:
        value = item.get(key)
        if value is None:
            continue
        if isinstance(value, (list, tuple)):
            value = "、".join(str(v) for v in value)
        text = str(value).strip()
        if text:
            return text
    return default


def format_trend_items(items: List[Dict[str, Any]], limit: int = 10) -> str:
    """热榜条目 → 证据文本。"""

    if not items:
        return "（无热榜数据：检查平台热点数据源是否可用）"
    lines: List[str] = []
    for index, item in enumerate(items[:limit], start=1):
        title = pick(item, "title", "name", "word", "keyword", default="(无标题)")
        platform = pick(item, "platform", "source", default="-")
        rank = pick(item, "rank", "position", default=str(index))
        heat = pick(item, "hotValue", "heat", "score", "hot", default="-")
        url = pick(item, "url", "link", "mobileUrl", default="")
        line = f"{index}. [{platform}] {title}（排名 {rank}，热度 {heat}）"
        if url:
            line += f" 来源：{url}"
        lines.append(line)
    return "\n".join(lines)


def format_web_hits(hits: List[Dict[str, Any]], limit: int = 5) -> str:
    """全网搜索命中 → 证据文本。"""

    if not hits:
        return "（无全网搜索结果：可能未配置搜索 Key）"
    lines: List[str] = []
    for index, hit in enumerate(hits[:limit], start=1):
        title = pick(hit, "title", "name", default="(无标题)")
        url = pick(hit, "url", "link", default="-")
        snippet = pick(hit, "content", "snippet", "description", "text", default="")
        line = f"{index}. {title} —— {url}"
        if snippet:
            line += f"\n   摘要：{snippet[:300]}"
        lines.append(line)
    return "\n".join(lines)


def format_kb_hits(hits: List[Dict[str, Any]], limit: int = 5) -> str:
    """知识库检索命中 → 证据文本（含相似度与出处标题）。"""

    if not hits:
        return "（知识库无相关历史内容）"
    lines: List[str] = []
    for index, hit in enumerate(hits[:limit], start=1):
        content = pick(hit, "content", "text", default="")
        score = pick(hit, "score", default="-")
        metadata = hit.get("metadata") if isinstance(hit.get("metadata"), dict) else {}
        title = pick(metadata, "title", "source", default="未命名")
        chunk_id = pick(hit, "chunkId", "chunk_id", default="-")
        lines.append(f"{index}. 《{title}》（相似度 {score}，chunk {chunk_id}）\n   {content[:400]}")
    return "\n".join(lines)


def parse_json_block(text: Optional[str]) -> Dict[str, Any]:
    """从模型输出里尽力解析 JSON（容忍 ```json 围栏与前后废话）。"""

    if not text:
        return {}
    fenced = _FENCE.search(text)
    candidate = fenced.group(1) if fenced else text
    candidate = candidate.strip()
    try:
        parsed = json.loads(candidate)
        return parsed if isinstance(parsed, dict) else {}
    except json.JSONDecodeError:
        pass
    start = candidate.find("{")
    end = candidate.rfind("}")
    if start >= 0 and end > start:
        try:
            parsed = json.loads(candidate[start:end + 1])
            return parsed if isinstance(parsed, dict) else {}
        except json.JSONDecodeError:
            return {}
    return {}


def normalize_angles(raw: Any, limit: int = 5) -> List[Dict[str, str]]:
    """规整「热点角度」列表。"""

    items = raw if isinstance(raw, list) else raw.get("angles") if isinstance(raw, dict) else []
    normalized: List[Dict[str, str]] = []
    for item in items or []:
        if isinstance(item, str):
            normalized.append({"angle": item, "evidence": "", "source": ""})
        elif isinstance(item, dict):
            normalized.append({
                "angle": pick(item, "angle", "角度", "title", "角度名称"),
                "evidence": pick(item, "evidence", "依据", "reason", "data"),
                "source": pick(item, "source", "url", "来源", "link"),
            })
        if len(normalized) >= limit:
            break
    return [item for item in normalized if item["angle"]]


def normalize_topics(raw: Any, limit: int = 3) -> List[Dict[str, Any]]:
    """规整「选题候选」列表。"""

    items = raw if isinstance(raw, list) else raw.get("topics") if isinstance(raw, dict) else []
    normalized: List[Dict[str, Any]] = []
    for item in items or []:
        if isinstance(item, str):
            normalized.append({"title": item, "angle": "", "platforms": [], "reason": "", "sources": []})
        elif isinstance(item, dict):
            platforms = item.get("platforms") or item.get("平台") or []
            if isinstance(platforms, str):
                platforms = [p.strip() for p in re.split(r"[,，、;；/\s]+", platforms) if p.strip()]
            sources = item.get("sources") or item.get("来源") or []
            if isinstance(sources, str):
                sources = [s.strip() for s in re.split(r"[,，、;；\s]+", sources) if s.strip()]
            normalized.append({
                "title": pick(item, "title", "标题", "name"),
                "angle": pick(item, "angle", "角度", "切入角度", "summary"),
                "platforms": [str(p) for p in platforms][:4],
                "reason": pick(item, "reason", "推荐理由", "why", "rationale"),
                "sources": [str(s) for s in sources][:5],
            })
        if len(normalized) >= limit:
            break
    return [item for item in normalized if item["title"]]


def normalize_fact_check(raw: Any) -> Dict[str, Any]:
    """规整「事实核查」结果。"""

    if not isinstance(raw, dict):
        return {"passed": True, "flags": [], "note": "模型未返回结构化核查结果，按通过处理"}
    flags = raw.get("flags") or raw.get("标记") or []
    if isinstance(flags, str):
        flags = [f.strip() for f in re.split(r"[,，、;；\n]+", flags) if f.strip()]
    passed = raw.get("passed")
    if passed is None:
        passed = not flags
    return {
        "passed": bool(passed),
        "flags": [str(flag) for flag in flags][:10],
        "note": pick(raw, "note", "结论", "comment", "说明"),
    }


def build_report_markdown(topic: str, niche: str, executor: str, hot_angles: List[Dict[str, str]],
                          topics: List[Dict[str, Any]], fact_check: Dict[str, Any],
                          generated_at: str) -> str:
    """把结构化结果拼成可回写知识库的调研报告（Markdown）。"""

    lines: List[str] = [f"# 选题研究报告：{topic}", ""]
    lines.append(f"- 账号定位/领域：{niche or '未指定'}")
    lines.append(f"- 生成方式：CrewAI 研究 crew（executor={executor}）")
    lines.append(f"- 生成时间：{generated_at}")
    lines.append("")
    lines.append("## 一、热点证据与可蹭角度")
    if hot_angles:
        for index, item in enumerate(hot_angles, start=1):
            lines.append(f"{index}. **{item['angle']}**")
            if item.get("evidence"):
                lines.append(f"   - 依据：{item['evidence']}")
            if item.get("source"):
                lines.append(f"   - 来源：{item['source']}")
    else:
        lines.append("（本次未获得有效热点角度）")
    lines.append("")
    lines.append("## 二、推荐选题")
    if topics:
        for index, item in enumerate(topics, start=1):
            lines.append(f"### {index}. {item['title']}")
            if item.get("angle"):
                lines.append(f"- 切入角度：{item['angle']}")
            if item.get("platforms"):
                lines.append(f"- 目标平台：{'、'.join(item['platforms'])}")
            if item.get("reason"):
                lines.append(f"- 推荐理由：{item['reason']}")
            if item.get("sources"):
                lines.append(f"- 参考来源：{'；'.join(item['sources'])}")
            lines.append("")
    else:
        lines.append("（本次未产出有效选题）")
        lines.append("")
    lines.append("## 三、事实核查与风险标记")
    lines.append(f"- 结论：{'通过' if fact_check.get('passed') else '存在风险，需人工确认'}")
    if fact_check.get("flags"):
        for flag in fact_check["flags"]:
            lines.append(f"  - ⚠️ {flag}")
    if fact_check.get("note"):
        lines.append(f"- 说明：{fact_check['note']}")
    lines.append("")
    lines.append("> 本报告由 CrewAI 研究 crew 自动生成，**只作为选题参考**；")
    lines.append("> 落地前请人工确认热点时效性与事实准确性。")
    return "\n".join(lines)
