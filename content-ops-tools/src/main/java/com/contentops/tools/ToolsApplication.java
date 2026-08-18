package com.contentops.tools;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Phase3 Tools Service 启动类（端口 8082）。
 *
 * <p>第一阶段：扫描 {@code com.contentops.*} 全量包，把 legacy-server 里的 RAG/知识库/趋势/热点
 * /生图/MCP 工具等 Bean 全部加载；对外暴露：
 * <ul>
 *   <li>POST /internal/api/tools/rag/search  向量检索</li>
 *   <li>POST /internal/api/tools/rag/ingest   知识库写入</li>
 *   <li>GET  /internal/api/tools/trends       趋势热点查询</li>
 * </ul>
 * Worker / Orchestrator 完成 HTTP 迁移后即可删本地工具调用。
 */
@SpringBootApplication(scanBasePackages = {"com.contentops"})
public class ToolsApplication {

    public static void main(String[] args) {
        SpringApplication.run(ToolsApplication.class, args);
    }
}
