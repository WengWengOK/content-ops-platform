-- ════════════════════════════════════════════════════════════════
-- Content Ops 平台数据库结构（H2 开发 / PostgreSQL 生产通用，幂等可重复执行）
-- 由 spring.sql.init 在应用启动时自动执行。
-- ════════════════════════════════════════════════════════════════

-- 工作流状态（TaskContext 以 JSON 存储；状态持久化到数据库，Redis 仅承担锁与对话记忆）
CREATE TABLE IF NOT EXISTS contentops_workflow (
    workflow_id  VARCHAR(64)      PRIMARY KEY,
    context_json VARCHAR(1000000) NOT NULL,
    owner_id     VARCHAR(64),
    created_at   TIMESTAMP        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   TIMESTAMP        NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_workflow_owner_updated
    ON contentops_workflow (owner_id, updated_at DESC);

-- Agent 平台事件 Outbox（大厂多 Agent 架构）：阶段/Agent 产生的领域事件持久化，
-- 供审计、回放与后续 Kafka 迁移；drainer 消费后标记 PUBLISHED。
CREATE TABLE IF NOT EXISTS contentops_agent_event (
    event_id     VARCHAR(64)  PRIMARY KEY,
    workflow_id  VARCHAR(64),
    agent        VARCHAR(64),
    event_type   VARCHAR(32)  NOT NULL,
    payload_json VARCHAR(20000),
    status       VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    created_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_agent_event_workflow_time
    ON contentops_agent_event (workflow_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_agent_event_status
    ON contentops_agent_event (status, created_at);

-- 用户（P0 鉴权；仅 contentops.security.enabled=true 时启用业务 API 登录）
CREATE TABLE IF NOT EXISTS contentops_user (
    user_id       VARCHAR(64)  PRIMARY KEY,
    username      VARCHAR(64)  NOT NULL UNIQUE,
    password_hash VARCHAR(128) NOT NULL,
    password_salt VARCHAR(64)  NOT NULL,
    created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- RBAC：用户角色（ADMIN/CREATOR/VIEWER），默认创作者
ALTER TABLE contentops_user ADD COLUMN IF NOT EXISTS role VARCHAR(32) NOT NULL DEFAULT 'CREATOR';

-- 作品合集（P0 新功能：用户自建合集，按类型区分，存放同类作品）
CREATE TABLE IF NOT EXISTS contentops_work_collection (
    collection_id VARCHAR(64)  PRIMARY KEY,
    owner_id      VARCHAR(64),
    name          VARCHAR(128) NOT NULL,
    type          VARCHAR(64)  NOT NULL,
    description   VARCHAR(512),
    created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_work_collection_owner
    ON contentops_work_collection (owner_id, updated_at DESC);

-- 作品-合集归属（多对多）
CREATE TABLE IF NOT EXISTS contentops_work_collection_item (
    collection_id VARCHAR(64) NOT NULL,
    workflow_id   VARCHAR(64) NOT NULL,
    added_at      TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (collection_id, workflow_id)
);

CREATE INDEX IF NOT EXISTS idx_work_collection_item_workflow
    ON contentops_work_collection_item (workflow_id);

-- 热点监控（TrendRadar 思路：多平台热榜聚合 + 快照存储，供选题模块直接取热点）
CREATE TABLE IF NOT EXISTS contentops_trend_hotspot (
    id          VARCHAR(64)  PRIMARY KEY,
    platform    VARCHAR(32)  NOT NULL,
    title       VARCHAR(512) NOT NULL,
    url         VARCHAR(1024),
    heat        BIGINT,
    rank        INT,
    category    VARCHAR(64),
    summary     VARCHAR(1024),
    captured_at TIMESTAMP    NOT NULL,
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_trend_platform_time
    ON contentops_trend_hotspot (platform, captured_at DESC);

-- 热点监控订阅：用户自定义监控的行业/关键词方向
CREATE TABLE IF NOT EXISTS contentops_trend_subscription (
    subscription_id VARCHAR(64)  PRIMARY KEY,
    owner_id        VARCHAR(64),
    keyword         VARCHAR(128) NOT NULL,
    enabled         BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_trend_subscription_owner
    ON contentops_trend_subscription (owner_id);

-- 关键词命中记录：每次轮询时把「已启用监控方向」匹配到的热点写入，
-- 支撑突发热点检测/通知（P1）与「关键词驱动抓取」的历史回溯。
CREATE TABLE IF NOT EXISTS contentops_trend_keyword_hit (
    hit_id      VARCHAR(64)  PRIMARY KEY,
    owner_id    VARCHAR(64),
    keyword     VARCHAR(128) NOT NULL,
    platform    VARCHAR(32)  NOT NULL,
    title       VARCHAR(512) NOT NULL,
    url         VARCHAR(1024),
    heat        BIGINT,
    rank        INT,
    category    VARCHAR(64),
    summary     VARCHAR(1024),
    captured_at TIMESTAMP    NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_trend_keyword_hit_owner_time
    ON contentops_trend_keyword_hit (owner_id, keyword, captured_at DESC);

-- 突发热点事件：轮询时检测到「新上榜/飙升/上升」即落库，
-- 支持历史回溯（快照轮次之间的爆发不会丢失）与 P1 通知。
CREATE TABLE IF NOT EXISTS contentops_trend_burst_event (
    event_id    VARCHAR(64)  PRIMARY KEY,
    platform    VARCHAR(32)  NOT NULL,
    title       VARCHAR(512) NOT NULL,
    url         VARCHAR(1024),
    heat        BIGINT,
    prev_heat   BIGINT,
    rank        INT,
    prev_rank   INT,
    heat_delta  BIGINT,
    rank_delta  INT,
    burst_label VARCHAR(16)  NOT NULL,
    burst_score INT,
    captured_at TIMESTAMP    NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_trend_burst_time
    ON contentops_trend_burst_event (captured_at DESC);

-- LLM 调用追踪（P0 可观测性）：每次真实模型调用的 token/延迟/状态，
-- 支撑成本大盘、阶段画像与故障排查；按 retention 天数定期清理。
CREATE TABLE IF NOT EXISTS contentops_llm_trace (
    trace_id     VARCHAR(64)  PRIMARY KEY,
    workflow_id  VARCHAR(64),
    stage        VARCHAR(64),
    agent        VARCHAR(64),
    model        VARCHAR(128),
    tokens_in    BIGINT,
    tokens_out   BIGINT,
    prompt_chars INT,
    output_chars INT,
    latency_ms   BIGINT,
    status       VARCHAR(16)  NOT NULL,
    error_message VARCHAR(512),
    created_at   TIMESTAMP    NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_llm_trace_time
    ON contentops_llm_trace (created_at DESC);
CREATE INDEX IF NOT EXISTS idx_llm_trace_workflow
    ON contentops_llm_trace (workflow_id);

-- Phase 2 OTel：trace 表关联分布式追踪（trace_id/span_id 来自 OpenTelemetry）
ALTER TABLE contentops_llm_trace ADD COLUMN IF NOT EXISTS otel_trace_id VARCHAR(64);
ALTER TABLE contentops_llm_trace ADD COLUMN IF NOT EXISTS otel_span_id VARCHAR(64);
CREATE INDEX IF NOT EXISTS idx_llm_trace_otel_trace_id
    ON contentops_llm_trace (otel_trace_id);

-- LLM-as-Judge 评测（Phase 2）：评估集用例 + 判官打分记录（回归门禁数据源）
CREATE TABLE IF NOT EXISTS contentops_llm_eval_case (
    case_id    VARCHAR(64)  PRIMARY KEY,
    stage      VARCHAR(64)  NOT NULL,
    title      VARCHAR(256),
    input_ref  TEXT,
    expected   TEXT,
    created_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS contentops_llm_eval_run (
    run_id         VARCHAR(64)  PRIMARY KEY,
    case_id        VARCHAR(64),
    workflow_id    VARCHAR(64),
    stage          VARCHAR(64)  NOT NULL,
    model          VARCHAR(128),
    judge_score    INT,
    judge_feedback TEXT,
    passed         BOOLEAN,
    threshold      INT,
    created_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_llm_eval_run_stage_time
    ON contentops_llm_eval_run (stage, created_at DESC);

-- 操作审计（Phase 2）：关键变更留痕，关联 OTel traceId
CREATE TABLE IF NOT EXISTS contentops_audit_log (
    audit_id    VARCHAR(64)  PRIMARY KEY,
    owner_id    VARCHAR(64),
    action      VARCHAR(64)  NOT NULL,
    target_type VARCHAR(64),
    target_id   VARCHAR(128),
    detail      VARCHAR(1000),
    trace_id    VARCHAR(64),
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_audit_owner_time
    ON contentops_audit_log (owner_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_audit_action_time
    ON contentops_audit_log (action, created_at DESC);

-- 评论区 AI 助手（MVP：小红书）：作品发布后评论采集/意图识别/AI 对话
CREATE TABLE IF NOT EXISTS contentops_comment (
    comment_id    VARCHAR(64)  PRIMARY KEY,
    owner_id      VARCHAR(64),
    platform      VARCHAR(32)  NOT NULL,
    work_id       VARCHAR(128),
    workflow_id   VARCHAR(64),
    author        VARCHAR(128),
    content       TEXT         NOT NULL,
    likes         INT          DEFAULT 0,
    comment_time  TIMESTAMP,
    reply_to      VARCHAR(128),
    intent        VARCHAR(32),
    sentiment     VARCHAR(16),
    ai_summary    VARCHAR(1000),
    ai_reply      TEXT,
    reply_status  VARCHAR(16)  DEFAULT 'NONE',
    dialog_history TEXT,
    collected_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_comment_platform_work
    ON contentops_comment (platform, work_id, collected_at DESC);
CREATE INDEX IF NOT EXISTS idx_comment_intent
    ON contentops_comment (intent);
CREATE INDEX IF NOT EXISTS idx_comment_owner_time
    ON contentops_comment (owner_id, collected_at DESC);
