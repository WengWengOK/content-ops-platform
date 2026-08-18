package com.contentops.worker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Phase3 Agent Worker 启动类（端口 8081，见 application.yml）。
 *
 * <p>第一阶段：扫描 {@code com.contentops.*} 全量包，把 legacy-server 的 6 个 Agent（topic/content/image/
 * publishing/analysis/optimization）+ 配置 全部加载；对外暴露
 * {@link com.contentops.worker.internal.WorkerInternalContractController} 的
 * {@code POST /internal/api/agent/execute} 供 Orchestrator 远程调用。
 *
 * <p>后续演化：Orchestrator 侧 AgentGateway 完成 HTTP 替换后，可删除 Worker 里的编排器 Bean 扫描，
 * 缩小为只扫 6 个 agent 包 + worker 包。
 */
@SpringBootApplication(scanBasePackages = {"com.contentops"})
public class WorkerApplication {

    public static void main(String[] args) {
        SpringApplication.run(WorkerApplication.class, args);
    }
}
