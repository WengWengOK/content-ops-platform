# Changelog

All notable changes to this project will be documented in this file.

## [Unreleased]

### Added — P1: Prompt Engineering Deep Optimization
- **Few-shot examples**: Each agent's SystemMessage now includes 1-2 high-quality output examples
  - TopicAgent: topic planning examples with unconventional angles
  - ContentAgent: article outline + body snippet example
  - AnalysisAgent: data analysis insights + actionable recommendations example
  - ImageAgent: visual keyword extraction + prompt generation example
  - PublishAgent: platform-specific formatting examples (公众号/小红书)
  - OptimizeAgent: strategy adjustment with quantified impact example
- **Dynamic Prompt assembly** (`PromptFragmentService`): Assembles system messages dynamically based on account profile (niche, tone, platforms)
  - Niche-specific guidance for 5 domains (个人成长/母婴/科技/美食/财经)
  - Tone-specific guidance for 3 styles (轻松/专业/感性)
  - Platform-specific guidance for 3 platforms (公众号/小红书/头条)
- **Unconventional angle guidance** (TopicAgent): At least 1 topic per plan must use reverse thinking, cross-domain analogy, niche perspective, or counter-intuitive data
- **Personal experience injection** (ContentAgent): New `{{personalExperience}}` template variable in `generateOutline`, `generateDraft`, and `createDraft` methods
  - `AccountProfile.personalExperience` field added to TaskContext
  - ContentAgentController extracts personalExperience from inputs/AccountProfile
- **Data questioning methodology** (AnalysisAgent): 5-question framework built into system prompt (trend, content type, timing, engagement, follower growth)
- **Prompt version management** (`PromptVersionService` + `PromptVersionProperties`):
  - Per-agent version control (v1/v2/v3) via `contentops.prompt.agents.*` config
  - Nacos config center integration ready (just add `spring-cloud-starter-alibaba-nacos-config` + `@RefreshScope`)
- **A/B testing framework**: Variant A (standard) and Variant B (experimental) prompt instructions
  - Hash-based traffic splitting via `contentops.prompt.ab-testing.traffic-split`
  - Each agent has variant-specific additional instructions in `PromptFragmentService`
- **Configuration**: `contentops.prompt.*` added to all 6 agent application.yml files

### Added — Previous
- **Multi-Agent Microservice Architecture**: 6 specialized AI Agent microservices for content operations pipeline
  - Topic Planning Agent (port 8081) — trending research, competitor analysis, topic recommendation
  - Content Creation Agent (port 8082) — article outline, draft generation, title variations
  - Image Design Agent (port 8083) — AI image prompt generation, multi-platform cover sizing
  - Publishing Agent (port 8084) — multi-platform formatting, readability optimization
  - Data Analysis Agent (port 8085) — performance metrics, trend analysis, chart data generation
  - Optimization Agent (port 8086) — strategy adjustment, health scoring, next-cycle recommendations
- **Pipeline Orchestrator** (port 8080) — sequential pipeline with human-in-the-loop support
- **Common Module** — shared DTOs, enums, events, Redis state management
- **LangChain4j @AiService integration** — declarative AI interfaces with @Tool calling
- **Spring Cloud microservice stack** — Eureka service discovery, OpenFeign clients, Kafka events
- **Docker Compose** — one-command full stack deployment
- **Architecture documentation** — full design doc with all 6 agent prompts

### Known Limitations (Planned for Future Releases)
- All @Tool methods return simulated data (no real API integration yet)
- No ChatMemory support (agents are stateless single-shot calls)
- No RAG/vector store integration
- No real image generation (prompt only)
- No real platform API publishing
- No streaming responses
- No token cost tracking
