# Roadmap

Based on the original TRAE Work content operations methodology and current implementation gaps.

## v1.0.0 — Initial Architecture (Current)

- [x] 6 Agent microservices with LangChain4j @AiService
- [x] Pipeline Orchestrator with sequential flow
- [x] Redis workflow state management
- [x] Kafka stage transition events
- [x] Eureka service discovery + Feign clients
- [x] Docker Compose deployment
- [x] All 6 agent prompts designed
- [x] 20 @Tool methods (simulated data)

## v1.1.0 — P0: Agent Memory & Multi-turn Dialog

> Original article: "把TRAE当讨论对象，而不是代写工具"

- [ ] Integrate LangChain4j ChatMemoryProvider with Redis backend
- [ ] Add conversation history to TaskContext (field exists, unused)
- [ ] Create DiscussionAgent for exploratory multi-turn topic ideation
- [ ] Support "fuzzy idea → AI asks clarifying questions → confirm → decompose" flow
- [ ] Per-workflowId session isolation

## v1.2.0 — P0: RAG Knowledge Base + File I/O + Web Search

> Original article: "直接读写本地文件、联网把资料抓回来"

- [ ] Integrate PGVector/Milvus for historical article storage
- [ ] Add FileTools: readLocalFile(), writeLocalFile()
- [ ] TopicResearchTools: real WebSearch API (SerpAPI/Tavily)
- [ ] RAG retrieval in TopicAgent for similar historical topics
- [ ] RAG retrieval in OptimizeAgent for historical performance matching
- [ ] Agent outputs saved as Markdown/JSON files

## v1.3.0 — P1: Progressive Generation (Two-phase Agents)

> Original article: "先搭框架，别一步到位"

- [ ] Split ContentCreationAgent: generateOutline() → confirm → generateDraft()
- [ ] Split ImageDesignAgent: generateStyleDirections() → confirm → generateImages()
- [ ] Add sub-stage concept to PipelineOrchestrator
- [ ] Human confirmation checkpoint between sub-stages

## v1.4.0 — P1: Prompt Engineering Deep Optimization

> Original article: "需求越具体，产出越好"

- [ ] Add few-shot examples to each agent's SystemMessage
- [ ] Dynamic prompt assembly based on AccountProfile (niche/audience/tone)
- [ ] TopicAgent: "unconventional angle exploration" directive
- [ ] ContentAgent: {{personalExperience}} injection variable
- [ ] AnalysisAgent: "monthly trends, ask the right questions" methodology
- [ ] Prompt version management via Nacos config center
- [ ] A/B testing framework for prompt variants

## v1.5.0 — P1: Resilience & Observability

- [ ] Resilience4j CircuitBreaker + Retry on LLM API calls
- [ ] Micrometer token cost tracking per workflow/agent/stage
- [ ] OpenTelemetry + Jaeger distributed tracing
- [ ] Kafka async mode for long-running agents (Content, Image)
- [ ] Prometheus/Grafana dashboards

## v2.0.0 — P2: MCP Protocol + Real Tool Integration

- [ ] Wrap @Tool methods as MCP Server
- [ ] ImageAgent: DALL-E / Stable Diffusion API integration
- [ ] PublishAgent: WeChat / Xiaohongshu platform API integration
- [ ] AnalysisAgent: platform backend data API integration
- [ ] Nacos MCP Registry for tool discovery

## v2.1.0 — P2: Multi-model Routing + Quality Assessment

- [ ] Model routing strategy (creative → high-temp large model, formatting → low-cost model)
- [ ] QualityAgent: score each stage output (logic/readability/originality)
- [ ] Competitive mode: parallel calls on key stages, select best result
- [ ] Streaming responses for ContentAgent
- [ ] Auto-retry on low quality scores

## v2.2.0 — P2: Methodology Prompt Systematization

> Original article: 4 practical tips as executable constraints

- [ ] "Specificity checker": Orchestrator validates input completeness before calling agent
- [ ] "Discussion mode": TopicAgent asks 3 clarifying questions when input is vague
- [ ] "Trend not single-article": AnalysisAgent forced monthly aggregation
- [ ] "Help not replace": each agent outputs "human action needed" checklist
