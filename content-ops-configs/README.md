# Content Ops Agent Platform (Monolithic)

> AI 多 Agent 内容运营自动化平台 — 单体架构版本
>
> 覆盖"选题→内容→配图→发布→分析→优化"6 阶段闭环，支持循环优化迭代和人机协同渐进式生成。

## 技术栈

| 分类 | 技术 |
|------|------|
| 语言/框架 | Java 21 / Spring Boot 3.4 |
| AI 编排 | LangChain4j 1.0 + LangGraph4j 1.8 |
| 向量存储 | PostgreSQL + pgvector |
| 对话记忆 | Redis ChatMemory |
| 监控 | Micrometer + Prometheus |
| API 文档 | SpringDoc OpenAPI 3 |
| 前端 | React 18 + TypeScript + Vite + TailwindCSS |

## 核心特性

- **7 个 AI Agent**：选题策划、讨论模式、内容创作、配图设计、排版发布、数据分析、优化迭代
- **31 个工具方法**：真实平台 API 集成（微信/抖音/B站/快手/小红书/头条）、DALL-E 3 生图、Tavily 联网搜索
- **双引擎编排**：Legacy 顺序管线 + LangGraph4j 状态图，Nacos 动态灰度切换
- **RAG 全链路**：BGE-small-zh 本地嵌入 + PGVector 存储 + 自动入库 + 语义检索复用
- **质量自愈**：三维启发式评分 + AutoRetry + CompetitiveMode 竞争择优
- **模型路由**：创意类 gpt-4o / 格式化类 gpt-4o-mini，4 级回退策略
- **渐进式生成**：大纲→确认→初稿、风格→确认→生图

## 快速启动

```bash
# 前置：启动 Redis 和 PostgreSQL（需安装 pgvector 扩展）

# 编译运行
cd content-ops-server
mvn spring-boot:run

# Mock 模式（无需 LLM，用于前端联调）
CONTENTOPS_MODE=mock mvn spring-boot:run
```

### Docker Compose（一键启动完整可观测栈 + 单体服务）

```bash
# 1. 在 content-ops-configs/ 下准备 .env（模板见 .env.example 字段说明）：
#    POSTGRES_PASSWORD / REDIS_PASSWORD / GRAFANA_ADMIN_PASSWORD / OPENAI_API_KEY
# 2. 构建并启动：Redis + PostgreSQL(pgvector) + Jaeger + Prometheus + Grafana + content-ops-server
cd content-ops-configs
docker compose up -d --build

# 访问入口：
#   API 文档      http://localhost:8080/swagger-ui.html
#   健康检查      http://localhost:8080/api/v1/health
#   Prometheus    http://localhost:9090
#   Grafana       http://localhost:3000
#   Jaeger        http://localhost:16686
```

> 说明：镜像构建上下文是仓库根目录（`Dockerfile` 位于根目录，多阶段构建后只保留 JRE 运行产物）。

启动后访问：
- API 文档：http://localhost:8080/swagger-ui.html
- 健康检查：http://localhost:8080/api/v1/health

## 项目结构

```
content-ops-agent-platform-monolithic/
├── content-ops-server/          # 后端单体服务（~17,900 行 Java）
│   └── src/main/java/com/contentops/
│       ├── orchestrator/        # 编排层（双引擎 + Gateway）
│       ├── common/              # 公共能力（RAG/记忆/质量/路由/MCP/平台集成）
│       ├── topic/               # 选题 Agent
│       ├── content/             # 内容 Agent
│       ├── image/               # 配图 Agent
│       ├── publish/             # 发布 Agent
│       ├── analysis/             # 分析 Agent
│       └── optimize/            # 优化 Agent
├── content-ops-frontend/       # React 前端工程
└── pom.xml                     # 父 POM
```

## License

Apache 2.0
