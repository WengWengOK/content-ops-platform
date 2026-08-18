package com.contentops.orchestrator.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Phase3 编排器启动类（端口 8080，见 application.yml）。
 *
 * <p>渐进拆分策略：
 * <ol>
 *   <li>组件扫描路径包含 legacy-server 的全部包（{@code com.contentops.*}），
 *       因此启动即等价于原单体 ContentOpsServerApplication，功能零差异。</li>
 *   <li>后续分阶段迁移：
 *       <ul>
 *         <li>把 6 个 Agent 从 orchestrator 摘出 → 迁到 content-ops-worker（端口 8081）；
 *             orchestrator 侧用 {@code RemoteAgentGateway} 通过 HTTP POST /internal/api/agent/execute
 *             调用，Worker 返回 {@link com.contentops.common.api.AgentExecuteResponse}。</li>
 *         <li>把 RAG/知识库/趋势/热点/生图 → 迁到 content-ops-tools（端口 8082）；
 *             worker/orchestrator 侧通过 REST /internal/api/tools/* 或 MCP 协议调用。</li>
 *       </ul>
 *   </li>
 *   <li>迁移全部完成后，删除对 content-ops-server 的 Maven 依赖即可完成"真正三服务分离"。</li>
 * </ol>
 */
@SpringBootApplication(scanBasePackages = {"com.contentops"})
public class OrchestratorApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrchestratorApplication.class, args);
    }
}
