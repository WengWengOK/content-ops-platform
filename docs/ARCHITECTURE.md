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

### 推荐拆分顺序

1. **先拆 Tool Services**（收益最大、契约最成熟）：趋势/热点、RAG、生图作为独立容器，
   主进程通过 MCP/HTTP 调用
2. **再拆 Worker**：topic/content/image/publish 四个 worker 消费 Kafka 事件，
   编排器只做状态机与审批（此时编排器可用 LangGraph 引擎）
3. **最后拆编排器**：前端 → 编排器 API 网关，控制面独立部署

## 三、关键横切能力

- **可观测**：OTel（Jaeger）+ Prometheus/Grafana + 自定义 LLM trace（traceId 关联）
- **评测**：LLM-as-Judge 流水线自动判分 + CI 门禁
- **治理**：JWT + RBAC（ADMIN/CREATOR/VIEWER）+ 操作审计（关联 traceId）
- **成本**：阶段级模型路由 / maxTokens / 熔断 / 预算告警（飞书/企微）
- **安全**：GuardedChatModel 输入输出护栏 / 域名白名单 / 租户归属校验
