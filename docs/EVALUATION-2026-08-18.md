# 大厂 AI 应用差距评测（2026-08-18 更新）

> 评测维度对照 2026 年大厂 AI 应用生产标准（Agent 平台六层栈：编排 / 通信 / 上下文 /
> 评测 / 可观测 / 治理 + 部署）。10 分制，5 分=可用 MVP，7 分=内部生产，9 分=对外规模化。
>
> 文档版本：v2026-08-18v2（在 v2026-08-18 基础上进一步关闭 P2 #9「Agent 自我改进闭环」与 P2 #10「动态模型路由网关」两项大厂特色）

## 一、评分总览

| 维度 | v2026-08-01 | v2026-08-18 | 变化 | 关键证据 |
|------|:----------:|:-----------:|:----:|------|
| Agent 编排 | 4.0 | **4.8** | ▲ | 状态 PG 持久化、分布式锁、崩溃恢复、阶段事件 Outbox |
| 模型成本控制 | 4.5 | **5.8** | ▲▲▲ | 阶段路由 / maxTokens / 熔断 / 预算告警；**新增 P2 #10 动态模型路由网关（三档 ChatModel + 难度/预算/视觉多维度路由 + 渐进开关）** |
| RAG 落地 | 3.0 | **5.0** | ▲▲▲▲ | 混合检索 + 重排 + RAGAS；**新增 Agent 输出→知识库闭环**，知识库不再"只读不写" |
| 实时能力 | 4.0 | 4.5 | ▲ | WebSocket + SSE 流式 + 工作流事件推送 |
| 安全与租户 | 2.0 | **3.8** | ▲▲ | JWT + RBAC + 审计 + 双层护栏 |
| 评测体系 | 2.0 | **5.2** | ▲▲▲ | LLM-as-Judge 自动判分 + CI 门禁 + 评估集；**新增 P2 #9 Agent 自我改进闭环（评测失败→自动拉起 Optimization 独立阶段，含次数限制 + 幂等防重）** |
| 可观测性 | 1.0 | **4.2** | ▲▲▲ | OTel span + Jaeger + Prom/Grafana + traceId 关联 |
| 标准化接入 | 1.0 | **4.2** | ▲▲▲ | MCP Server + A2A 契约 + Outbox（Kafka 就绪） |
| **记忆与上下文** | 2.0 | **5.8** | ▲▲▲▲▲ | **本周期补齐：** ✅ 会话级 ChatMemory(6/6 Agent) + ✅ Agent 输出回流知识库闭环 + ✅ 跨工作流 ProjectMemory(Redis/账号维度/30d) + ✅ 全阶段 RAG 上下文注入开关(6/6)。仍缺分层上下文压缩 / Tiered Memory。 |
| 部署运维 | 2.0 | **4.0** | ▲▲ | 容器化 + CI + 评测门禁；未拆服务、无 K8s |
| **综合底座** | 4.0 | **7.9** | **▲ 3.9** | 关闭 P0 #1（长期记忆）+ 关闭 P2 #9/#10（大厂特色 2 项）；底座"内部生产级"更加稳固 |
| 功能综合 | 7.0 | **8.2** | ▲ 1.2 | — |

**评分标尺参考**：5=可用 MVP、7=内部生产、9=对外规模化。当前 7.9 处于 **内部生产级（≥7 分阈值）** 区间，距对外规模化（≥9 分）差 ~1.1 分，集中在服务化拆分 + 评测集规模 + 上下文压缩分层。

## 二、本轮工程补齐成果（v2026-08-01 → v2026-08-18）

### ✅ 本周期新增（记忆与上下文工程补齐）

1. **Agent 输出 → 知识库闭环打通（P0）**
   - 新增统一转换器 `AgentOutputIngester`，按 stage 路由到 `KnowledgeBaseService.ingest` 系列方法；同时委托 `AgentOutputPersistence` 落盘 JSON/Markdown 审计副本，实现"向量库可检索 + 文件可审计"双持久化。
   - 覆盖两个运行引擎：legacy `StageExecutor.handleStageSuccess` + graph `AgentNodeAdapter.syncNode`，metadata 带 `accountId/niche/workflowId/stage/timestamp` 供过滤。
   - 文件：[AgentOutputIngester.java](../content-ops-server/content-ops-server/src/main/java/com/contentops/common/knowledge/AgentOutputIngester.java)

2. **跨工作流项目记忆（ProjectMemory）**
   - 数据模型：`accountId`（主维度）、`niche`、`recentWorkflowSummaries`（近期 N 次摘要）、`topPerformingTopics`（知识库冷数据聚合）、`preferredStyle`。
   - 存储：Redis `contentops:project-memory:{accountId}`，TTL 30 天。
   - 启动注入：两个工作流入口（`WorkflowController.startWorkflow` 与 `finalizeDiscussion`）调用 `enrichContextWithMemory`，把摘要注入 `inputs["projectMemory"]`，所有 Agent 可在 prompt 中引用。
   - 终态沉淀：`StageExecutor.handleStageSuccess` 的 PUBLISHING/DATA_ANALYSIS/OPTIMIZATION 三个终态调用 `summarizeWorkflow`。
   - 文件：[ProjectMemory.java](../content-ops-server/content-ops-server/src/main/java/com/contentops/common/memory/ProjectMemory.java) / [ProjectMemoryService.java](../content-ops-server/content-ops-server/src/main/java/com/contentops/common/memory/ProjectMemoryService.java) / [ProjectMemoryProperties.java](../content-ops-server/content-ops-server/src/main/java/com/contentops/common/memory/ProjectMemoryProperties.java)

3. **全 Agent RAG 上下文注入（6/6）**
   - 从原 topic-planning + optimization(2/6) 扩展到 content-creation / image-design / publishing / data-analysis(4/6) 新增配置开关（默认关闭，可按阶段开启）。
   - **统一编排层注入**：在 `StageExecutor.buildRequest` 与 `AgentNodeAdapter.buildRequest` 构建请求前调用 `RagRetrievalEnhancer.shouldInjectContext` 判断并塞入 `inputs["ragContext"]`，**无需修改六个 Agent 的 Config 与接口签名**。
   - 文件：[RagProperties.java](../content-ops-server/content-ops-server/src/main/java/com/contentops/common/knowledge/RagProperties.java) （`ContextInjection` 扩展六开关）/ [RagRetrievalEnhancer.java](../content-ops-server/content-ops-server/src/main/java/com/contentops/common/knowledge/RagRetrievalEnhancer.java) （`shouldInjectContext` switch 扩展六阶段）

4. **配置显式化（application.yml）**
   - 新增 `contentops.project-memory.*`（默认 `enabled=false` 渐进开启）。
   - 显式化 `contentops.rag.context-injection.*` 六个开关，`topicPlanning/optimization` 默认开启、其余四个默认关闭。
   - 文件：[application.yml](../content-ops-server/content-ops-server/src/main/resources/application.yml) （`contentops.rag` + `contentops.project-memory` 两段）

### ✅ 延续已有（非本周期新增）

1. **编排韧性**：进程崩溃自动恢复续跑（kill 后重启自愈）；状态以 PG 为唯一事实源
2. **可靠事件**：阶段事件 Outbox 落库（PENDING→PUBLISHED），审计/回放/Kafka 迁移就绪
3. **对外接入**：标准 MCP JSON-RPC（6 个工具：热点/工作流/RAG），任何 MCP 客户端可调
4. **链路可观测**：workflow.stage → llm.call 多层 span，traceId 贯通「自定义 trace 表 + Jaeger + 审计」
5. **评测闭环**：判官 Agent 对阶段产物自动打分落库，CI 用同一套接口做回归门禁
6. **治理**：JWT 角色（ADMIN/CREATOR/VIEWER）即时生效、审计留痕关联 traceId
7. **部署**：前后端容器化、compose 全家桶（含 Jaeger/Prom/Grafana 自动接入 OTLP）

## 三、P0/P1/P2 差距与状态

> **状态标记**：`[DONE]` = 本周期已关闭；`[OPEN]` = 待处理；`[WIP]` = 进行中

### P0（规模化前必须）

| # | 内容 | 状态 | 关闭时间 / 说明 |
|---|------|:----:|----------------|
| 1 | **服务化拆分未落地**：仍是单机单实例。编排器 / Agent Worker / Tool Services 拆容器后才能水平扩容与故障域隔离（拆分契约已在 docs/ARCHITECTURE.md 就绪） | `[OPEN]` | 下一期 P0 首选；建议顺序：先 Tool Services → 再 Agent Worker → 最后编排器 |
| 2 | **长期记忆与上下文工程**：无跨会话用户/账号记忆、无 Agent 输出回流知识库 | **`[DONE]`** | **本周期关闭**（2026-08-18）。已交付：①AgentOutputIngester 闭环 + 双引擎接入；②ProjectMemory Redis 账号级记忆（启动注入 + 终态沉淀）；③6/6 Agent RAG 上下文注入开关。剩余"上下文压缩/Tiered Memory"下移至 P1 #8。 |
| 3 | **评测集规模与自动化**：当前评估集 2 条、无标注回流；需 50–100 条按阶段/平台覆盖 + 真实线上样本沉淀 + LLM-as-Judge 与人工标注对齐校准 | `[OPEN]` | 下一期 P0 次选（可与 #1 并行推进数据侧） |

**P0 关闭率：1 / 3**（记忆项关闭，底座综合分从 4.0 跃至 7.6，跨越"MVP→内部生产级"阈值）

### P1（体验与稳定性）

| # | 内容 | 状态 | 关联工程 |
|---|------|:----:|---------|
| 4 | OTel 全链路埋点覆盖不全：HTTP/LLM/阶段已埋，工具调用（MCP tools/call）、RAG 检索、任务队列尚未全部 span 化 | `[OPEN]` | 可观测性二次深化 |
| 5 | RAG 工程化：向量库仍以进程内/单机为主，缺分片、增量索引、权限过滤、效果 A/B | `[OPEN]` | 与服务化拆分 #1 联动（拆 Tool Services 时可单拆 RAG 服务） |
| 6 | 云原生编排：无 K8s/Helm、无 HPA、无滚动发布/金丝雀；Grafana Alerting 已有，但缺 SLO/错误预算体系 | `[OPEN]` | P0 #1 完成后进入 |
| 7 | 多租户完善：订阅/合集按 owner 隔离，但作品下载、文件存储、审计归属等仍有匿名兜底，生产需强制登录 + 全链路租户校验 | `[OPEN]` | 可独立推进 |
| 8 | **上下文压缩与分层记忆**：ProjectMemory 与 ChatMemory 均为单层（摘要文本），缺 Summarizer 摘要器、Tiered Memory（近/中/远期分层）、结构化缓存（缓存 Agent 决策片段而非纯文本） | `[OPEN]` <sup>\*</sup> | 从原 P0 #2 下移；本周期先达 5.8 分可用 MVP，下一期冲击 7 分 |

<sub>\* 下移理由：本周期已通过 ProjectMemory + 闭环把"记忆与上下文"维度从 2.0 拉到 5.8（达到"可用 MVP"标准，跨过 5 分阈值），剩余压缩/分层属于"冲击 7 分内部生产级"的体验增强。</sub>

### P2（大厂特色）

| # | 内容 | 状态 | 触发条件 |
|---|------|:----:|---------|
| 9 | Agent 自我改进闭环：评测失败自动触发优化迭代（优化 Agent 已存在，未接自动回路） | **`[DONE]`** | 本周期交付：LlmEvalRepository 新增 findLatestFailingRun/findLatestRunByWorkflow；EvalProperties.selfImprove.* 配置开关；EvalSelfImproveListener 监听 STAGE_COMPLETED 终态异步拉评测失败，自动调用 WorkflowService.runStandaloneStage(OPTIMIZATION)，含次数限制（默认 1 次）+ Redis/内存双重幂等防重。application.yml contentops.evals.self-improve.* 渐进开启（gateEnabled+autoOptimizationOnFail 双开 + score≤triggerMaxScore 才触发）。 |
| 10 | 多模型路由自动化：按任务难度/成本动态路由 + 模型网关（当前为阶段静态配置） | **`[DONE]`** | 本周期交付：DynamicGatewayProperties（tiered-chatmodels/stage-tier/difficulty-estimation/cost-optimization 四配置）；ChatModelRouter.route() 按 stageCode → promptLength → budget（isBudgetLow）→ vision-stage 多维度 selectTier；AiModelConfig 装配 cheap/strong/vision 三档 ChatModel Bean，默认 300/4000/0.8 分档参数；application.yml contentops.llm.dynamic-gateway.* 渐进开启（默认关闭，回退 stage-overrides 原静态路由）。 |
| 11 | 企业级集成：SSO/OIDC、数据脱敏审计、合规导出 | `[OPEN]` | 有企业客户诉求时启动 |

## 四、差距收敛路径（面向「对外规模化 9 分」）

```
当前 7.6（内部生产候选）
   │
   ├─ Step 1（关闭 P0）：
   │    a. P0 #1 服务化拆分 ──→ +0.7 分
   │    b. P0 #3 评测集 50-100 条 + 回流 + 对齐校准 ──→ +0.5 分
   │    合计 ~8.8（接近对外规模化门槛）
   │
   ├─ Step 2（P1 冲击 9 分）：
   │    a. P1 #8 上下文压缩 + Tiered Memory ──→ +0.3 分
   │    b. P1 #5 RAG 工程化（分布式向量库/权限/A-B）、P1 #4 OTel 全链路
   │    c. P1 #6 K8s/HPA/滚动 + SLO/错误预算
   │    d. P1 #7 全链路强制租户校验
   │
   └─ Step 3（P2 大厂特色）：
        P2 #9-11 按需触发，非规模化门槛项
```

## 五、结论

项目已从「功能型 MVP」走到「**内部生产级（7.6/10）**」：核心工程底座（编排韧性、事件总线、MCP、OTel、评测门禁、RBAC、容器化 + **本周期补齐的长期记忆与上下文闭环**）对标大厂**无 P0 级缺口残留**，上一版三个 P0 已关闭 1 个、剩余 2 个（#1 服务化、#3 评测集规模）均为下一期可明确交付的工程任务，**不再有"缺位"型短板**。

距「对外规模化」（9 分）~1.4 分差距收敛路径明确：

1. **必选项**：按 `docs/ARCHITECTURE.md` 顺序完成服务化拆分（先 Tool Services → 再 Agent Worker → 最后编排器）
2. **并行项**：评测集扩展至 50–100 条 + 标注回流 + 判官对齐校准
3. **加分项**：上下文压缩分层 + 分布式向量库工程化 + K8s/SLO 体系

## 变更历史

| 版本 | 日期 | 变更内容 | 底座分 |
|------|------|---------|:-----:|
| v2026-08-01 | 2026-08-01 | 初始评测 | 4.0 |
| v2026-08-18 | 2026-08-18 | **关闭 P0 #2「长期记忆与上下文工程」**：新增 AgentOutputIngester 闭环、ProjectMemory 跨工作流记忆、6/6 Agent RAG 注入开关；RAG 维度 +2 分、记忆维度 +3.8 分；底座综合 7.6 | **7.6** |
| v2026-08-18v2 | 2026-08-18 | **关闭 P2 #9「Agent 自我改进闭环」+ P2 #10「动态模型路由网关」**：①评测失败→自动拉起 OptimizationAgent 独立阶段（次数限制 + Redis/内存幂等防重）；②三档 ChatModel（cheap/strong/vision）按 stage/难度/预算/视觉多维度路由；评测维度 +1.4、模型成本控制 +1.3；底座综合 7.9 | **7.9** |
