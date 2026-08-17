# Content Ops 部署指南

## 一、Docker Compose（推荐）

仓库根目录执行：

```bash
cp content-ops-configs/.env.example content-ops-configs/.env
# 编辑 .env：POSTGRES_PASSWORD / REDIS_PASSWORD / OPENAI_API_KEY 必填
docker compose -f content-ops-configs/docker-compose.yml up -d --build
```

服务拓扑：

| 服务 | 端口 | 说明 |
|------|------|------|
| content-ops-frontend | 5173 | Nginx 托管前端，反代 /api /ws /mcp 到后端 |
| content-ops-server | 8080 | 单体后端（6 Agent + 编排器 + MCP + 热点 + RAG） |
| postgres | 5432 | PG + pgvector，唯一事实源 |
| redis | 6379 | 分布式锁 / 对话记忆 |
| jaeger | 16686 / 4318 | 分布式链路（OTLP HTTP 已接入） |
| prometheus | 9090 | 指标采集 |
| grafana | 3000 | 大盘（LLM 观测 / 告警，provisioning 自动加载） |

## 二、关键环境变量

| 变量 | 说明 |
|------|------|
| `OPENAI_API_KEY` / `OPENAI_BASE_URL` / `LLM_MODEL` | 模型接入（本项目当前用 DeepSeek 兼容端点） |
| `CONTENTOPS_SECURITY_ENABLED=true` | 生产开启 JWT 鉴权 + RBAC |
| `CONTENTOPS_JWT_SECRET` | 生产必改的 JWT 密钥 |
| `CONTENTOPS_OTLP_ENDPOINT` | 默认 `http://jaeger:4318/v1/traces`（compose 已配） |
| `CONTENTOPS_EVALS_GATE=true` | 开启评测门禁阻断（默认只记录） |
| `FEISHU_WEBHOOK` / `WECOM_WEBHOOK` | 告警机器人（Grafana Alerting → 后端 → 群） |
| `TAVILY_API_KEY` | 全网搜索聚合 |

## 三、健康检查与就绪

```bash
curl http://localhost:8080/api/v1/health
curl http://localhost:8080/actuator/prometheus | head
```

## 四、CI/CD 评测门禁

`.github/workflows/ci.yml` 包含三阶段：

1. 后端构建 + 全量测试
2. 前端构建
3. **LLM 评测门禁**：起 PG + 后端，用 `scripts/eval-gate.mjs` 对 `content-ops-configs/evals/cases.json`
   逐条调用 `LLM-as-Judge` 判分，任一低于 `EVAL_THRESHOLD`（默认 70）即 CI 失败、阻断合并。

仓库需配置 Secret：`OPENAI_API_KEY`。未配置时该 Job 自动跳过。

## 五、无 Docker 本地运行

```bash
# 依赖：PostgreSQL(5433 见 scripts/pg-manage.ps1) + 后端 jar + 前端 dev
powershell -ExecutionPolicy Bypass -File scripts/pg-manage.ps1 start
powershell -ExecutionPolicy Bypass -File scripts/start-backend.ps1
cd content-ops-frontend/content-ops-frontend && npm run dev
```
