package com.contentops.server.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 内容运营流水线编排服务（单体架构入口）。
 *
 * <p>合并原 orchestrator 的 PipelineOrchestrator 与各 Agent 业务逻辑，
 * 直接在进程内调用各 Agent Service，无需远程 Feign 调用。
 *
 * <p>TODO: 迁移原 WorkflowService 和 PipelineOrchestrator 逻辑到此类，
 * 或保留原 orchestrator 包下的实现，通过内部依赖注入直接调用。
 */
@Slf4j
@Service
public class PipelineService {

    // TODO: 注入各 Agent Service（Topic、Content、Image、Publish、Analysis、Optimize）

    /**
     * 启动完整的内容运营流水线。
     */
    public void startPipeline(String workflowId) {
        log.info("[Monolithic] Starting pipeline for workflow: {}", workflowId);
        // TODO: 实现流水线编排逻辑
    }

    /**
     * 推进工作流到下一阶段。
     */
    public void proceedToNextStage(String workflowId) {
        log.info("[Monolithic] Proceeding workflow: {} to next stage", workflowId);
        // TODO: 实现阶段推进逻辑
    }
}
