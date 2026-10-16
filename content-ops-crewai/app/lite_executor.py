"""精简执行器（crew-lite）：不依赖 crewai 也能跑通「研究员 → 策划 → 核查」三角色链路。

用途有两个：

1. 本机没有 Python>=3.10 / 容器时的降级路径，保证链路可验证；
2. 与 crewai 执行器共用同一套工具、同一套输出结构，便于对比两者的质量与成本。
"""

from __future__ import annotations

import logging
from datetime import datetime
from typing import Any, Dict, List, Optional

from . import llm, tools
from .client import ContentOpsClient
from .config import Config

log = logging.getLogger(__name__)

RESEARCHER_SYSTEM = (
    "你是资深热点研究员。只依据给定的热榜/搜索证据工作，不要编造热点或链接。"
    "证据不足时如实说明，并给出可执行的补充建议。只输出要求的 JSON，不要输出思考过程。"
)
PLANNER_SYSTEM = (
    "你是内容选题策划。基于热点角度与知识库里的历史内容产出选题候选，"
    "避免与历史内容重复，标题要具体可写、不要空泛口号。只输出要求的 JSON，不要输出思考过程。"
)
FACT_CHECK_SYSTEM = (
    "你是事实核查与合规审查员。逐条核对选题是否有来源支撑，"
    "指出绝对化承诺（保证/最/第一/百分百）、无来源数据、时效性风险。只输出要求的 JSON，不要输出思考过程。"
)


def gather_evidence(client: ContentOpsClient, topic: str, niche: Optional[str]) -> Dict[str, str]:
    """调平台工具取证据（失败降级为空证据，不打断流程）。"""

    evidence: Dict[str, str] = {}
    try:
        evidence["trends"] = tools.format_trend_items(client.hot_trends(limit=6), limit=6)
    except Exception as exc:  # noqa: BLE001 - 单个工具失败不该中断研究
        log.warning("热榜工具失败：%s", exc)
        evidence["trends"] = "（热榜工具调用失败）"
    try:
        matched = client.search_trends(topic, limit=10)
        evidence["trend_search"] = tools.format_trend_items(matched, limit=10)
    except Exception as exc:  # noqa: BLE001
        log.warning("热榜关键词搜索失败：%s", exc)
        evidence["trend_search"] = "（热榜关键词搜索失败）"
    try:
        evidence["web"] = tools.format_web_hits(client.web_search(topic, limit=5), limit=5)
    except Exception as exc:  # noqa: BLE001
        log.warning("全网搜索工具失败：%s", exc)
        evidence["web"] = "（全网搜索工具调用失败）"
    try:
        hits = client.kb_search(topic, top_k=5, niche=niche)
        evidence["kb"] = tools.format_kb_hits(hits, limit=5)
    except Exception as exc:  # noqa: BLE001
        log.warning("知识库检索工具失败：%s", exc)
        evidence["kb"] = "（知识库检索失败）"
    return evidence


def run_lite(cfg: Config, client: ContentOpsClient, topic: str, niche: Optional[str],
             platforms: Optional[List[str]], max_topics: int) -> Dict[str, Any]:
    """三角色顺序执行（等价于 crewai 的 sequential 过程）。"""

    evidence = gather_evidence(client, topic, niche)
    platform_text = "、".join(platforms) if platforms else "不限"

    angles_payload = ask_json(cfg, RESEARCHER_SYSTEM, (
        f"账号定位：{niche or '不限'}\n目标平台：{platform_text}\n主题：{topic}\n\n"
        f"【主题相关热榜】\n{evidence['trend_search']}\n\n【实时热榜（全局，可能与本主题无关）】\n{evidence['trends']}\n\n"
        f"【全网搜索】\n{evidence['web']}\n\n"
        "请输出 JSON：{\"angles\":[{\"angle\":\"可蹭角度\",\"evidence\":\"依据\",\"source\":\"链接或平台\"}]}，"
        "3-5 条，按可蹭价值排序，只用上面的证据。"
        "若热榜与主题无关，就基于搜索/知识库给出「可写的切入角度」，并在 evidence 里写明「无直接热点，属常青选题」。"
    ), temperature=0.5, retry_prompt=(
        f"主题：{topic}（账号定位 {niche or '不限'}）。只输出 JSON："
        "{\"angles\":[{\"angle\":\"角度\",\"evidence\":\"依据\",\"source\":\"来源\"}]}，3 条。"
    ))
    angles = tools.normalize_angles(angles_payload, limit=5)

    topics_payload = ask_json(cfg, PLANNER_SYSTEM, (
        f"账号定位：{niche or '不限'}\n目标平台：{platform_text}\n主题：{topic}\n"
        f"需要 {max_topics} 个选题候选。\n\n"
        f"【可蹭角度】\n{tools.format_trend_items([{'title': a['angle'], 'platform': a.get('source', '')} for a in angles])}\n\n"
        f"【知识库历史内容（避免重复）】\n{evidence['kb']}\n\n"
        "请输出 JSON：{\"topics\":[{\"title\":\"标题\",\"angle\":\"切入角度\","
        "\"platforms\":[\"XHS\"],\"reason\":\"推荐理由\",\"sources\":[\"来源\"]}]}。\n"
        "平台代号：XHS=小红书, WECHAT=公众号, DOUYIN=抖音, BILIBILI=B站。"
    ), temperature=0.6, retry_prompt=(
        f"主题：{topic}，目标平台 {platform_text}。只输出 JSON："
        f"{{\"topics\":[{{\"title\":\"标题\",\"angle\":\"角度\",\"platforms\":[\"XHS\"],"
        "\"reason\":\"理由\",\"sources\":[\"来源\"]}]}，"
        f"{max_topics} 条。"
    ))
    topics = tools.normalize_topics(topics_payload, limit=max_topics)

    check_payload = ask_json(cfg, FACT_CHECK_SYSTEM, (
        "待核查的选题：\n"
        + "\n".join(f"{i}. {t['title']}（角度：{t.get('angle', '')}）" for i, t in enumerate(topics, 1))
        + f"\n\n可用证据：\n{evidence['trends']}\n{evidence['web']}\n"
        "请输出 JSON：{\"passed\":true/false,\"flags\":[\"风险点\"],\"note\":\"结论说明\"}。"
    ), temperature=0.2, retry_prompt=(
        "只输出 JSON：{\"passed\":true,\"flags\":[],\"note\":\"说明\"}。"
    ))
    fact_check = tools.normalize_fact_check(check_payload)

    report = tools.build_report_markdown(
        topic=topic, niche=niche or "", executor="lite", hot_angles=angles, topics=topics,
        fact_check=fact_check, generated_at=datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
    )
    return {
        "topic": topic,
        "niche": niche or "",
        "executor": "lite",
        "hotAngles": angles,
        "topicCandidates": topics,
        "factCheck": fact_check,
        "evidenceDigest": {key: value[:600] for key, value in evidence.items()},
        "reportMarkdown": report,
    }


def ask_json(cfg: Config, system: str, user: str, temperature: float,
             retry_prompt: str) -> Dict[str, Any]:
    """请求模型并解析 JSON：解析不出来就用更紧的提示重试一次。

    推理型模型偶尔会把预算耗在思考里导致正文截断，这一步是链路的兜底，
    避免整段研究静默产出空结果。
    """

    parsed = tools.parse_json_block(llm.chat(cfg, system, user, temperature=temperature))
    if parsed:
        return parsed
    log.warning("模型首次输出无法解析为 JSON，使用紧凑提示重试一次")
    retry_raw = llm.chat(cfg, system + " 严格只输出 JSON，不要任何解释。",
                         retry_prompt, temperature=0.1, max_tokens=2048)
    return tools.parse_json_block(retry_raw)
