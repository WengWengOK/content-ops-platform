# Changelog

All notable changes to this project will be documented in this file.

## [2026-10] 评论助手（Comment Assistant）— 发布后评论运营闭环

### Added
- **评论采集（可插拔数据源）**：真实接口 `preset=xhs-mcp`（自建桥 `POST /api/v1/feeds/detail`）/
  自定义端点 / 内置模拟数据；数据源策略 `auto|api|mock` 与可配置回退，返回 `source` + `fallbackReason`
- **通知增量采集**：`notifications/list`（tab=mentions/connections）、账号级并行 + 每账号延迟限流、
  按 `comment_id` 去重、发现新笔记自动纳管监控项、可选自动 AI 分析（单轮条数上限）
- **意图识别三段式**：意图语料库 RAG 前置（归一化精确匹配 → 本地 BGE 余弦 ≥ 阈值）→ 模型判定 → 启发式兜底；
  `intent_source`/`intent_score` 落库，提供语料 CRUD、向量缓存重建、匹配预览与命中率统计
- **关系标签**：粉丝（新增关注通知自动同步）/ 关注 / 好友（人工或外部关系源）/ 常客（评论次数阈值）/ 自己 / 路人；
  评论用户关系库 + 关系分布与按关系筛选
- **评论即消息（附件）**：`contentops_comment_media` 附件表（图片/视频、缩略图、宽高、排序、
  所属用户 `owner_user_id`/`owner_name`），评论主表 `media_count` 紧凑标记与「只看带图」筛选；
  附件字段解析兼容对象数组与纯 URL 数组
- **真实回复**：多轮 AI 对话（`dialog_history`）+ 状态机 NONE→DRAFT→APPROVED→SENT；
  通知来源优先走 `notifications/reply`、失败回退笔记页回复；失败不写 SENT（可重试）
- **多账号凭据与密钥轮换**：凭据按租户归属、AES-GCM 加密落库、`enc:v2:<keyId>` 密文 + 旧密钥兼容、
  `POST /platform-credentials/rotate` 批量重加密、解密失败显式报错（绝不把密文当令牌返回）
- **运维与预警**：运维总览（轮换状态/账号健康/票据预警/最近任务）、票据 `token_state` +
  观测有效期（`min(配置阈值, 观测×0.8)` 提前预警）、一键更新票据、预警汇总推送飞书交互卡片（带直达按钮）
- **场景客服回复 Agent（可插拔 + 忠实度校验）**：`contentops_reply_agent_profile` 场景配置（内置电商带货默认 /
  课程示例模板（停用）/ 通用兜底三个种子），场景差异（提示词、商品事实、禁止承诺、转人工词、语气、长度、外链）
  全部配置化，新增场景不改代码；回复草稿经 `ReplyComplianceChecker` 做**只标记不修正**的忠实度校验
  （禁止承诺 / 内置高危词 / 未核实价格·促销·时效 / 漏提示转人工 / 外链 / 超长），
  高置信度进 `NEEDS_REVIEW` 复核清单、中低置信度 `SAMPLED` 抽样；`reply_flags`/`reply_confidence`/
  `reply_review_state` 落库审计；支持 `recheck` 对人工改写后的草稿重新标记（文本原样保留）
- **知识库问答 + RAG 错误自动识别（五层）**：新增 `contentops_kb_qa` / `contentops_kb_qa_signal` 两表与
  `POST /api/v1/kb/qa/ask` 等 14 个接口。四类错误分别识别：①检索错误（相关条数/最高分/改写耗尽/双路分歧/
  模型拒答导致的盲区信号）；②生成幻觉（**答案-证据忠实度校验**：结论拆分 → 逐条判定支持/部分支持/不支持）；
  ③引用不实（引用编号越界或零引用）；④拒答误判（检索充分性对照 + 模型拒答识别）。
  用户隐式信号归因（追问质疑/重复提问/引用秒退/转人工确认/点踩）自动打「疑似错误」标签；
  离线聚类找知识库盲区、错误样本一键并入回归评测集（stage=`kb-qa`）。全程**只标记不修正**，
  高风险进人工复核清单、中低置信度抽样。实测修掉两个真实误判：长证据+短结论用 Jaccard 会把逐字复述的
  正确答案判成编造（改用证据覆盖率），模型主动拒答被误判为引用不实（改为盲区信号）
- **CrewAI 研究 crew 接入（外部 Agent）**：新增 `content-ops-crewai/` 独立 Python 服务（研究员→策划→事实核查
  三角色，`crewai` 与 `lite` 双执行器同构，容器镜像基于 python:3.12-slim）+ 平台侧 `ExternalAgentController`
  （`/api/v1/external-agents/status|research/run`）。crew 不直连数据库，只用服务账号 JWT 调平台工具
  （热榜 / 按主题搜热榜 / 全网搜索 / 知识库检索），产出由平台回写知识库（type=research_report）进检索与评测面，
  并记审计与 traceId。实测修掉两个真实问题：推理型模型把 token 花在 reasoning 上导致正文为空
  （放宽 max_tokens + content 空时回落 reasoning_content + 紧凑提示重试一次）、全局热榜对垂类主题无效
  （新增按主题搜热榜工具）
- **多平台发布接 MCP（作品真实上传）**：新增 `common/mcp/client`（MCP 客户端：`initialize` →
  `tools/list` → `tools/call`；支持 **stdio** 与 **Streamable HTTP/SSE** 双传输）与 `publish/mcp`
  （按平台配置、工具选择、参数映射、审计）。调研并预置社区 MCP：抖音 `douyin-upload-mcp-skill`（CDP 自动化）、
  小红书 `xiaohongshu-mcp`（HTTP+MCP）、公众号 `wenyan-mcp`，B 站保留占位。新增
  `/api/v1/publish/platforms/status|{platform}/publish|tools/{tool}/call|reset`。未配置或调用失败明确返回
  `ok=false`，不伪造成功。实测修掉四个坑：通知等响应白等超时（拆出 notify，30s→0.29s）、
  Windows `npx` 需解析为 `npx.cmd`、弱语义把 `create_directory` 误当发布工具（改为只认强语义 +
  未识别要求显式配置）、Python stdio 中文乱码（默认注入 UTF-8 环境变量）
- **影刀 RPA 发布通道**：复用 MCP 客户端接影刀官方 MCP Server（`npx -y yingdao-mcp-server`），
  支持本地模式（`RPA_MODEL=local` + `SHADOWBOT_PATH`/`USER_FOLDER`，工具 `queryApplist`/`queryRobotParam`/`runApp`）
  与企业开放 API 模式（`ACCESS_KEY_ID/SECRET`，`startJob` + `queryJob` 异步轮询）。
  平台级 `channel: auto|mcp|rpa`：auto 原生 MCP 失败自动回落 RPA，显式通道失败不回落；
  新增按通道调工具接口（`/publish/platforms/{platform}/channels/{channel}/tools/{tool}/call`）。
  实测：影刀通道探活返回 5 个工具、`queryApplist` 可取应用、异步发布 `startJob→queryJob` 747ms 到 SUCCESS
- **小红书运营闭环（商品上架 → 笔记发布挂车）**：新增 `POST /api/v1/publish/xiaohongshu/operations/run`
  与 `/publish/platforms/{platform}/products`。笔记发布走 xiaohongshu-mcp 并支持 `products` 挂车参数
  （该 MCP 只挂已上架商品，所以顺序必须是先上架）；商品上架走影刀 RPA（千帆商家后台）或任何提供
  `product-tool` 的 MCP。上架失败自动停止发布，上架成功则把商品标题并入挂车列表。
  实测桩通道：上架 265ms 返回商品ID，笔记发布 38ms 且挂车 `['店铺爆款','保湿面膜 10 片装']`
- **小红书真实发布链路跑通（Windows + WSL）**：Windows Defender 拦截 Rod leakless.exe，改用独立 Ubuntu WSL
  跑 Linux 版 xiaohongshu-mcp；修复 MCP Streamable HTTP 的 `Mcp-Session-Id` 透传（否则 tools/call 失败）；
  发布参数新增 `visibility`（支持仅自己可见）。真实发布「ContentOps 测试笔记」成功（46.6s），
  `get_my_profile` 核对到笔记 `6adc8158000000002c034df9`。新增 `scripts/xhs-mcp.ps1`（启停）与
  `scripts/xhs-login-qr.ps1`（取二维码）
- **前端模块化**：评论助手页拆为 7 个模块组件 + 分段控件（评论工作台/自动采集/账号凭据/意图与关系/客服 Agent/运维总览），
  模块与 URL 同步支持深链

### Changed
- **作品平台标签**：新增 `PlatformCode`（0=未知,1=小红书,2=公众号,3=抖音,4=B站,5=快手），
  `contentops_workflow.platform_code` 用 1 字节存储 + 索引，前端按序号解码展示（历史数据幂等回填）
- 评论入库统一走 `CommentMessageService`（评论 + 附件整体落库，幂等去重），四条采集路径行为一致

## [Unreleased]

### Added — P1: Prompt Engineering Deep Optimization
- **Few-shot examples**: Each agent's SystemMessage now includes 1-2 high-quality output examples
  - TopicAgent: topic planning examples with unconventional angles
  - ContentAgent: article outline + body snippet example
  - AnalysisAgent: data analysis insights + actionable recommendations example
  - ImageAgent: visual keyword extraction + prompt generation example
  - PublishAgent: platform-specific formatting examples (公众号/小红书)
  - OptimizeAgent: strategy adjustment with quantified impact example
- **Dynamic Prompt assembly** (`PromptFragmentService`): Assembles system messages dynamically based on account profile (niche, tone, platforms)
  - Niche-specific guidance for 5 domains (个人成长/母婴/科技/美食/财经)
  - Tone-specific guidance for 3 styles (轻松/专业/感性)
  - Platform-specific guidance for 3 platforms (公众号/小红书/头条)
- **Unconventional angle guidance** (TopicAgent): At least 1 topic per plan must use reverse thinking, cross-domain analogy, niche perspective, or counter-intuitive data
- **Personal experience injection** (ContentAgent): New `{{personalExperience}}` template variable in `generateOutline`, `generateDraft`, and `createDraft` methods
  - `AccountProfile.personalExperience` field added to TaskContext
  - ContentAgentController extracts personalExperience from inputs/AccountProfile
- **Data questioning methodology** (AnalysisAgent): 5-question framework built into system prompt (trend, content type, timing, engagement, follower growth)
- **Prompt version management** (`PromptVersionService` + `PromptVersionProperties`):
  - Per-agent version control (v1/v2/v3) via `contentops.prompt.agents.*` config
  - Nacos config center integration ready (just add `spring-cloud-starter-alibaba-nacos-config` + `@RefreshScope`)
- **A/B testing framework**: Variant A (standard) and Variant B (experimental) prompt instructions
  - Hash-based traffic splitting via `contentops.prompt.ab-testing.traffic-split`
  - Each agent has variant-specific additional instructions in `PromptFragmentService`
- **Configuration**: `contentops.prompt.*` added to all 6 agent application.yml files

### Added — Previous
- **Multi-Agent Microservice Architecture**: 6 specialized AI Agent microservices for content operations pipeline
  - Topic Planning Agent (port 8081) — trending research, competitor analysis, topic recommendation
  - Content Creation Agent (port 8082) — article outline, draft generation, title variations
  - Image Design Agent (port 8083) — AI image prompt generation, multi-platform cover sizing
  - Publishing Agent (port 8084) — multi-platform formatting, readability optimization
  - Data Analysis Agent (port 8085) — performance metrics, trend analysis, chart data generation
  - Optimization Agent (port 8086) — strategy adjustment, health scoring, next-cycle recommendations
- **Pipeline Orchestrator** (port 8080) — sequential pipeline with human-in-the-loop support
- **Common Module** — shared DTOs, enums, events, Redis state management
- **LangChain4j @AiService integration** — declarative AI interfaces with @Tool calling
- **Spring Cloud microservice stack** — Eureka service discovery, OpenFeign clients, Kafka events
- **Docker Compose** — one-command full stack deployment
- **Architecture documentation** — full design doc with all 6 agent prompts

### Known Limitations (Planned for Future Releases)
- All @Tool methods return simulated data (no real API integration yet)
- No ChatMemory support (agents are stateless single-shot calls)
- No RAG/vector store integration
- No real image generation (prompt only)
- No real platform API publishing
- No streaming responses
- No token cost tracking
