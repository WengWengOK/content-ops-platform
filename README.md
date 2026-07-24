# Content Ops Agent Platform

> 基于 LangChain4j + Spring AI + Spring Cloud 的多Agent微服务内容运营平台

将内容运营全流程拆分为 **6个环节**，每个环节由一个独立的 AI Agent 微服务处理，通过 Pipeline 编排器串联成完整流水线。

## 快速开始

### 环境要求
- Java 21+
- Maven 3.6+
- Docker & Docker Compose
- OpenAI API Key

### 构建项目
```bash
cd content-ops-agent-platform
mvn clean package -DskipTests
```

### Docker Compose 一键启动
```bash
export OPENAI_API_KEY=your-api-key
docker-compose up -d
```

### 本地启动（需要先启动 Eureka、Redis、Kafka）
```bash
# 启动6个Agent + 1个Orchestrator
java -jar content-ops-agent-topic/target/content-ops-agent-topic-1.0.0-SNAPSHOT.jar &
java -jar content-ops-agent-content/target/content-ops-agent-content-1.0.0-SNAPSHOT.jar &
java -jar content-ops-agent-image/target/content-ops-agent-image-1.0.0-SNAPSHOT.jar &
java -jar content-ops-agent-publish/target/content-ops-agent-publish-1.0.0-SNAPSHOT.jar &
java -jar content-ops-agent-analysis/target/content-ops-agent-analysis-1.0.0-SNAPSHOT.jar &
java -jar content-ops-agent-optimize/target/content-ops-agent-optimize-1.0.0-SNAPSHOT.jar &
java -jar content-ops-orchestrator/target/content-ops-orchestrator-1.0.0-SNAPSHOT.jar
```

## API 使用

### 启动内容运营工作流
```bash
curl -X POST http://localhost:8080/orchestrator/api/v1/workflow/start \
  -H "Content-Type: application/json" \
  -d '{
    "accountProfile": {
      "accountId": "acc-001",
      "accountName": "成长日记",
      "niche": "个人成长",
      "targetAudience": "20-30岁年轻人",
      "tone": "轻松、不要太说教",
      "platforms": ["公众号", "小红书", "头条"]
    },
    "inputs": {
      "direction": "如何克服拖延症"
    },
    "requireHumanReview": false
  }'
```

### 查询工作流状态
```bash
curl http://localhost:8080/orchestrator/api/v1/workflow/{workflowId}/status
```

### 人工审核通过（当 requireHumanReview=true）
```bash
curl -X POST http://localhost:8080/orchestrator/api/v1/workflow/{workflowId}/approve \
  -H "Content-Type: application/json" \
  -d '{"feedback": "选题很好，建议从情绪管理角度切入"}'
```

## 架构概览

| 模块 | 服务名 | 端口 | Agent 角色 |
|------|--------|------|------------|
| content-ops-orchestrator | orchestrator | 8080 | 流水线编排器 |
| content-ops-agent-topic | topic-agent | 8081 | 选题策划 Agent |
| content-ops-agent-content | content-agent | 8082 | 内容创作 Agent |
| content-ops-agent-image | image-agent | 8083 | 配图设计 Agent |
| content-ops-agent-publish | publish-agent | 8084 | 排版发布 Agent |
| content-ops-agent-analysis | analysis-agent | 8085 | 数据分析 Agent |
| content-ops-agent-optimize | optimize-agent | 8086 | 优化迭代 Agent |

详细架构设计见 [ARCHITECTURE.md](docs/ARCHITECTURE.md)

## 技术栈
- **AI框架**: LangChain4j 1.0.1 (声明式 AI Service + Tool Calling)
- **AI框架**: Spring AI 1.0.0 (ChatClient 适配)
- **微服务**: Spring Boot 3.4.5 + Spring Cloud 2024.0.1
- **消息队列**: Apache Kafka (阶段转换事件)
- **状态存储**: Redis (Workflow 状态 + Artifact 累积)
- **服务发现**: Eureka + OpenFeign
- **语言**: Java 21
