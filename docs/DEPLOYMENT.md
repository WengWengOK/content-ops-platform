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
| `CONTENTOPS_SECURITY_CREDENTIAL_KEY` | **平台账号凭据加密密钥（AES-GCM，生产必配）**；轮换时把旧密钥写入 `CONTENTOPS_SECURITY_CREDENTIAL_KEYS_OLD` |
| `CONTENTOPS_SECURITY_CREDENTIAL_KEY_ID` | 当前密钥标识（写入密文，便于多密钥并存与轮换） |
| `CONTENTOPS_WEB_BASE_URL` | 前端地址，用于预警消息里的直达链接（默认 http://localhost:5173） |
| `XHS_COMMENT_ACCESS_TOKEN` / `CONTENTOPS_XHS_COMMENT_ENDPOINT` | 评论接口全局兜底配置（推荐改用「账号凭据」按账号配置） |

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

## 五、评论助手接入（自建桥示例）

```bash
# 1) 起桥：默认监听 18060，建议设置 AUTH_TOKEN（客户端用 Authorization: Bearer <token>）
AUTH_TOKEN=your-token ./xiaohongshu-mcp        # 或 go run .
# 2) 扫码登录（另开终端）
go run cmd/login/main.go
# 3) 平台内配置账号凭据（不必改配置文件）：评论助手 → 账号凭据 → 添加账号（桥地址 + AUTH_TOKEN）→ 测试连接
# 4) 评论助手 → 自动采集 → 作品 ID + xsec_token → 加入监控；或「拉取评论通知」做账号级增量
```

生产要点：

- **凭据密钥**：首次部署即设置 `CONTENTOPS_SECURITY_CREDENTIAL_KEY` 并纳入 KMS/Vault；密钥丢失将无法解密已存凭据。
- **轮换流程**：新密钥写入主变量、旧密钥写入 `_KEYS_OLD`（写法 `keyId=密钥材料`）→ 重启 → 调
  `POST /api/v1/platform-credentials/rotate` → 报告 `failed=0` 后移除旧密钥。
- **限流与合规**：桥是浏览器自动化，注意采集频率（`collect-ms`、`min-interval-seconds`、
  `notification-per-account-delay-ms`），并在桥所在机器上做资源隔离。
- **预警通道**：配置 `FEISHU_WEBHOOK` / `WECOM_WEBHOOK` 后，票据即将过期会汇总推送一条消息（带直达按钮）。

## 六、无 Docker 本地运行

```bash
# 依赖：PostgreSQL(5433 见 scripts/pg-manage.ps1) + 后端 jar + 前端 dev
powershell -ExecutionPolicy Bypass -File scripts/pg-manage.ps1 start
powershell -ExecutionPolicy Bypass -File scripts/start-backend.ps1
cd content-ops-frontend/content-ops-frontend && npm run dev
```
