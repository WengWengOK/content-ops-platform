# CrewAI 接入（研究 crew = 流水线的"研究外挂"）

> 定位：**CrewAI 负责探索型多角色协作，平台的确定性编排内核不动**。
> crew 不直连数据库，只通过平台 `/api/**` 用工具；产出由平台回写知识库，进入检索与评测面。

---

## 一、架构

```
   前端 / 定时任务
        │  POST /api/v1/external-agents/research/run
        ▼
┌───────────────────────────┐        HTTP         ┌──────────────────────────────┐
│ content-ops-server        │ ──────────────────▶ │ content-ops-crewai（Python）  │
│ ExternalAgentController   │  /research          │ Crew: 研究员→策划→事实核查     │
│  · 统一入口（鉴权/审计）   │ ◀────────────────── │  · crewai 或 lite 执行器      │
│  · 回写知识库（RAG/评测）  │   reportMarkdown    │  · 工具：HTTP 调平台接口       │
└───────────────────────────┘                     └──────────────────────────────┘
        │                                                      │
        │ 写知识库 / 审计 / 评测用例                             │ 服务账号 JWT
        ▼                                                      ▼
                          PostgreSQL（租户隔离、RBAC、审计、成本）
```

关键约束：

1. **crew 无数据库凭据**，只有服务账号 JWT，走 `/api/**`（租户隔离、RBAC、限流、审计、成本统计都由平台统管）；
2. **平台侧发起 + 平台侧回写**，调用点显式、可计费、可熔断；
3. **两条执行器同构**（`crewai` / `lite`），工具与输出结构完全一致，便于用同一评测集做 A/B。

---

## 二、目录

```
content-ops-crewai/
  app/config.py        环境变量配置（平台地址 / 服务账号 / LLM / 执行器）
  app/client.py        平台 HTTP 客户端（登录、token 重试、响应解包、工具封装）
  app/tools.py         纯函数：证据格式化 / JSON 容错解析 / 结果规整 / 报告拼装
  app/llm.py           OpenAI 兼容对话客户端（含推理模型空正文兜底）
  app/lite_executor.py 精简执行器（三角色顺序执行，无 crewai 也能跑）
  app/crew.py          CrewAI 执行器（Agent/Task/Crew + BaseTool 工具）
  app/server.py        FastAPI：/health /crew-info /research
  tests/test_tools.py  15 个纯函数单测
  Dockerfile           python:3.12-slim（crewai 要求 Python >= 3.10）
```

---

## 三、运行

| 方式 | 命令 | 适用 |
|------|------|------|
| 容器（真 crewai） | `docker build -t contentops-crewai:1.0 .` 然后 `docker run -p 8000:8000 --env-file .env contentops-crewai:1.0` | 生产 / 有 Docker |
| 本机 Python 3.10+ | `pip install -r requirements.txt` 然后 `uvicorn app.server:app --port 8000` | 开发机 |
| 本机 Python 3.9（降级） | `CREW_EXECUTOR=lite python -m uvicorn app.server:app --port 8000` | 本机装不了 crewai 时的验证路径 |

平台侧开关（`application.yml` / 环境变量）：

```yaml
contentops:
  external-agents:
    enabled: true
    crewai:
      enabled: ${CONTENTOPS_CREWAI_ENABLED:false}     # 先起 crew 服务再打开
      base-url: ${CONTENTOPS_CREWAI_BASE_URL:http://localhost:8000}
      timeout-ms: 240000                              # 多角色协作耗时较长
      write-back-to-kb: true                          # 产出回写知识库
```

---

## 四、接口

| 侧 | 方法 | 路径 | 说明 |
|----|------|------|------|
| 平台 | GET | `/api/v1/external-agents/status` | 开关 + crew 探活（crewai 是否可用、执行器、LLM 是否就绪） |
| 平台 | POST | `/api/v1/external-agents/research/run` | 触发研究 → 回写知识库 → 记审计 |
| crew | GET | `/health`、`/crew-info` | 健康与角色/工具清单 |
| crew | POST | `/research` | `{topic, niche?, platforms?, maxTopics?, executor?}` |

`/research/run` 返回：`hotAngles`、`topicCandidates`、`factCheck`、`evidenceDigest`、
`reportMarkdown`、`ingest{documentId,chunkCount,success}`、`latencyMs`。

---

## 五、端到端实测（本机，真实 LLM + 真实平台工具）

请求：`{"topic":"AI 提效工具","niche":"效率工具","platforms":["XHS","WECHAT"],"maxTopics":3}`

| 观测项 | 结果 |
|--------|------|
| 执行器 / 耗时 | `executor=lite`，`latencyMs=40438` |
| 热点角度 | **5 条**，每条带依据与来源链接（其中 2 条如实标注"无直接热点，属常青选题"） |
| 选题候选 | **3 条**，具体可写、按平台区分（如"AI 脸看多了烧心？我用 3 步去 AI 味润色法…"） |
| 事实核查 | `passed=false`，拦下三类问题：无来源的量化效果承诺（"10 分钟筛出 3 个"）、类比外推（把"砸金蛋骗局"直接迁移到 AI 付费课）、无权威来源 + 时效衰减风险 |
| 回写知识库 | `success=true`，5 个分块；`GET /rag/search?query=AI 提效 选题 研究报告` 命中 `type=research_report`，score 1.59 |
| 审计 | `EXTERNAL_AGENT_RESEARCH`，detail=「executor=lite，选题 3 个，回写知识库=true」，带 traceId |

> 第三个角色（事实核查）不是摆设：它把"听起来很爆但站不住"的选题当场标了出来 —— 这正是研究 crew 放进流水线前最需要的闸门。

---

## 六、踩坑记录（都已修，有兜底）

1. **推理型模型返回空正文**：`finish_reason=length`、`content=""`、3434 字的思考全在 `reasoning_content` 里 ——
   token 被思考吃光。修复：`max_tokens` 提到 4096（`LLM_MAX_TOKENS`）、提示词要求"只输出 JSON，不要思考过程"、
   `content` 为空时回落 `reasoning_content`；再加一层"解析不出 JSON 就换紧凑提示重试一次"。
2. **全局热榜不等于垂类热点**：`/trends` 返回的是全站热搜（泰国王后、砸金蛋…），跟"AI 提效工具"无关，
   研究员无角度可写。修复：新增 `search_trends`（`/trends/search?q=`）按主题搜热榜，并在提示里要求
   "热榜无关时按常青选题处理并写明"。
3. **全网搜索未配置**：Tavily Key 缺省时 `/trends/web-search` 返回空，工具降级为"（无全网搜索结果）"，
   链路不中断；配好 Key 自动生效。
4. **本机 Python 3.9 装不了 crewai**（要求 >=3.10）：用容器镜像解决；同时保留 `lite` 执行器，保证
   "没有 crewai 也能验证整条链路"，切到真 crewai 只改一个环境变量。

---

## 七、安全与治理

- crew 用**服务账号**（`crew_service`）登录，JWT 只存在 crew 进程内存，不进数据库、不打印；
- 全部调用经平台 `/api/**`：租户隔离、RBAC、限流、审计、成本统计一套不落；
- 产出**由平台回写**知识库，crew 无写库权限；回写失败只记日志并返回 `ingest.success=false`，不影响研究结果；
- 单次研究超时 240s（可配），客户端异常统一转 `BusinessException`（502 语义），不把连接错误 500 抛给前端。

---

## 八、下一步

1. 前端加「AI 研究」入口（输入主题 → 展示角度/选题/核查标记 → 一键"采用为选题"）；
2. 把研究结果接进工作流的**选题节点**（作为选题候选来源之一，仍由用户确认）；
3. 起 Docker 后跑一次**真 crewai**，用同一评测集与 `lite` 做质量/成本对比；
4. 给 crew 增加 `kb_ask`（带引用的知识库问答）作为第五个工具，让策划阶段能引用内部资料。
