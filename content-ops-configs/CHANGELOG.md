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
- **前端模块化**：评论助手页拆为 7 个模块组件 + 分段控件（评论工作台/自动采集/账号凭据/意图与关系/运维总览），
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
