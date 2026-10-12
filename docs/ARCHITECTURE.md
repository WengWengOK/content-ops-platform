# Content Ops 多 Agent 架构说明

## 一、当前形态：平台化单体

所有 Agent + 编排器运行在单个 Spring Boot 进程，但已具备「可拆分」的平台契约：

```
┌─────────────────────────── 单体运行时 ───────────────────────────┐
│ 控制面      AgentRegistry（8 Agent 元数据）/ MCP Server /mcp        │
│ 编排器      WorkflowService + StageExecutor + SubStageExecutor     │
│ Agent       topic / content / image / publishing / discussion      │
│              + analysis / optimization（独立服务模式）               │
│ 工具层      TrendService / WorkflowService / RAG / ImageGeneration │
│ 评论助手    采集(自建桥/第三方/模拟) / 通知增量 / 意图语料库 / 关系标签 │
│             附件(图片视频) / AI 起草 / 真实回复 / 定时任务 / 票据预警 │
│ 平台件      Outbox 事件 / A2A 信封 / LLM trace / Eval / 审计 / RBAC │
└────────────────────────────────────────────────────────────────────┘
```

## 二、目标形态：服务化拆分（Phase 3 蓝图）

拆分后共享 PG（事实源）+ Redis（锁/记忆）+ Kafka（事件总线）：

```
┌──────────────┐     ┌──────────────────────────────┐
│   Frontend   │────▶│ Orchestrator（控制面+状态机）  │
└──────────────┘     │  AgentRegistry / 编排 / 审批   │
                     └──────────────┬───────────────┘
                     Kafka 事件总线（Outbox 已就绪）
        ┌────────────┬──────────────┼──────────────┐
        ▼            ▼              ▼              ▼
   topic-worker  content-worker  image-worker  publish-worker
        │            │              │              │
        └────────────┴──────────────┴──────────────┘
                    Tool Services（趋势/热点/RAG/生图）
```

### 拆分可行性（契约已具备）

| 依赖 | 现状 | 拆服务时 |
|------|------|----------|
| 状态 | PG `contentops_workflow` 唯一事实源 | 直接共享，无需改 |
| 事件 | `contentops_agent_event` Outbox（PENDING→PUBLISHED） | drainer 改为发 Kafka，worker 消费 |
| 通信 | `AgentMessageEnvelope`（A2A 契约） | 透传到 Kafka topic |
| 工具 | `/mcp` 标准 JSON-RPC（热点/工作流/RAG） | worker 通过 MCP 调 Tool Services |
| 评测 | `contentops_llm_eval_run` 判分记录 | CI 门禁直接消费 |
| 追踪 | OTel span + traceId 关联 | 服务间 traceId 透传（header） |
| 权限 | JWT + RBAC | 网关统一校验 |
| 评论 | `contentops_comment` / `_media` / `_watch` / `_job_run` / `_user` / `intent_corpus` | 评论助手独立拆分时直接共享这些表（已在表内做 owner 隔离） |
| 凭据 | `contentops_platform_credential`（AES-GCM 密文） | 拆分后由统一凭据服务下发，密钥走 KMS/Vault |

### 推荐拆分顺序

1. **先拆 Tool Services**（收益最大、契约最成熟）：趋势/热点、RAG、生图作为独立容器，
   主进程通过 MCP/HTTP 调用
2. **再拆 Worker**：topic/content/image/publish 四个 worker 消费 Kafka 事件，
   编排器只做状态机与审批（此时编排器可用 LangGraph 引擎）
3. **最后拆编排器**：前端 → 编排器 API 网关，控制面独立部署

## 三、评论助手子系统（发布后的评论运营）

独立的评论运营闭环，与内容生产流水线解耦、共用一个进程与事实源。详细设计见
[docs/COMMENT-ASSISTANT.md](COMMENT-ASSISTANT.md)。

```
自建桥 xiaohongshu-mcp ─┐
第三方数据服务        ─┼─▶ CommentCollector ─▶ CommentMessageService ─▶ contentops_comment
内置模拟数据(开发)    ─┘        │                      (评论+附件整体入库)        │
                                │                                                  ▼
           通知增量(账号级,可并行)  │                                 意图语料库匹配 ─▶ 模型判定 ─▶ 启发式兜底
           按笔记监控(作品级)      │                                                  │
                                ▼                                                  ▼
                     CommentWatch(票据健康/预警)                    回复草稿 ─▶ 多轮对话 ─▶ 审核 ─▶ 真实回复
```

设计要点：

- **可插拔数据源**：`source=auto|api|mock`，真实接口支持 `preset=xhs-mcp`（自建桥）/ 自定义端点，
  鉴权 `bearer|query|ark-sign|none`；失败可回退模拟数据并返回 `fallbackReason`（来源可追溯）。
- **多账号归属**：账号凭据（`contentops_platform_credential`）按租户加密存储，
  采集/回复/通知按凭据解析客户端，监控项固定绑定 `credentialId`，跨租户 403。
- **意图识别三段式**：语料库精确匹配 → 本地 BGE 向量相似度 ≥ 阈值 → 模型判定 → 启发式兜底，
  命中来源与分数落库（`intent_source`/`intent_score`）便于观测命中率与调阈值。
- **消息模型**：评论即消息（发送者 `author`+`author_user_id`、时间 `comment_time`、正文、附件表、
  楼中楼 `reply_to`、来源 `collected_via`）；附件逐条记录所属用户。附件解析兼容多种字段与形态。
- **安全发送**：真实回复失败不写 SENT（保持 APPROVED 可重试）；通知来源优先走通知回复接口，失败回退笔记页回复。

## 四、关键横切能力

- **可观测**：OTel（Jaeger）+ Prometheus/Grafana + 自定义 LLM trace（traceId 关联）
- **评测**：LLM-as-Judge 流水线自动判分 + CI 门禁
- **治理**：JWT + RBAC（ADMIN/CREATOR/VIEWER）+ 操作审计（关联 traceId）
- **成本**：阶段级模型路由 / maxTokens / 熔断 / 预算告警（飞书/企微）
- **安全**：GuardedChatModel 输入输出护栏 / 域名白名单 / 租户归属校验
