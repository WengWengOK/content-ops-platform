# ContentOps × CrewAI 研究服务

把一个 **CrewAI 多角色研究 crew** 接到 ContentOps 平台上，作为流水线的「研究外挂」：
crew 通过 HTTP 调平台的工具（热榜 / 全网搜索 / 知识库检索），产出选题研究报告，
由平台回写知识库，进入 RAG 检索与评测面。

> 定位说明见 [docs/PROJECT-STAR.md](../docs/PROJECT-STAR.md) 第五节：
> CrewAI 做研究外挂与离线实验台，**不替代**主链路的确定性编排内核。

## 角色与流程（sequential）

| 角色 | 工具 | 产出 |
|------|------|------|
| 热点研究员 | `search_trends`（按主题搜热榜）、`hot_trends`、`web_search` | 3-5 个可蹭角度 + 依据 + 来源链接 |
| 选题策划 | `kb_search` | N 个选题候选（标题/角度/目标平台/理由/来源），避开历史内容 |
| 事实核查与合规审查员 | — | 风险标记（无来源数据、绝对化承诺、时效风险） |

## 运行

### 方式一：容器（推荐，Python 3.12 + crewai）

```bash
cd content-ops-crewai
cp .env.example .env      # 填 CONTENTOPS_* 与 LLM_*
docker build -t contentops-crewai:1.0 .
docker run --rm -p 8000:8000 --env-file .env contentops-crewai:1.0
```

Windows 下容器访问宿主机后端用 `host.docker.internal`（`.env.example` 已给出）。

### 方式二：本机 Python（>= 3.10）

```bash
pip install -r requirements.txt
set CREW_EXECUTOR=crewai     # Windows；Linux/macOS 用 export
uvicorn app.server:app --port 8000
```

### 方式三：**没有 crewai 也能跑**（本机 Python 3.9 等场景）

`CREW_EXECUTOR=lite`（或默认 `auto` 且未装 crewai）时会走内置的精简执行器：
同样的三个角色、同样的平台工具、同样的输出结构，只是用顺序 LLM 调用代替 crew 的委派过程。
这条路径用来**验证链路与对比成本**，切到 crewai 只需装依赖并设 `CREW_EXECUTOR=crewai`。

## 接口

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/health` | crewai 是否可用、执行器、LLM 是否就绪 |
| GET | `/crew-info` | 角色、工具、过程类型 |
| POST | `/research` | `{topic, niche?, platforms?, maxTopics?, executor?}` → 报告 + 选题候选 |

返回结构：

```json
{
  "topic": "...", "niche": "...", "executor": "crewai|lite",
  "hotAngles": [{"angle": "...", "evidence": "...", "source": "..."}],
  "topicCandidates": [{"title": "...", "angle": "...", "platforms": ["XHS"], "reason": "...", "sources": ["..."]}],
  "factCheck": {"passed": false, "flags": ["含绝对化用语"], "note": "..."},
  "evidenceDigest": {"trends": "...", "web": "...", "kb": "..."},
  "reportMarkdown": "# 选题研究报告：..."
}
```

## 平台侧调用

后端 `ExternalAgentController` 提供统一入口（平台侧发起、平台侧回写，便于审计与限流）：

```bash
# 触发一次研究，并把 reportMarkdown 回写知识库
curl -X POST http://localhost:8080/api/v1/external-agents/research/run \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"topic":"AI 提效工具","niche":"效率工具","platforms":["XHS","WECHAT"]}'

# 查看 crew 服务状态（含 crewai 是否可用）
curl -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/v1/external-agents/status
```

## 单测

```bash
cd content-ops-crewai
python -m unittest discover -s tests -v
```

覆盖：平台返回体解包、证据格式化、模型 JSON 容错解析、结构化结果规整、报告拼装。

## 设计约束（为什么这么做）

1. **crew 不直连数据库**：所有数据经平台 `/api/**` 接口，租户隔离、RBAC、限流、审计、成本统计不重做；
2. **平台侧发起与回写**：调用点显式、可计费、可熔断，产出自动进知识库与评测面；
3. **两条执行器同构**：`crewai` 与 `lite` 共用工具与输出结构，方便用同一评测集做 A/B；
4. **工具失败不致命**：单个工具报错降级为空证据并记日志，研究链路继续走完。
