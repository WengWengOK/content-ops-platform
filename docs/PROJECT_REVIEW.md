# Content Ops 项目检查与评测报告

> 检查日期：2026-08-18 ｜ 检查人：Codex（独立复核）
> 基线：git HEAD `f4156d1`（服务化拆分骨架 + 长期记忆 + 动态路由 + 自我改进闭环）
> 配套文档：能力/差距评分见 [EVALUATION-2026-08-18.md](EVALUATION-2026-08-18.md)，架构蓝图见 [ARCHITECTURE.md](ARCHITECTURE.md)

## 一、检查范围与方法

| 项目 | 方法 |
|------|------|
| 构建 | 父 POM 全模块 `mvn test`（编译 + 全量测试）、前端 `npm run build` |
| 测试 | 308 个后端用例全量执行，逐项归类失败根因 |
| 服务 | 探测 5433(PG) / 8080(后端) / 5173(前端) / 9090(Prometheus) / 3000(Grafana) |
| 安全 | 对暂存文件扫描真实密钥（API Key / Token / 密码） |
| 版本 | git 历史、远程、未提交变更核对 |
| 规模 | Java / TS 代码量、模块结构统计 |

## 二、项目概览

### 规模与结构

| 指标 | 数值 |
|------|------|
| Java 代码量 | 约 60,000 行 |
| TS/TSX 代码量 | 约 11,400 行 |
| 后端模块 | `content-ops-common`（契约） / `content-ops-orchestrator`(8080) / `content-ops-worker`(8081) / `content-ops-tools`(8082) / `content-ops-server`（legacy 单体） |
| 前端 | React 18 + Vite，173 模块 |
| 测试 | 308 个后端用例 |

### 当前架构形态

处于「**平台化单体 → 服务化拆分**」过渡期：多模块骨架已建立（common/orchestrator/worker/tools，
内部契约 `POST /internal/api/agent/execute` + `/internal/api/tools/*`），但三模块目前直接依赖
legacy-server 实现全量扫包，功能与单体等价；Dockerfile 已支持 `--build-arg MAVEN_MODULE` 复用，
并提供 `docker-compose-phase3.yml` 三服务独立部署。

## 三、功能能力清单（HEAD）

| 域 | 能力 |
|----|------|
| 热点监控 | 7 平台真实热榜（多路降级）、关键词启停/命中、AI 相关性/真假/摘要、突发检测、时间范围、趋势曲线/平台对比 |
| 内容生产 | 4 阶段流水线（选题→内容→配图→发布）、人机协同、多平台并行扇出、ZIP 下载 |
| 实时与通知 | WebSocket、SSE 流式对话与阶段事件、邮件、Grafana Alerting → 飞书/企微 |
| RAG | 混合检索 + 重排 + RAGAS；**Agent 输出 → 知识库回流闭环（新增）** |
| 记忆 | 会话级 ChatMemory(6/6)、**跨工作流 ProjectMemory（Redis/账号维度/30d，新增）** |
| Agent 平台 | 8 Agent 注册表、MCP Server（/mcp）、A2A 信封、事件 Outbox、崩溃恢复 |
| 评测 | LLM-as-Judge 自动判分 + CI 门禁；**自我改进闭环（评测失败→自动优化迭代，新增）** |
| 模型 | 阶段路由/maxTokens/熔断/预算告警；**动态模型路由网关（三档模型，新增，渐进开启）** |
| 可观测 | OTel span + Jaeger、Prometheus/Grafana、LLM trace 表（traceId 关联） |
| 治理 | JWT + RBAC（ADMIN/CREATOR/VIEWER）、操作审计、安全护栏 |

## 四、实证检查结果

### 4.1 构建

- ✅ 后端编译：通过（修复了 `WorkflowControllerTest` 构造器缺 `ProjectMemoryService` 参数的编译错误）
- ✅ 前端构建：`npm run build` 通过（173 模块，2.55s）

### 4.2 测试（关键发现：当前 CI 会红）

**308 用例：299 通过，1 失败 + 8 错误。**

| 失败项 | 数量 | 根因 |
|--------|:--:|------|
| `AuthIntegrationTest` / `WorkflowApiIntegrationTest`（集成） | 6 | **schema.sql 在 H2 测试库执行失败** → ApplicationContext 无法加载。PG 专用 DDL（如 `ALTER TABLE ... ADD COLUMN IF NOT EXISTS`）与 H2 不兼容；测试仍用 H2 内存库 |
| `GuardedChatModelTest` | 2 | OTel 改造后 mock `Tracer.nextSpan()` 返回 null → NPE（测试桩未更新） |
| `WorkflowControllerTest.selectPlatforms_shouldCallService` | 1 | 断言漂移：控制器 select-platforms 签名已变化（新增参数），测试仍按旧签名 verify |

> 结论：单元测试基本健康；集成测试需要迁移到 Testcontainers/PostgreSQL 或让 schema.sql 双库兼容，
> 否则 CI 的 `mvn package`（含测试）必然失败。

### 4.3 服务运行状态（当前环境）

| 服务 | 状态 | 说明 |
|------|:--:|------|
| 后端 :8080 | ✅ UP | 运行中的 jar 为早期单体版本，**与 HEAD 代码不一致**（新模块未部署） |
| 前端 :5173 | ✅ UP | Vite dev |
| PostgreSQL :5433 | ❌ down | 需 `scripts/pg-manage.ps1 start` |
| Prometheus :9090 / Grafana :3000 | ❌ down | 需 `scripts/observability-manage.ps1 start` |

### 4.4 安全与密钥

- ✅ 暂存区扫描无真实密钥（`sk-*` / `ark-*` / `ghp_` / 密码均未发现）
- ✅ `.env`、`local/alerts.env`、`.pgdata`、日志、构建产物均已 gitignore
- ✅ `application.yml` 的 DeepSeek / DashScope / 火山引擎 key 已替换为占位符
- ⚠️ 注意：`content-ops-configs/.env` 与 `local/alerts.env` 是本地密钥文件，**永不提交**；
  新机器按 `alerts.env.example` 与 `.env.example` 自行填写

### 4.5 Git 状态

- ✅ 已初始化仓库，remote 指向 `WengWengOK/content-ops-platform`
- ✅ 本地已有 6 个提交，HEAD = `f4156d1`
- ⚠️ **存在未提交 WIP**（约 10 个文件）：新模块的 `AgentExecuteRequest/ToolsContracts`、
  `orchestrator/gateway/`、`worker/tools` 内部契约控制器、`validation/`、`content-outputs/` 等
- ⚠️ `git fetch origin` 因网络连接重置失败，远程同步状态待确认（此前提交已注明“同步本地最新代码至远程”）

### 4.6 评测门禁

- ✅ `scripts/eval-gate.mjs` 本地实测通过（选题 85 / 正文 72，阈值 70，exit 0）
- ✅ 流水线自动判分落库（真实工作流选题自动评分 92）
- ⚠️ 评估集仅 2 条，规模不足，需扩充至 50-100 条并做标注回流

## 五、大厂差距评分（独立复核）

引用并复核 [EVALUATION-2026-08-18.md](EVALUATION-2026-08-18.md) v2：

| 维度 | 评分 | 复核意见 |
|------|:--:|------|
| Agent 编排 | 4.8 | 认可；PG 状态 + 锁 + 恢复 + Outbox |
| 模型成本控制 | 5.8 | 认可；动态路由网关为亮点（默认渐进开启） |
| RAG | 5.0 | 认可；输出回流闭环补上了“只读不写”短板 |
| 实时能力 | 4.5 | 认可 |
| 安全与租户 | 3.8 | 认可；匿名兜底仍在，生产需强制登录 |
| 评测体系 | 5.2 | 认可；自我改进闭环 + CI 门禁，但评估集规模不足 |
| 可观测性 | 4.2 | 认可；工具调用/RAG span 覆盖仍不全 |
| 标准化接入 | 4.2 | 认可 |
| 记忆与上下文 | 5.8 | 认可；缺分层上下文压缩 |
| 部署运维 | 4.0 | **复核下调 0.3→3.7**：服务化拆分骨架已建，但模块间大量未提交、集成测试红、无 K8s |
| **综合底座** | **7.9** | 复核区间 **7.6-7.9**；取决于能否快速让测试套件与拆分骨架落地 |
| 功能综合 | 8.2 | 认可 |

## 六、发现的问题（按优先级）

### P0（先修）
1. **测试套件 9 处失败**：集成测试因 schema.sql 与 H2 不兼容无法启动上下文；2 处 OTel mock NPE；
   1 处 select-platforms 断言漂移。当前 CI 必红，需先让 `mvn test` 全绿
2. **拆分骨架未提交**：orchestrator/worker/tools 的新契约与网关代码在工作区未提交，存在丢失风险，
   且三模块与 legacy 的依赖关系（全量扫包）是否真正独立待验证
3. **运行版本与 HEAD 不一致**：8080 上跑的是旧 jar，新模块/新功能未实际部署验证过端到端

### P1
4. 本地基础设施（PG/Prometheus/Grafana）未运行，可观测闭环无法即时验证
5. 集成测试基建落后：应迁移 Testcontainers（PostgreSQL），或让 schema.sql 兼容 H2/PG
6. 密钥治理提醒：CI 的 `OPENAI_API_KEY` Secret 未配置时评测门禁自动跳过（可接受，但需在 README 说明）

### P2
7. README / GAP 文档描述仍以单体为主，需随多模块结构更新
8. 评估集仅 2 条，需规模化

## 七、改进建议（排序）

1. **让测试全绿**（P0）：修复 GuardedChatModelTest mock（NoopTracer）、更新 select-platforms 断言、
   集成测试切 Testcontainers 或 schema 双库兼容 → CI 恢复可信
2. **提交拆分骨架**（P0）：审阅并提交未跟踪的模块代码，跑一次三模块构建 + `docker-compose-phase3.yml`
   冒烟，验证“功能 100% 等价单体”的声明
3. **部署新版本**：以 HEAD 构建单体 jar 或三服务镜像，替换 8080 旧进程，恢复 PG/可观测服务，
   做一轮真实工作流端到端验证
4. 扩充评估集、README 多模块化更新、K8s/Helm 与 HPA（进入规模化前）

## 八、结论

项目功能与工程底座已达到「**内部生产级候选**」（底座 7.6-7.9 / 功能 8.2），
大厂特色（记忆、动态路由、自我改进、MCP、OTel、评测门禁）均已落地并有契约支撑后续拆分。

当前最大风险不在功能，而在**工程卫生**：测试套件 9 处失败、拆分骨架未提交、运行版本落后 HEAD。
先花 1-2 天把「测试全绿 + 骨架提交 + 新版本部署」三项收口，即可宣称达到内部生产级并进入对外规模化准备。
