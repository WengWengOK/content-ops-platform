"""CrewAI 执行器：三个角色协作完成「热点研究 → 选题策划 → 事实核查」。

角色分工（CrewAI 的 role/goal/backstory + task 委派）：

* 热点研究员：调用平台热榜 / 全网搜索工具，产出可蹭角度与依据；
* 选题策划：调用知识库检索工具（避免与历史内容重复），产出选题候选；
* 事实核查与合规审查员：核对来源支撑与绝对化承诺，输出风险标记。

不满足条件时（未安装 crewai / 模型未配置）自动降级到 `lite_executor`，
两者共用同一套平台工具与同一份输出结构，便于对比质量与成本。
"""

from __future__ import annotations

import logging
from datetime import datetime
from typing import Any, Dict, List, Optional

from . import tools
from .client import ContentOpsClient
from .config import Config
from .lite_executor import gather_evidence, run_lite

log = logging.getLogger(__name__)

ROLES = [
    {"role": "热点研究员", "tools": ["search_trends", "hot_trends", "web_search"]},
    {"role": "选题策划", "tools": ["kb_search"]},
    {"role": "事实核查与合规审查员", "tools": []},
]


def crewai_available() -> bool:
    """crewai 是否可用（Python 版本/依赖满足）。"""

    try:
        import crewai  # noqa: F401
        return True
    except Exception as exc:  # noqa: BLE001
        log.info("crewai 不可用：%s", exc)
        return False


def _build_llm(cfg: Config):
    """构造 crewai 的 LLM（OpenAI 兼容端点统一走 openai/ 前缀）。"""

    from crewai import LLM

    return LLM(model=f"openai/{cfg.llm_model}", base_url=cfg.llm_base_url,
               api_key=cfg.llm_api_key, temperature=cfg.llm_temperature)


def _build_tools(client: ContentOpsClient) -> List[Any]:
    """把平台工具暴露成 crewai 工具（BaseTool + pydantic 参数校验）。"""

    from crewai.tools import BaseTool
    from pydantic import BaseModel, Field

    class TrendArgs(BaseModel):
        platform: Optional[str] = Field(default=None, description="平台代号，如 WEIBO/ZHIHU/DOUYIN/BILIBILI/BAIDU/TOUTIAO/XHS")
        limit: int = Field(default=10, description="返回条数")

    class SearchArgs(BaseModel):
        query: str = Field(description="搜索关键词")
        limit: int = Field(default=5, description="返回条数")

    class HotTrendTool(BaseTool):
        name: str = "hot_trends"
        description: str = "获取各平台实时热榜（可按平台过滤），返回带排名/热度/链接的条目"
        args_schema: type = TrendArgs

        def _run(self, platform: Optional[str] = None, limit: int = 10) -> str:
            return tools.format_trend_items(client.hot_trends(platform=platform, limit=limit), limit=limit)

    class WebSearchTool(BaseTool):
        name: str = "web_search"
        description: str = "全网搜索（新闻/网页），用于核实热点与找素材来源"
        args_schema: type = SearchArgs

        def _run(self, query: str, limit: int = 5) -> str:
            return tools.format_web_hits(client.web_search(query, limit=limit), limit=limit)

    class TrendSearchTool(BaseTool):
        name: str = "search_trends"
        description: str = "按主题关键词搜热榜（垂类主题必须用它，全局热榜通常不相关）"
        args_schema: type = SearchArgs

        def _run(self, query: str, limit: int = 10) -> str:
            return tools.format_trend_items(client.search_trends(query, limit=limit), limit=limit)

    class KbSearchTool(BaseTool):
        name: str = "kb_search"
        description: str = "检索平台知识库（历史作品/资料），用于避免选题与老内容重复"
        args_schema: type = SearchArgs

        def _run(self, query: str, limit: int = 5) -> str:
            return tools.format_kb_hits(client.kb_search(query, top_k=limit), limit=limit)

    return [HotTrendTool(), TrendSearchTool(), WebSearchTool(), KbSearchTool()]


def run_crewai(cfg: Config, client: ContentOpsClient, topic: str, niche: Optional[str],
               platforms: Optional[List[str]], max_topics: int) -> Dict[str, Any]:
    """用 CrewAI 顺序过程跑三角色协作。"""

    from crewai import Agent, Crew, Process, Task

    llm = _build_llm(cfg)
    trend_tool, trend_search_tool, web_tool, kb_tool = _build_tools(client)
    platform_text = "、".join(platforms) if platforms else "不限"

    researcher = Agent(
        role="热点研究员",
        goal=f"围绕「{topic}」找到可蹭的热点角度，并给出可核实的依据与来源",
        backstory="你每天扫全平台热榜，擅长判断一个热点值不值得蹭、该怎么蹭。",
        tools=[trend_tool, trend_search_tool, web_tool], llm=llm, allow_delegation=False, verbose=False,
    )
    planner = Agent(
        role="选题策划",
        goal=f"为「{topic}」产出 {max_topics} 个具体可写、不与历史内容重复的选题",
        backstory="你写过上千篇爆款，知道标题要具体、角度要小、平台要匹配。",
        tools=[kb_tool], llm=llm, allow_delegation=False, verbose=False,
    )
    checker = Agent(
        role="事实核查与合规审查员",
        goal="核对每条选题是否有来源支撑，标出绝对化承诺与时效性风险",
        backstory="你盯合规和事实，宁可标记风险也不放过没出处的数据。",
        tools=[], llm=llm, allow_delegation=False, verbose=False,
    )

    research_task = Task(
        description=(
            f"主题：{topic}\n账号定位：{niche or '不限'}\n目标平台：{platform_text}\n"
            "先调用 search_trends 按主题搜热榜（全局热榜 hot_trends 通常与垂类主题无关，仅作参考），"
            "再调用 web_search 补充，输出 3-5 个可蹭角度，"
            "每条必须带依据与来源链接，证据不足就如实说明。"
        ),
        expected_output='JSON：{"angles":[{"angle":"...","evidence":"...","source":"..."}]}',
        agent=researcher,
    )
    plan_task = Task(
        description=(
            f"基于热点角度，为「{topic}」产出 {max_topics} 个选题候选；"
            "先用 kb_search 检查知识库里已有内容，避免重复；"
            "平台代号用 XHS/WECHAT/DOUYIN/BILIBILI。"
        ),
        expected_output='JSON：{"topics":[{"title":"...","angle":"...","platforms":["XHS"],'
                        '"reason":"...","sources":["..."]}]}',
        agent=planner,
        context=[research_task],
    )
    check_task = Task(
        description=(
            "对每个选题做事实与合规核查：来源是否支撑、是否含绝对化承诺、时效是否可能过期。"
            "最后输出包含全部结果的完整 JSON。"
        ),
        expected_output='JSON：{"angles":[...],"topics":[...],'
                        '"factCheck":{"passed":true,"flags":[],"note":"..."}}',
        agent=checker,
        context=[research_task, plan_task],
    )

    crew = Crew(agents=[researcher, planner, checker],
                tasks=[research_task, plan_task, check_task],
                process=Process.sequential, verbose=False)
    raw = str(crew.kickoff())
    payload = tools.parse_json_block(raw)
    angles = tools.normalize_angles(payload.get("angles"), limit=5)
    topics = tools.normalize_topics(payload.get("topics"), limit=max_topics)
    fact_check = tools.normalize_fact_check(payload.get("factCheck"))
    if not topics:
        log.warning("crewai 输出无法解析出选题，退回 lite 执行器保证可用")
        return run_lite(cfg, client, topic, niche, platforms, max_topics)

    report = tools.build_report_markdown(
        topic=topic, niche=niche or "", executor="crewai", hot_angles=angles, topics=topics,
        fact_check=fact_check, generated_at=datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
    )
    evidence = gather_evidence(client, topic, niche)
    return {
        "topic": topic,
        "niche": niche or "",
        "executor": "crewai",
        "hotAngles": angles,
        "topicCandidates": topics,
        "factCheck": fact_check,
        "evidenceDigest": {key: value[:600] for key, value in evidence.items()},
        "reportMarkdown": report,
    }


def run_research(topic: str, niche: Optional[str] = None, platforms: Optional[List[str]] = None,
                 max_topics: Optional[int] = None, executor: Optional[str] = None,
                 cfg: Optional[Config] = None, client: Optional[ContentOpsClient] = None) -> Dict[str, Any]:
    """统一入口：按配置/参数选择执行器（auto → 有 crewai 用 crewai，否则 lite）。"""

    from .config import load_config

    cfg = cfg or load_config()
    client = client or ContentOpsClient(cfg)
    limit = max_topics or cfg.max_topics
    wanted = (executor or cfg.executor or "auto").lower()
    if wanted == "auto":
        wanted = "crewai" if crewai_available() else "lite"
    if wanted == "crewai":
        return run_crewai(cfg, client, topic, niche, platforms, limit)
    return run_lite(cfg, client, topic, niche, platforms, limit)
