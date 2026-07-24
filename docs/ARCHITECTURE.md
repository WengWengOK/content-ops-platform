# Content Ops Agent Platform — 多Agent微服务架构设计文档

> 基于 TRAE Work 内容运营6环节流水线，采用 Spring AI + LangChain4j + Spring Cloud 构建的多Agent微服务平台

---

## 一、架构总览

### 1.1 设计理念

本项目将内容运营的全流程拆分为 **6个环节**，每个环节对应一个独立的 **AI Agent 微服务**。采用 **Pipeline/Sequential（流水线式）** 多Agent编排模式：

- 每个 Agent 专注于一个环节，拥有独立的 **System Prompt** 和 **工具集（Tools）**
- 上游 Agent 的产出自动成为下游 Agent 的输入（Artifact 传递）
- **Orchestrator（编排器）** 统一调度全链路，支持自动流转和人工审核（Human-in-the-loop）
- **Redis** 存储 Workflow 状态和累积产出，**Kafka** 发布阶段转换事件
- **Eureka** 做服务发现，**Feign** 做服务间同步调用

### 1.2 技术栈选型

| 层级 | 技术 | 版本 | 选型理由 |
|------|------|------|----------|
| AI框架 | LangChain4j | 1.0.1 | 声明式 AI Service（@AiService），结构化输出，@Tool 工具调用 |
| AI框架 | Spring AI | 1.0.0 | ChatClient 适配器，未来可扩展 Advisor、RAG |
| 基础框架 | Spring Boot | 3.4.5 | 企业级微服务基座 |
| 微服务 | Spring Cloud | 2024.0.1 | Eureka 服务发现 + OpenFeign 声明式调用 |
| 消息队列 | Kafka | - | 异步阶段转换事件，解耦编排器与Agent |
| 状态存储 | Redis | - | Workflow 状态管理，Artifact 累积传递 |
| 语言 | Java | 21 | 虚拟线程、模式匹配、文本块 |
| 构建工具 | Maven | 3.6+ | 多模块管理 |

### 1.3 架构图

```
                          ┌─────────────────────────────────────────────────────┐
                          │              API Gateway (Orchestrator)               │
                          │          POST /api/v1/workflow/start                   │
                          │          GET  /api/v1/workflow/{id}/status           │
                          │          POST /api/v1/workflow/{id}/approve           │
                          └──────────────────────┬──────────────────────────────┘
                                                  │
                    ┌─────────────────────────────┼─────────────────────────────┐
                    │              Pipeline Orchestrator                        │
                    │    (Sequential Pipeline + Human-in-the-loop)              │
                    │                                                           │
                    │  1.Topic → 2.Content → 3.Image → 4.Publish               │
                    │                                    ↓                      │
                    │  6.Optimize ← 5.Analysis                                  │
                    └─────┬──────────┬──────────┬──────────┬──────────┬─────────┘
                          │          │          │          │          │
                    ┌─────▼──┐ ┌─────▼──┐ ┌─────▼──┐ ┌──────▼─┐ ┌──────▼─┐ ┌──────▼─┐
                    │ Topic  │ │Content │ │ Image  │ │Publish │ │Analysis│ │Optimize│
                    │ Agent  │ │ Agent  │ │ Agent  │ │ Agent  │ │ Agent  │ │ Agent  │
                    │ :8081  │ │ :8082  │ │ :8083  │ │ :8084  │ │ :8085  │ │ :8086  │
                    └───┬────┘ └───┬────┘ └───┬────┘ └───┬────┘ └───┬────┘ └───┬────┘
                        │          │          │          │          │          │
                   ┌────┴──────────┴──────────┴──────────┴──────────┴──────────┴────┐
                   │                    基础设施层                                    │
                   │  ┌─────────┐  ┌─────────┐  ┌─────────┐  ┌─────────────────┐  │
                   │  │ Eureka  │  │  Redis  │  │  Kafka  │  │  Observability   │  │
                   │  │ :8761   │  │ :6379   │  │ :9092   │  │  Actuator+OTel   │  │
                   │  └─────────┘  └─────────┘  └─────────┘  └─────────────────┘  │
                   └───────────────────────────────────────────────────────────────┘
```

---

## 二、6个 Agent 提示词设计

### Agent 1: 选题策划 Agent (Topic Planning)

**服务**: `content-ops-agent-topic` (port 8081)
**温度**: 0.8 (创意型)
**结构化输出**: `TopicPlanResult`

```text
你是「选题策划Agent」，一个专业的内容选题分析师。你的核心任务是帮自媒体创作者做选题决策。

你的能力包括：
- 联网搜索当前热点话题和趋势
- 分析竞品账号的内容方向  
- 结合账号定位推荐选题
- 为每个选题提供切入角度和预期效果

工作原则：
1. 先明确账号定位（领域、目标受众、风格调性）
2. 联网搜索该领域近7天的热门话题和关键词
3. 分析竞品在同类话题上的表现
4. 推荐3-5个选题，每个包含：标题、切入角度、推荐理由、预期互动率
5. 为不同平台适配标题（公众号、小红书、头条等）

输出要求：
- 提供3-5个选题候选
- 每个选题附带关键词标签
- 给出平台适配的标题变体
- 包含趋势关键词列表
- 输出竞品分析摘要和推荐方向
```

**工具集**:
- `searchTrendingTopics(niche)` — 搜索领域近期热点
- `analyzeCompetitors(niche)` — 竞品内容方向分析
- `getHotSearchRanking(platform)` — 平台热搜榜单

---

### Agent 2: 内容创作 Agent (Content Creation)

**服务**: `content-ops-agent-content` (port 8082)
**温度**: 0.8 (创意型)
**结构化输出**: `ContentDraftResult`

```text
你是「内容创作Agent」，一个专业的自媒体文案撰写助手。你的核心任务是根据选题生成高质量的文章初稿。

工作原则：
1. 先搭建文章框架（大纲），包含：开头引入、正文分段、结尾总结
2. 框架确认后再生成完整初稿
3. 风格匹配账号定位（轻松/专业/感性等）
4. 结合真实场景和例子，而非空泛说教
5. 生成多个标题变体供选择
6. 生成摘要和标签

文章结构要求：
- 开头：用场景/故事/提问引入，抓住注意力
- 正文：每段有明确主题，用案例支撑观点
- 结尾：总结升华，引导互动
- 字数：1500-3000字
- 格式：Markdown

输出要求：
- 文章框架（大纲）
- 完整Markdown初稿
- 3-5个标题变体
- 5-10个标签
- 100字以内的分享摘要
```

**工具集**:
- `generateOutline(topic, angle)` — 生成文章框架大纲
- `searchExamples(topic)` — 搜索相关案例素材
- `generateTags(content)` — 生成SEO标签

---

### Agent 3: 配图设计 Agent (Image Design)

**服务**: `content-ops-agent-image` (port 8083)
**温度**: 0.8 (创意型)
**结构化输出**: `ImageDesignResult`

```text
你是「配图设计Agent」，一个专业的AI图片生成规划师。你的核心任务是根据文章内容生成合适的配图和封面。

工作原则：
1. 分析文章内容，提取核心视觉元素
2. 为每个配图位置生成详细的图片描述提示词
3. 为不同平台生成不同尺寸的封面图
4. 确保配图风格与文章调性一致
5. 生成后可去除水印

配图规则：
- 文章配图：2-3张，分别用于开头、文中、结尾
- 封面图：每个目标平台一张
  * 公众号：横版 900x383px
  * 小红书：竖版 1080x1440px
  * 头条：横版 660x370px
- 图片风格：暖色调、有生活气息、与内容匹配
- 避免过于抽象或与内容无关的图片

输出要求：
- 生成图片列表（每张配图的prompt和位置）
- 平台封面列表（每个平台封面的尺寸和描述）
```

**工具集**:
- `extractVisualKeywords(articleContent)` — 提取视觉关键词
- `generateImagePrompt(scene, mood, style)` — 生成图片描述提示词
- `removeWatermark(imageUrl)` — 去除图片水印

---

### Agent 4: 排版发布 Agent (Publishing)

**服务**: `content-ops-agent-publish` (port 8084)
**温度**: 0.1 (精确型)
**结构化输出**: `PublishResult`

```text
你是「排版发布Agent」，一个专业的多平台内容排版和发布助手。你的核心任务是将文章初稿排版为各平台适配的格式。

工作原则：
1. 根据目标平台调整排版格式
2. 插入配图到正确位置
3. 优化段落长度和阅读节奏
4. 添加适当的emoji和分隔符（根据平台风格）
5. 生成各平台的最终发布内容

平台适配规则：
- 公众号：支持富文本，段落短，重点加粗，图片居中
- 小红书：短段落，emoji丰富，口语化，图片穿插
- 头条：段落适中，小标题清晰，文末引导关注
- 知乎：专业排版，引用规范，逻辑清晰

输出要求：
- 每个平台的排版后内容
- 发布状态和URL（如果支持自动发布）
```

**工具集**:
- `convertToPlatformFormat(markdown, platform)` — Markdown转平台富文本
- `optimizeReadability(content, platform)` — 优化段落和阅读节奏
- `generateChecklist(platform)` — 生成发布检查清单

---

### Agent 5: 数据分析 Agent (Data Analysis)

**服务**: `content-ops-agent-analysis` (port 8085)
**温度**: 0.3 (分析型)
**结构化输出**: `AnalysisReport`

```text
你是「数据分析Agent」，一个专业的自媒体数据分析师。你的核心任务是分析内容运营数据，输出可执行的洞察。

工作原则：
1. 接收后台导出的数据（阅读量、点赞、转发、评论、粉丝变化等）
2. 按月分析趋势，而非单篇
3. 分析哪些类型的文章表现好
4. 分析哪些时间段发文效果更好
5. 生成可视化图表数据
6. 输出可执行的具体建议

分析维度：
- 内容类型分析：不同主题/类型的平均表现对比
- 时间分析：星期几/时间段的表现差异
- 互动分析：完读率、互动率最高的内容特征
- 趋势分析：粉丝增长趋势、阅读量变化

输出要求：
- 核心指标摘要（平均阅读量、互动率、涨粉数等）
- 各类内容表现对比
- 时间段表现分析
- 关键洞察列表
- 具体建议列表
- 图表数据（JSON格式，可用于前端渲染）
```

**工具集**:
- `calculateMetrics(rawData)` — 计算平均表现指标
- `analyzeByCategory(rawData)` — 按内容类型分组分析
- `analyzeByTimeSlot(rawData)` — 按发布时间分析最佳时段
- `generateChartData(metricsData, chartType)` — 生成可视化图表数据

---

### Agent 6: 优化迭代 Agent (Optimization)

**服务**: `content-ops-agent-optimize` (port 8086)
**温度**: 0.3 (分析型)
**结构化输出**: `OptimizationResult`

```text
你是「优化迭代Agent」，一个专业的内容运营策略优化师。你的核心任务是根据数据分析结果调整运营策略。

工作原则：
1. 接收数据分析Agent的输出
2. 识别表现好的方向和需要改进的方向
3. 输出具体的策略调整建议
4. 推荐下一周期的选题方向
5. 总结本周期的经验教训
6. 给出运营健康评分

优化维度：
- 内容类型调整：哪些类型应该多做/少做
- 发布时间优化：最佳发布时间窗口
- 平台重心调整：哪个平台值得更多投入
- 内容风格微调：调性、长度、互动方式
- 选题方向：基于数据推荐下周期3-5个选题

输出要求：
- 策略调整列表（维度、当前值、建议值、理由、预期影响）
- 下周期推荐选题（3-5个）
- 经验总结
- 运营健康评分（0-100）
- 周期总结
```

**工具集**:
- `identifyGaps(currentStrategy, analysisData)` — 对比策略与数据差距
- `generateStrategyRecommendations(gapAnalysis)` — 生成策略调整建议
- `calculateHealthScore(metricsData)` — 评估运营健康度
- `recommendNextTopics(analysisData, accountNiche)` — 推荐下周期选题

---

## 三、核心架构设计

### 3.1 流水线编排模式 (Pipeline Orchestration)

```
                         ┌──────────────────────────────────────────────┐
                         │           WorkflowController                   │
                         │  start() → 创建Workflow → 执行Stage 1        │
                         │  approve() → 人工审核通过 → 执行下一Stage    │
                         │  status() → 查询当前状态                      │
                         └──────────────┬───────────────────────────────┘
                                        │
                         ┌──────────────▼───────────────────────────────┐
                         │         PipelineOrchestrator                  │
                         │                                              │
                         │  1. 加载 accumulatedArtifacts (Redis)        │
                         │  2. 构建 AgentTaskRequest                    │
                         │  3. Feign → 调用对应 Agent 微服务           │
                         │  4. 合并产出到 accumulatedArtifacts          │
                         │  5. 检查 requireHumanReview                  │
                         │     ├─ true  → 暂停等待人工审核              │
                         │     └─ false → 自动推进到下一Stage           │
                         │  6. 发布 StageTransitionEvent (Kafka)        │
                         └──────────────┬───────────────────────────────┘
                                        │
              ┌─────────────────────────┼─────────────────────────────┐
              │                         │                               │
     ┌────────▼────────┐      ┌─────────▼─────────┐          ┌────────▼────────┐
     │   Topic Agent   │ ───▶ │   Content Agent    │  ────▶  │   Image Agent   │
     │  选题策划       │      │   内容创作         │          │   配图设计      │
     │  port: 8081     │      │   port: 8082       │          │   port: 8083    │
     └─────────────────┘      └────────────────────┘          └─────────────────┘
                                                                       │
              ┌──────────────────────────────────────────────────────┘
              │
     ┌────────▼────────┐      ┌─────────▼─────────┐          ┌────────▼────────┐
     │  Publish Agent  │ ───▶ │  Analysis Agent    │  ────▶  │  Optimize Agent │
     │  排版发布       │      │   数据分析         │          │   优化迭代      │
     │  port: 8084     │      │   port: 8085       │          │   port: 8086    │
     └─────────────────┘      └────────────────────┘          └────────┬────────┘
                                                                      │
                                                          ┌───────────┘
                                                          │ (循环回Topic)
                                                          ▼
                                                    下一运营周期
```

### 3.2 Artifact 传递机制

每个 Stage 的产出通过 **Redis 累积存储** 传递给下一 Stage：

```java
// PipelineOrchestrator.handleStageSuccess()
context.getAccumulatedArtifacts().put(stage.getCode(), response.getData());
stateManager.saveWorkflowState(context.getWorkflowId(), context);
```

**数据流向**:

| Stage | 产出 Artifact Key | 消费方 |
|-------|-------------------|--------|
| topic-planning | `topics`, `trendingKeywords`, `recommendedDirection` | content-creation |
| content-creation | `outline`, `draftContent`, `titleVariations`, `tags` | image-design, publishing |
| image-design | `images`, `covers` | publishing |
| publishing | `publications`, `status` | data-analysis |
| data-analysis | `keyMetrics`, `insights`, `recommendations`, `chartData` | optimization |
| optimization | `strategyAdjustments`, `recommendedTopics`, `healthScore` | (循环) topic-planning |

### 3.3 Human-in-the-Loop 机制

```java
// 流程控制逻辑
if (context.isRequireHumanReview()) {
    context.setStatus(TaskStatus.AWAITING_HUMAN.name());
    // 暂停，等待 POST /workflow/{id}/approve
} else {
    // 自动推进到下一 Stage
    AgentStage nextStage = stage.next();
    context.setCurrentStage(nextStage.getCode());
    orchestrator.executeStage(context);
}
```

### 3.4 服务发现与通信

```
                    Eureka Server (:8761)
                    ┌───────────────────────┐
                    │  Service Registry      │
                    │  ┌──────────────────┐ │
                    │  │ orchestrator     │ │
                    │  │ topic-agent      │ │
                    │  │ content-agent    │ │
                    │  │ image-agent      │ │
                    │  │ publish-agent    │ │
                    │  │ analysis-agent   │ │
                    │  │ optimize-agent    │ │
                    │  └──────────────────┘ │
                    └───────────────────────┘
                            ▲
                            │ Feign Client
                            │ (基于服务名调用)
                   ┌────────┴────────┐
                   │  Orchestrator   │
                   │  Feign 调用      │
                   │  topic-agent    │──┐
                   │  content-agent  │  │ REST/gRPC
                   │  image-agent    │──┤
                   │  publish-agent  │  │
                   │  analysis-agent│──┤
                   │  optimize-agent│  │
                   └─────────────────┘  │
                                        ▼
                              各 Agent 微服务
```

---

## 四、项目结构

```
content-ops-agent-platform/
├── pom.xml                          # 父POM (Spring Boot 3.4.5, Spring Cloud 2024.0.1)
├── content-ops-common/              # 公共模块
│   ├── constant/AgentConstants.java # 平台常量
│   ├── dto/                         # 数据传输对象
│   │   ├── TaskContext.java         # 工作流上下文（含AccountProfile）
│   │   ├── AgentResponse.java       # 统一响应包装
│   │   ├── TopicPlanResult.java     # 选题结果
│   │   ├── ContentDraftResult.java  # 内容初稿结果
│   │   ├── ImageDesignResult.java   # 配图结果
│   │   ├── PublishResult.java       # 发布结果
│   │   ├── AnalysisReport.java      # 分析报告
│   │   └── OptimizationResult.java  # 优化结果
│   ├── enums/
│   │   ├── AgentStage.java          # 6阶段枚举（含循环）
│   │   └── TaskStatus.java          # 任务状态
│   ├── event/
│   │   ├── AgentTaskRequest.java     # Agent任务请求
│   │   └── StageTransitionEvent.java # 阶段转换事件
│   └── util/
│       └── WorkflowStateManager.java # Redis状态管理器
│
├── content-ops-orchestrator/        # 编排器微服务 (:8080)
│   ├── controller/WorkflowController.java   # REST API入口
│   ├── service/
│   │   ├── WorkflowService.java             # 工作流业务逻辑
│   │   └── AgentFeignClients.java           # 6个Agent的Feign客户端
│   └── workflow/PipelineOrchestrator.java   # 流水线编排核心
│
├── content-ops-agent-topic/        # 选题策划Agent (:8081)
│   ├── agent/TopicPlanningAgent.java  # @AiService + @SystemMessage
│   ├── tool/TopicResearchTools.java   # @Tool 热点搜索/竞品分析
│   ├── controller/TopicAgentController.java
│   └── config/TopicAgentConfig.java   # AiServices.builder()
│
├── content-ops-agent-content/      # 内容创作Agent (:8082)
│   ├── agent/ContentCreationAgent.java
│   ├── tool/ContentTools.java
│   ├── controller/ContentAgentController.java
│   └── config/ContentAgentConfig.java
│
├── content-ops-agent-image/        # 配图设计Agent (:8083)
│   ├── agent/ImageDesignAgent.java
│   ├── tool/ImageTools.java
│   ├── controller/ImageAgentController.java
│   └── config/ImageAgentConfig.java
│
├── content-ops-agent-publish/      # 排版发布Agent (:8084)
│   ├── agent/PublishingAgent.java
│   ├── tool/PublishTools.java
│   ├── controller/PublishAgentController.java
│   └── config/PublishAgentConfig.java
│
├── content-ops-agent-analysis/    # 数据分析Agent (:8085)
│   ├── agent/DataAnalysisAgent.java
│   ├── tool/AnalysisTools.java
│   ├── controller/AnalysisAgentController.java
│   └── config/AnalysisAgentConfig.java
│
└── content-ops-agent-optimize/     # 优化迭代Agent (:8086)
    ├── agent/OptimizationAgent.java
    ├── tool/OptimizeTools.java
    ├── controller/OptimizeAgentController.java
    └── config/OptimizeAgentConfig.java
```

---

## 五、Agent 间通信协议

### 5.1 同步调用 (Feign)

Orchestrator 通过 Feign Client 同步调用各 Agent：

```java
@FeignClient(name = "content-ops-agent-topic")
public interface TopicAgentClient {
    @PostMapping("/api/v1/topic/execute")
    AgentResponse<Map<String, Object>> execute(@RequestBody AgentTaskRequest request);
}
```

### 5.2 异步事件 (Kafka)

阶段转换通过 Kafka 发布事件，用于可观测性：

```java
kafkaTemplate.send("content-ops.task.events", workflowId, 
    StageTransitionEvent.completed(workflowId, "topic-planning", "content-creation", artifacts));
```

### 5.3 状态共享 (Redis)

Workflow 状态和累积产出存储在 Redis，所有服务可读写：

```
Key:   contentops:workflow:{workflowId}
Value: TaskContext (JSON序列化)
TTL:   24h
```

---

## 六、API 接口

### 6.1 Orchestrator API

| Method | Path | 描述 |
|--------|------|------|
| POST | `/orchestrator/api/v1/workflow/start` | 启动新的内容运营工作流 |
| GET | `/orchestrator/api/v1/workflow/{workflowId}/status` | 查询工作流状态 |
| POST | `/orchestrator/api/v1/workflow/{workflowId}/approve` | 人工审核通过，继续下一阶段 |
| GET | `/orchestrator/api/v1/workflow/stages` | 获取所有流水线阶段 |

### 6.2 Agent API (内部调用)

每个 Agent 服务暴露统一接口：

| Agent | Path | 端口 |
|-------|------|------|
| Topic | `/topic-agent/api/v1/topic/execute` | 8081 |
| Content | `/content-agent/api/v1/content/execute` | 8082 |
| Image | `/image-agent/api/v1/image/execute` | 8083 |
| Publish | `/publish-agent/api/v1/publish/execute` | 8084 |
| Analysis | `/analysis-agent/api/v1/analysis/execute` | 8085 |
| Optimize | `/optimize-agent/api/v1/optimize/execute` | 8086 |

---

## 七、启动顺序

1. **基础设施**: Eureka (:8761) → Redis (:6379) → Kafka (:9092)
2. **Agent 微服务**: 6个 Agent 服务可并行启动
3. **Orchestrator**: 最后启动，注册到 Eureka 后即可接收请求

```bash
# 启动示例
java -jar content-ops-agent-topic/target/content-ops-agent-topic-1.0.0-SNAPSHOT.jar
java -jar content-ops-agent-content/target/content-ops-agent-content-1.0.0-SNAPSHOT.jar
# ... 其他4个Agent
java -jar content-ops-orchestrator/target/content-ops-orchestrator-1.0.0-SNAPSHOT.jar
```

---

## 八、扩展性设计

### 8.1 横向扩展
- 每个 Agent 微服务可独立扩容，通过 Eureka 自动负载均衡
- 高负载 Agent（如 Content）可部署多实例

### 8.2 新增 Agent
1. 创建新的 Maven 模块 `content-ops-agent-xxx`
2. 实现 Agent 接口 + Tools + Controller + Config
3. 在 `AgentStage` 枚举中新增阶段
4. 在 `PipelineOrchestrator` 中新增 Feign 调用分支

### 8.3 替换 AI 框架
- LangChain4j 的 `@AiService` 接口可替换为 Spring AI 的 `ChatClient`
- Tools 层抽象独立，可复用
- DTO 层与框架无关

### 8.4 MCP 集成路线
- 将 `@Tool` 方法封装为 MCP Server
- 支持跨 Agent 的工具共享
- 集成 Nacos MCP Registry 做工具发现
