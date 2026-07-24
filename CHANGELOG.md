# Changelog

All notable changes to this project will be documented in this file.

## [Unreleased]

### Added
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
