-- ════════════════════════════════════════════════════════════════
-- Content Ops 平台 PostgreSQL 初始化脚本
-- 由 docker-compose 挂载到 /docker-entrypoint-initdb.d/schema.sql，
-- 仅在容器首次初始化数据卷时执行。
-- ════════════════════════════════════════════════════════════════

-- LangChain4j 的 PgVectorEmbeddingStore 依赖 pgvector 扩展（RAG 向量检索）
CREATE EXTENSION IF NOT EXISTS vector;

-- 工作流状态表（与 classpath:schema.sql 保持一致，容器首次初始化时创建）
CREATE TABLE IF NOT EXISTS contentops_workflow (
    workflow_id  VARCHAR(64)      PRIMARY KEY,
    context_json VARCHAR(1000000) NOT NULL,
    owner_id     VARCHAR(64),
    created_at   TIMESTAMP        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   TIMESTAMP        NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_workflow_owner_updated
    ON contentops_workflow (owner_id, updated_at DESC);

CREATE TABLE IF NOT EXISTS contentops_user (
    user_id       VARCHAR(64)  PRIMARY KEY,
    username      VARCHAR(64)  NOT NULL UNIQUE,
    password_hash VARCHAR(128) NOT NULL,
    password_salt VARCHAR(64)  NOT NULL,
    created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 说明：
-- 1) 向量表 content_embeddings 由 KnowledgeBaseService 启动时自动创建（createTable=true）；
-- 2) 业务表由应用按需创建（本项目无 JPA 实体，编排状态存 Redis）；
-- 3) 如需初始化自定义业务表，请在此文件追加 DDL（首次启动时执行一次）。
