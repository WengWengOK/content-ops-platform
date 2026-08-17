# 大厂AI应用开发改造指南

> 改造日期：2026-07-30
> 目标：对标阿里/字节/腾讯AI应用开发工程师岗位要求，补全项目能力短板

---

## 一、改造背景

基于大厂AI应用开发岗位招聘要求分析，当前项目存在以下核心短板：

| 能力维度 | 改造前 | 大厂要求 | 差距 |
|---------|--------|---------|------|
| RAG全链路 | 35 | 90 | -55 |
| 多Agent协作 | 45 | 90 | -45 |
| 模型微调部署 | 10 | 85 | -75 |
| 多模态能力 | 25 | 80 | -55 |
| 安全合规 | 40 | 85 | -45 |
| 工程化/LLMOps | 60 | 90 | -30 |
| 可观测性 | 55 | 85 | -30 |
| Prompt工程 | 75 | 90 | -15 |

---

## 二、改造内容

### 2.1 RAG全链路升级（`common.rag` 包）

**新增6个核心类：**

| 类名 | 职责 |
|------|------|
| `DocumentChunker` | 文档分块：递归字符/语义/固定大小三种策略，中文友好 |
| `HybridSearchService` | 混合检索：PGVector向量 + BM25关键词 + RRF融合 |
| `RerankService` | 重排序：规则（关键词匹配+位置加权）+ Cross-Encoder |
| `DocumentIngestionPipeline` | 文档摄入流水线：解析→清洗→分块→向量化→存储 |
| `AdvancedRagService` | 高级RAG：查询重写(HyDE/Multi-query) + 混合检索 + 重排序 |
| `RagProperties` | 配置属性：分块/检索/重排序/摄入/查询重写 |

**面试考点覆盖：**
- 向量检索 vs 关键词检索的优劣
- BM25算法原理（TF-IDF + 文档长度归一化）
- RRF融合公式：`fusedScore = vw/(k+rank_v) + kw/(k+rank_k)`
- HyDE（Hypothetical Document Embedding）查询重写
- 文档分块策略选择（语义分块 vs 固定大小）

### 2.2 多Agent协作框架（`common.agent` 包）

**新增9个核心类：**

| 类名 | 职责 |
|------|------|
| `AgentRole` | Agent角色定义（SUPERVISOR/RESEARCHER/WRITER/REVIEWER/CRITIC） |
| `AgentTask` | 任务封装 + DAG依赖管理 + 拓扑排序 |
| `AgentResult` | 结果封装 + merge()聚合 |
| `AgentCommunicationProtocol` | Agent间通信：点对点 + 广播 + 消息队列 |
| `ReActAgentExecutor` | ReAct模式：Thought→Action→Observation循环 |
| `PlanAndExecuteAgent` | Plan-Execute模式：分解→执行→重规划 |
| `MultiAgentOrchestrator` | 三种协作模式：Sequential/Parallel/Hierarchical |
| `MultiAgentProperties` | 多Agent配置 |
| `MultiAgentThreadPoolConfig` | 专用线程池配置 |

**面试考点覆盖：**
- ReAct vs Plan-and-Execute 模式对比
- 多Agent协作的三种范式（顺序/并行/层级）
- Agent间通信协议设计
- DAG任务调度与拓扑排序
- CompletableFuture异步并行编排

### 2.3 模型微调与评估（`common.finetune` 包）

**新增6个核心类：**

| 类名 | 职责 |
|------|------|
| `FineTuneProperties` | 微调配置（LoRA参数/超参数/评估/部署） |
| `FineTuneDataset` | 数据集封装（Instruction/Chat/Preference三种格式） |
| `ModelEvaluationService` | 五维评估（准确性/流畅性/相关性/安全性/一致性） |
| `ModelFineTuneManager` | 微调任务编排 + 生命周期管理 |
| `ModelDeploymentManager` | 部署管理（API/本地/混合 + 灰度发布） |
| `ModelAbTest` | A/B测试框架（流量分配 + T检验 + 统计显著性） |

**面试考点覆盖：**
- LoRA微调原理（低秩矩阵分解）
- DPO（Direct Preference Optimization）数据格式
- 模型评估维度设计
- 灰度发布与A/B测试
- Welch's T检验实现统计显著性判断

### 2.4 AI安全合规框架（`common.safety` 包）

**新增6个核心类：**

| 类名 | 职责 |
|------|------|
| `SafetyProperties` | 安全配置（注入检测/内容过滤/输出护栏/PII） |
| `PromptInjectionDetector` | Prompt注入检测（直接/间接/编码绕过） |
| `ContentSafetyFilter` | 内容过滤（敏感词/PII/有害内容） |
| `OutputGuardrail` | 输出护栏（信息泄露/版权/幻觉/格式） |
| `PiiDetector` | PII检测与脱敏（手机号/身份证/邮箱/银行卡） |
| `SafetyGuardService` | 综合守护（inputGuard + outputGuard双层防护） |

**面试考点覆盖：**
- Prompt注入攻击类型与防御
- PII脱敏策略（保留首尾中间打码）
- LLM输出安全护栏设计
- fail-open vs fail-closed 降级策略
- 身份证校验位算法、银行卡Luhn校验

### 2.5 多模态能力（`common.multimodal` 包）

**新增3个核心类：**

| 类名 | 职责 |
|------|------|
| `MultimodalRequestBuilder` | 多模态请求构建（URL/Base64/文件路径） |
| `VisionAnalysisService` | 视觉理解（描述/问答/OCR/分类/相似度） |
| `AudioProcessingService` | 音频处理（STT/TTS/摘要） |

**面试考点覆盖：**
- OpenAI Vision API消息格式
- 多模态输入的Base64编码与降级
- OCR via LLM Vision vs 传统OCR
- 音频处理流水线设计

### 2.6 LLMOps增强（`common.llmops` 包）

**新增3个核心类：**

| 类名 | 职责 |
|------|------|
| `LlmOpsDashboard` | LLMOps仪表盘（Token/延迟/成功率/成本/质量） |
| `ModelRouterWithFallback` | 降级路由（降级链+熔断器+限流+成本优化） |
| `PromptVersionControl` | Prompt版本控制（存储/回滚/diff/AB测试/优化建议） |

**面试考点覆盖：**
- LLMOps核心指标体系
- 模型降级链设计
- 熔断器状态机（CLOSED/OPEN/HALF_OPEN）
- 令牌桶限流算法
- Prompt版本管理与A/B测试

---

## 三、改造统计

| 统计项 | 数量 |
|--------|------|
| 新增包 | 6 (rag, agent, finetune, safety, multimodal, llmops) |
| 新增Java类 | 33 |
| 编译错误 | 0 |
| 配置属性类 | 6 |
| 测试类 | 1 (MultiAgentFrameworkTest) |

---

## 四、面试准备重点

### 4.1 必须能讲清楚的设计决策

1. **为什么选择单体架构而非微服务？**
   - 代码量小（每个Agent 500-1100行）、团队规模小、无独立扩缩容需求
   - 单体减少网络调用开销，降低运维复杂度
   - 通过AgentGateway接口抽象保留未来微服务化能力

2. **RAG混合检索为什么用RRF而非简单加权？**
   - RRF不依赖原始分数的绝对值，只依赖排名
   - 向量检索和BM25的分数不可直接比较（尺度不同）
   - RRF天然解决分数归一化问题

3. **多Agent协作的三种模式如何选择？**
   - Sequential：任务有严格依赖关系（如选题→创作→发布）
   - Parallel：任务独立无依赖（如同时生成多个平台的内容）
   - Hierarchical：复杂任务需要分解（如Supervisor分解给多个Worker）

4. **LoRA微调为什么比全量微调更适合？**
   - 参数量仅为原模型的0.1%-1%，降低GPU显存需求
   - 防止灾难性遗忘
   - 可热插拔：一个基座模型 + 多个LoRA适配器

### 4.2 可能的追问及回答要点

| 追问 | 回答要点 |
|------|---------|
| "RAG检索效果不好怎么优化？" | 分块策略调优 → 嵌入模型换型 → 混合检索 → 重排序 → 查询重写 |
| "多Agent怎么避免死循环？" | 最大迭代次数限制 + 超时控制 + 成本上限 |
| "如何评估LLM应用质量？" | 五维评估 + 人工标注 + A/B测试 + 在线指标监控 |
| "Token成本怎么控制？" | 模型路由(创意用大模型/格式化用小模型) + 缓存 + Prompt压缩 |
| "如何防Prompt注入？" | 输入检测(模式匹配+编码解码) + 系统提示词隔离 + 输出护栏 |

---

## 五、后续建议

1. **添加集成测试**：为每个新模块编写集成测试，使用Testcontainers启动PGVector和Redis
2. **完善配置文件**：在application-dev.yml中添加各模块的配置示例
3. **编写技术博客**：将改造过程整理为技术博客，面试时可作为项目亮点展示
4. **补充架构图**：使用Mermaid绘制每个模块的架构图，方便面试讲解
5. **实际跑通**：配置真实的OpenAI API Key，跑通端到端流程

---

## 六、文件索引

```
content-ops-server/src/main/java/com/contentops/common/
├── rag/                          # RAG全链路
│   ├── RagProperties.java
│   ├── DocumentChunker.java
│   ├── HybridSearchService.java
│   ├── RerankService.java
│   ├── DocumentIngestionPipeline.java
│   └── AdvancedRagService.java
├── agent/                        # 多Agent协作
│   ├── AgentRole.java
│   ├── AgentTask.java
│   ├── AgentResult.java
│   ├── AgentCommunicationProtocol.java
│   ├── ReActAgentExecutor.java
│   ├── PlanAndExecuteAgent.java
│   ├── MultiAgentOrchestrator.java
│   ├── MultiAgentProperties.java
│   └── MultiAgentThreadPoolConfig.java
├── finetune/                     # 模型微调与评估
│   ├── FineTuneProperties.java
│   ├── FineTuneDataset.java
│   ├── ModelEvaluationService.java
│   ├── ModelFineTuneManager.java
│   ├── ModelDeploymentManager.java
│   └── ModelAbTest.java
├── safety/                       # AI安全合规
│   ├── SafetyProperties.java
│   ├── PromptInjectionDetector.java
│   ├── ContentSafetyFilter.java
│   ├── OutputGuardrail.java
│   ├── PiiDetector.java
│   └── SafetyGuardService.java
├── multimodal/                   # 多模态能力
│   ├── MultimodalRequestBuilder.java
│   ├── VisionAnalysisService.java
│   └── AudioProcessingService.java
└── llmops/                       # LLMOps增强
    ├── LlmOpsDashboard.java
    ├── ModelRouterWithFallback.java
    └── PromptVersionControl.java
```
