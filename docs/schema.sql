-- ============================================================================
-- Content Ops Agent Platform — Database Schema (P0: Agent Memory & Multi-turn Dialogue)
-- ============================================================================
-- This schema provides persistent storage for discussion sessions, chat memory
-- archives, and workflow execution audit trails. Redis remains the primary
-- runtime store for ChatMemory (low-latency reads/writes); PostgreSQL is used
-- for long-term persistence, audit, and analytics.
--
-- Usage:
--   psql -U contentops -d contentops -f docs/schema.sql
-- ============================================================================

-- Enable UUID extension (if not already enabled)
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- ============================================================================
-- 1. Discussion Sessions — persistent record of multi-turn topic discussions
-- ============================================================================
-- Each row represents one "把TRAE当讨论对象" discussion session.
-- Redis stores the live session + ChatMemory; this table is the persistent mirror.
CREATE TABLE IF NOT EXISTS discussion_sessions (
    id                    BIGSERIAL PRIMARY KEY,
    session_id            VARCHAR(64) UNIQUE NOT NULL,
    workflow_id           VARCHAR(64),

    -- Discussion phase: IDEATION → CLARIFICATION → CONFIRMATION → COMPLETED
    phase                 VARCHAR(32) NOT NULL DEFAULT 'IDEATION',

    -- The original fuzzy idea from the user
    fuzzy_idea            TEXT,

    -- Account profile context (JSONB for flexible schema)
    account_profile       JSONB,

    -- Conversation turns: [{role, content, timestamp}, ...]
    turns                 JSONB DEFAULT '[]'::jsonb,

    -- AI-generated clarifying questions
    clarifying_questions  JSONB DEFAULT '[]'::jsonb,

    -- AI-proposed directions after clarification
    proposed_directions   JSONB DEFAULT '[]'::jsonb,

    -- User-confirmed direction
    confirmed_direction   TEXT,

    -- Final structured topic plan (TopicPlanResult JSON)
    topic_plan_result     JSONB,

    created_at            TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at            TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    completed_at          TIMESTAMP
);

-- ============================================================================
-- 2. Chat Memory Archive — long-term storage beyond Redis TTL
-- ============================================================================
-- Redis ChatMemory entries have a 24h TTL. This table archives conversations
-- before they expire, enabling audit trails and conversation replay.
CREATE TABLE IF NOT EXISTS chat_memory_archive (
    id              BIGSERIAL PRIMARY KEY,
    memory_id       VARCHAR(128) NOT NULL,

    -- The agent code (e.g., 'topic-planning', 'content-creation')
    agent_code      VARCHAR(64),

    -- The workflow ID this memory belongs to
    workflow_id     VARCHAR(64),

    -- JSON array of ChatMessage objects
    messages        JSONB NOT NULL,

    -- Number of messages in the conversation
    message_count   INTEGER DEFAULT 0,

    -- Whether this conversation resulted from a discussion session
    is_discussion   BOOLEAN DEFAULT FALSE,

    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    archived_at     TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- ============================================================================
-- 3. Workflow Executions — audit trail for pipeline runs
-- ============================================================================
-- Tracks the lifecycle of each content operations workflow as it flows
-- through the 6-stage pipeline: Topic → Content → Image → Publish → Analysis → Optimize
CREATE TABLE IF NOT EXISTS workflow_executions (
    id                      BIGSERIAL PRIMARY KEY,
    workflow_id             VARCHAR(64) UNIQUE NOT NULL,

    -- Current pipeline stage (AgentStage code)
    current_stage           VARCHAR(64),

    -- Task status: PENDING, IN_PROGRESS, AWAITING_HUMAN, COMPLETED, FAILED
    status                  VARCHAR(32) NOT NULL DEFAULT 'PENDING',

    -- Account info
    account_id              VARCHAR(64),
    account_profile         JSONB,

    -- Input/output artifacts (JSONB for flexible schema)
    inputs                  JSONB,
    outputs                 JSONB,
    accumulated_artifacts   JSONB DEFAULT '{}'::jsonb,

    -- Error tracking
    error_message           TEXT,
    retry_count             INTEGER DEFAULT 0,

    -- Human-in-the-loop
    require_human_review    BOOLEAN DEFAULT FALSE,

    -- Conversation history snapshot (backed by Redis ChatMemory at runtime)
    conversation_history    JSONB,

    created_at              TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at              TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    completed_at            TIMESTAMP
);

-- ============================================================================
-- 4. Agent Conversation Logs — individual turn-level logs for debugging
-- ============================================================================
-- Each row is one turn (user message → AI response) within an agent conversation.
-- Useful for debugging prompt issues and analyzing conversation quality.
CREATE TABLE IF NOT EXISTS agent_conversation_logs (
    id                  BIGSERIAL PRIMARY KEY,
    workflow_id         VARCHAR(64) NOT NULL,
    agent_code          VARCHAR(64) NOT NULL,
    memory_id           VARCHAR(128) NOT NULL,

    -- The user message (or parameterized input summary)
    user_message        TEXT,

    -- The AI response (or structured output summary)
    assistant_response  TEXT,

    -- Turn number within this conversation (0-indexed)
    turn_number         INTEGER DEFAULT 0,

    -- Response time in milliseconds
    response_time_ms    BIGINT,

    -- Whether tools were called during this turn
    tools_called        JSONB DEFAULT '[]'::jsonb,

    created_at          TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- ============================================================================
-- Indexes
-- ============================================================================
CREATE INDEX IF NOT EXISTS idx_discussion_sessions_session_id  ON discussion_sessions(session_id);
CREATE INDEX IF NOT EXISTS idx_discussion_sessions_workflow_id ON discussion_sessions(workflow_id);
CREATE INDEX IF NOT EXISTS idx_discussion_sessions_phase       ON discussion_sessions(phase);
CREATE INDEX IF NOT EXISTS idx_discussion_sessions_created     ON discussion_sessions(created_at);

CREATE INDEX IF NOT EXISTS idx_chat_memory_memory_id    ON chat_memory_archive(memory_id);
CREATE INDEX IF NOT EXISTS idx_chat_memory_workflow_id  ON chat_memory_archive(workflow_id);
CREATE INDEX IF NOT EXISTS idx_chat_memory_agent_code  ON chat_memory_archive(agent_code);
CREATE INDEX IF NOT EXISTS idx_chat_memory_archived    ON chat_memory_archive(archived_at);

CREATE INDEX IF NOT EXISTS idx_workflow_exec_workflow_id ON workflow_executions(workflow_id);
CREATE INDEX IF NOT EXISTS idx_workflow_exec_status      ON workflow_executions(status);
CREATE INDEX IF NOT EXISTS idx_workflow_exec_stage       ON workflow_executions(current_stage);
CREATE INDEX IF NOT EXISTS idx_workflow_exec_created     ON workflow_executions(created_at);

CREATE INDEX IF NOT EXISTS idx_agent_logs_workflow_id ON agent_conversation_logs(workflow_id);
CREATE INDEX IF NOT EXISTS idx_agent_logs_agent_code ON agent_conversation_logs(agent_code);
CREATE INDEX IF NOT EXISTS idx_agent_logs_memory_id  ON agent_conversation_logs(memory_id);
CREATE INDEX IF NOT EXISTS idx_agent_logs_created    ON agent_conversation_logs(created_at);

-- ============================================================================
-- Redis Key Structure Reference (for documentation)
-- ============================================================================
-- Redis is the primary runtime store. Key structure:
--
--   contentops:chat-memory:{memoryId}
--     → JSON array of ChatMessage objects
--     → TTL: 24 hours (configurable via contentops.chat-memory.ttl-hours)
--     → memoryId format: {agentCode}:{workflowId} (e.g., "topic-planning:abc-123")
--     → or "discussion:{sessionId}" for discussion sessions
--
--   contentops:discussion:{sessionId}
--     → JSON DiscussionSession object
--     → TTL: 48 hours
--
--   contentops:workflow:{workflowId}
--     → JSON TaskContext object
--     → TTL: 7 days (workflow state)
--
--   contentops:agent:context:{agentCode}:{workflowId}
--     → JSON agent-specific context
--     → TTL: 24 hours
-- ============================================================================
