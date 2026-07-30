package com.contentops.orchestrator.workflow;

import com.contentops.common.constant.AgentConstants;
import com.contentops.common.dto.AgentResponse;
import com.contentops.common.dto.TaskContext;
import com.contentops.common.enums.AgentStage;
import com.contentops.common.enums.SubStage;
import com.contentops.common.enums.TaskStatus;
import com.contentops.common.event.AgentTaskRequest;
import com.contentops.common.event.StageTransitionEvent;
import com.contentops.common.util.WorkflowStateManager;
import com.contentops.common.quality.QualityAssessmentService;
import com.contentops.common.quality.QualityScore;
import com.contentops.common.methodology.HumanActionChecklistGenerator;
import com.contentops.orchestrator.gateway.AgentGateway;
import com.contentops.orchestrator.kafka.AsyncTaskProducer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * The Pipeline Orchestrator — the heart of the multi-agent system.
 *
 * Implements a Sequential Pipeline pattern:
 *   Topic → Content → Image → Publish → Analysis → Optimize → (loop back to Topic)
 *
 * <p><b>P1 渐进式生成：</b>CONTENT_CREATION 和 IMAGE_DESIGN 两个阶段各自包含子阶段：
 * <ul>
 *   <li>CONTENT_CREATION: outline → (确认) → draft</li>
 *   <li>IMAGE_DESIGN: styles → (确认) → generate</li>
 * </ul>
 * 子阶段一完成后暂停等待人工确认，确认后再执行子阶段二。
 *
 * Each stage:
 *   1. Loads accumulated artifacts from Redis
 *   2. Calls the appropriate agent microservice via Feign
 *   3. Merges new artifacts back into Redis
 *   4. If human review required, pauses; otherwise advances to next stage
 *   5. Publishes a StageTransitionEvent to Kafka for observability
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PipelineOrchestrator {

    private final WorkflowStateManager stateManager;
    private final AgentGateway agentGateway;

    // P2 服务集成：质量评估 + 人工行动清单
    private final QualityAssessmentService qualityAssessmentService;
    private final HumanActionChecklistGenerator checklistGenerator;

    // 单体模式下 Kafka 可能不存在，设为可选（用 Object 避免依赖 spring-kafka）
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private Object kafkaTemplate;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private AsyncTaskProducer asyncTaskProducer;

    /**
     * P1: 判断子阶段是否应通过 Kafka 异步执行。
     *
     * <p>长耗时的子阶段（内容初稿、批量生图）通过 Kafka 异步执行，
     * 避免 Feign HTTP 超时阻塞整个流水线。
     */
    private static boolean isAsyncSubStage(SubStage subStage) {
        return subStage == SubStage.CONTENT_DRAFT || subStage == SubStage.IMAGE_GENERATE;
    }

    /**
     * Execute the current stage for a workflow.
     *
     * <p>If the stage has sub-stages, starts with the first sub-stage.
     * Otherwise, executes the stage in a single call.
     */
    public void executeStage(TaskContext context) {
        AgentStage stage = AgentStage.fromCode(context.getCurrentStage());
        log.info("[Workflow:{}] Executing stage: {} ({})",
                context.getWorkflowId(), stage.getCode(), stage.getNameCn());

        context.setStatus(TaskStatus.IN_PROGRESS.name());
        context.setUpdatedAt(LocalDateTime.now());
        stateManager.saveWorkflowState(context.getWorkflowId(), context);

        // Publish start event
        publishEvent(StageTransitionEvent.started(context.getWorkflowId(), stage.getCode()));

        try {
            // Check if this stage has sub-stages (progressive generation)
            if (SubStage.hasSubStages(stage)) {
                SubStage firstSub = SubStage.firstOf(stage);
                log.info("[Workflow:{}] Stage {} has sub-stages. Starting with: {} ({})",
                        context.getWorkflowId(), stage.getCode(), firstSub.getCode(), firstSub.getNameCn());
                context.setCurrentSubStage(firstSub.getCode());
                executeSubStage(context, firstSub);
            } else {
                // No sub-stages: execute in one shot
                AgentTaskRequest request = buildRequest(context);
                AgentResponse<Map<String, Object>> response = routeToAgent(stage, request);

                if (response.isSuccess()) {
                    handleStageSuccess(context, stage, response);
                } else {
                    handleStageFailure(context, stage, response.getError());
                }
            }
        } catch (Exception e) {
            log.error("[Workflow:{}] Stage {} failed with exception",
                    context.getWorkflowId(), stage.getCode(), e);
            handleStageFailure(context, stage, e.getMessage());
        }
    }

    /**
     * Execute a specific sub-stage (called by executeStage or confirmSubStage).
     *
     * <p><b>P1 Kafka 异步模式：</b>长耗时的子阶段（CONTENT_DRAFT、IMAGE_GENERATE）
     * 通过 Kafka 异步执行，避免 Feign HTTP 超时：
     * <ol>
     *   <li>发送 AsyncTaskRequest 到 Kafka</li>
     *   <li>设置工作流状态为 AWAITING_ASYNC</li>
     *   <li>立即返回（不阻塞等待）</li>
     *   <li>目标 Agent 消费消息后执行 LLM 调用，发送结果到结果 topic</li>
     *   <li>{@link com.contentops.orchestrator.kafka.AsyncTaskResultConsumer} 消费结果并推进工作流</li>
     * </ol>
     */
    private void executeSubStage(TaskContext context, SubStage subStage) {
        log.info("[Workflow:{}] Executing sub-stage: {} ({})",
                context.getWorkflowId(), subStage.fullCode(), subStage.getNameCn());

        // P1: 长耗时子阶段走 Kafka 异步模式
        if (isAsyncSubStage(subStage)) {
            executeSubStageAsync(context, subStage);
            return;
        }

        try {
            AgentTaskRequest request = buildRequest(context);
            AgentResponse<Map<String, Object>> response = routeToSubStage(subStage, request);

            if (response.isSuccess()) {
                handleSubStageSuccess(context, subStage, response);
            } else {
                AgentStage stage = AgentStage.fromCode(subStage.getParentStageCode());
                handleStageFailure(context, stage, response.getError());
            }
        } catch (Exception e) {
            log.error("[Workflow:{}] Sub-stage {} failed with exception",
                    context.getWorkflowId(), subStage.fullCode(), e);
            AgentStage stage = AgentStage.fromCode(subStage.getParentStageCode());
            handleStageFailure(context, stage, e.getMessage());
        }
    }

    /**
     * P1: 通过 Kafka 异步执行长耗时子阶段。
     *
     * <p>发送异步任务到 Kafka，设置工作流状态为 AWAITING_ASYNC，
     * 然后立即返回。结果由 {@link AsyncTaskResultConsumer} 处理。
     */
    private void executeSubStageAsync(TaskContext context, SubStage subStage) {
        // 单体模式下无 Kafka，回退到同步执行
        if (asyncTaskProducer == null) {
            log.info("[Workflow:{}] Kafka not available. Falling back to synchronous execution for sub-stage {}",
                    context.getWorkflowId(), subStage.fullCode());
            try {
                AgentTaskRequest request = buildRequest(context);
                AgentResponse<Map<String, Object>> response = routeToSubStage(subStage, request);
                if (response.isSuccess()) {
                    handleSubStageSuccess(context, subStage, response);
                } else {
                    AgentStage stage = AgentStage.fromCode(subStage.getParentStageCode());
                    handleStageFailure(context, stage, response.getError());
                }
            } catch (Exception e) {
                log.error("[Workflow:{}] Sync sub-stage {} failed with exception",
                        context.getWorkflowId(), subStage.fullCode(), e);
                AgentStage stage = AgentStage.fromCode(subStage.getParentStageCode());
                handleStageFailure(context, stage, e.getMessage());
            }
            return;
        }

        try {
            AgentTaskRequest request = buildRequest(context);

            // 发送异步任务到 Kafka
            String taskId = asyncTaskProducer.sendAsyncTask(
                    context, subStage.getParentStageCode(), subStage.getCode(), request);

            // 设置工作流状态为 AWAITING_ASYNC
            context.setStatus(TaskStatus.AWAITING_ASYNC.name());
            context.setUpdatedAt(LocalDateTime.now());
            stateManager.saveWorkflowState(context.getWorkflowId(), context);

            log.info("[Workflow:{}] Sub-stage {} sent to Kafka async. taskId={}, status=AWAITING_ASYNC",
                    context.getWorkflowId(), subStage.fullCode(), taskId);

            // 发布阶段开始事件
            publishEvent(StageTransitionEvent.started(context.getWorkflowId(),
                    subStage.getParentStageCode()));
        } catch (Exception e) {
            log.error("[Workflow:{}] Async sub-stage {} failed with exception",
                    context.getWorkflowId(), subStage.fullCode(), e);
            AgentStage stage = AgentStage.fromCode(subStage.getParentStageCode());
            handleStageFailure(context, stage, e.getMessage());
        }
    }

    /**
     * Confirm a sub-stage and proceed to the next sub-stage or the next AgentStage.
     *
     * <p>Called by the WorkflowController when a user confirms the sub-stage output
     * (e.g., confirms an outline before draft generation).
     *
     * @param context   the workflow context
     * @param feedback  optional feedback/modifications from the user (e.g., edited outline)
     */
    public void confirmAndProceedSubStage(TaskContext context, Map<String, Object> feedback) {
        String currentSub = context.getCurrentSubStage();
        if (currentSub == null || currentSub.isBlank()) {
            log.warn("[Workflow:{}] No current sub-stage to confirm", context.getWorkflowId());
            return;
        }

        SubStage subStage = SubStage.fromCode(currentSub);
        log.info("[Workflow:{}] Confirming sub-stage: {} ({})",
                context.getWorkflowId(), subStage.fullCode(), subStage.getNameCn());

        // Merge feedback into accumulated artifacts (e.g., confirmedOutline, confirmedStyle)
        if (feedback != null && !feedback.isEmpty()) {
            if (context.getAccumulatedArtifacts() == null) {
                context.setAccumulatedArtifacts(new java.util.HashMap<>());
            }
            // Store feedback as inputs for the next sub-stage
            if (context.getInputs() == null) {
                context.setInputs(new java.util.HashMap<>());
            }
            context.getInputs().putAll(feedback);
        }

        // Check if there's a next sub-stage
        SubStage nextSub = subStage.next();
        if (nextSub != null) {
            // Proceed to next sub-stage
            log.info("[Workflow:{}] Sub-stage {} → {} (proceeding)",
                    context.getWorkflowId(), subStage.getCode(), nextSub.getCode());
            context.setCurrentSubStage(nextSub.getCode());
            context.setStatus(TaskStatus.IN_PROGRESS.name());
            context.setUpdatedAt(LocalDateTime.now());
            stateManager.saveWorkflowState(context.getWorkflowId(), context);
            executeSubStage(context, nextSub);
        } else {
            // Last sub-stage completed → advance to next AgentStage
            AgentStage stage = AgentStage.fromCode(subStage.getParentStageCode());
            context.setCurrentSubStage(null);
            AgentStage nextStage = stage.next();
            context.setCurrentStage(nextStage.getCode());
            context.setStatus(TaskStatus.PENDING.name());
            context.setUpdatedAt(LocalDateTime.now());
            stateManager.saveWorkflowState(context.getWorkflowId(), context);

            log.info("[Workflow:{}] Stage {} → {} (all sub-stages done)",
                    context.getWorkflowId(), stage.getCode(), nextStage.getCode());
            publishEvent(StageTransitionEvent.completed(
                    context.getWorkflowId(),
                    stage.getCode(),
                    nextStage.getCode(),
                    null
            ));

            // FIX: 修复自动推进断裂 —— 立即触发下一阶段执行
            executeStage(context);
        }
    }

    /**
     * Route the task to the appropriate agent (via AgentGateway — 支持 mock / microservice 模式切换).
     */
    private AgentResponse<Map<String, Object>> routeToAgent(AgentStage stage, AgentTaskRequest request) {
        return switch (stage) {
            case TOPIC_PLANNING -> agentGateway.callTopic(request);
            case CONTENT_CREATION -> agentGateway.callContentExecute(request);
            case IMAGE_DESIGN -> agentGateway.callImageExecute(request);
            case PUBLISHING -> agentGateway.callPublish(request);
            case DATA_ANALYSIS -> agentGateway.callAnalysis(request);
            case OPTIMIZATION -> agentGateway.callOptimize(request);
        };
    }

    /**
     * Route the task to the appropriate sub-stage (via AgentGateway).
     */
    private AgentResponse<Map<String, Object>> routeToSubStage(SubStage subStage, AgentTaskRequest request) {
        return switch (subStage) {
            case CONTENT_OUTLINE -> agentGateway.callContentOutline(request);
            case CONTENT_DRAFT -> agentGateway.callContentDraft(request);
            case IMAGE_STYLES -> agentGateway.callImageStyles(request);
            case IMAGE_GENERATE -> agentGateway.callImageGenerate(request);
        };
    }

    /**
     * Handle successful sub-stage execution.
     *
     * <p>If this is NOT the last sub-stage, pause for human confirmation.
     * If it IS the last sub-stage, advance to the next AgentStage.
     */
    private void handleSubStageSuccess(TaskContext context, SubStage subStage,
                                        AgentResponse<Map<String, Object>> response) {
        // Merge sub-stage artifacts into accumulated artifacts
        if (response.getData() != null) {
            if (context.getAccumulatedArtifacts() == null) {
                context.setAccumulatedArtifacts(new java.util.HashMap<>());
            }
            context.getAccumulatedArtifacts().put(subStage.fullCode(), response.getData());

            // Also store key outputs at a flat level for easy access by next sub-stage
            // (e.g., outline result → confirmedOutline, style result → confirmedStyle)
            storeSubStageOutput(context, subStage, response.getData());
        }

        SubStage nextSub = subStage.next();
        if (nextSub != null) {
            // Pause for human confirmation before next sub-stage
            context.setStatus(TaskStatus.AWAITING_HUMAN.name());
            context.setUpdatedAt(LocalDateTime.now());
            stateManager.saveWorkflowState(context.getWorkflowId(), context);

            log.info("[Workflow:{}] Sub-stage {} completed. Awaiting confirmation for {}.",
                    context.getWorkflowId(), subStage.getCode(), nextSub.getCode());

            publishEvent(StageTransitionEvent.completed(
                    context.getWorkflowId(),
                    subStage.getParentStageCode(),
                    subStage.getParentStageCode(),  // stays on same stage
                    response.getData()
            ));
        } else {
            // Last sub-stage → advance to next AgentStage
            AgentStage stage = AgentStage.fromCode(subStage.getParentStageCode());
            context.setCurrentSubStage(null);

            AgentStage nextStage = stage.next();
            context.setCurrentStage(nextStage.getCode());
            context.setStatus(TaskStatus.PENDING.name());
            context.setUpdatedAt(LocalDateTime.now());
            stateManager.saveWorkflowState(context.getWorkflowId(), context);

            log.info("[Workflow:{}] Sub-stage {} completed. Stage {} → {} (auto-advance)",
                    context.getWorkflowId(), subStage.getCode(), stage.getCode(), nextStage.getCode());

            publishEvent(StageTransitionEvent.completed(
                    context.getWorkflowId(),
                    stage.getCode(),
                    nextStage.getCode(),
                    response.getData()
            ));

            // FIX: 修复自动推进断裂 —— 立即触发下一阶段执行
            executeStage(context);
        }
    }

    /**
     * Store sub-stage output at a flat level for easy access by the next sub-stage.
     *
     * <p>For example, the outline sub-stage's result is stored as "confirmedOutline"
     * so the draft sub-stage can pick it up via inputs.
     */
    @SuppressWarnings("unchecked")
    private void storeSubStageOutput(TaskContext context, SubStage subStage, Map<String, Object> data) {
        if (context.getInputs() == null) {
            context.setInputs(new java.util.HashMap<>());
        }
        switch (subStage) {
            case CONTENT_OUTLINE -> {
                // Store the outline result as confirmedOutline for the draft sub-stage
                Object outlineObj = data.get("outline");
                if (outlineObj != null) {
                    context.getInputs().put("confirmedOutline", outlineObj.toString());
                }
                // Also carry forward the topic
                Object topic = data.get("topic");
                if (topic != null) {
                    context.getInputs().putIfAbsent("topic", topic);
                }
            }
            case IMAGE_STYLES -> {
                // Store the style directions result as confirmedStyle for the generate sub-stage
                Object stylesObj = data.get("styleDirections");
                if (stylesObj != null) {
                    context.getInputs().put("confirmedStyle", stylesObj.toString());
                }
            }
            default -> {
                // For CONTENT_DRAFT and IMAGE_GENERATE, outputs are final stage outputs
                // and are already stored in accumulatedArtifacts
            }
        }
    }

    /**
     * Handle successful stage execution (for stages without sub-stages).
     *
     * <p><b>A计划修复：</b>
     * <ul>
     *   <li>修复自动推进断裂：auto-advance 后立即调用 {@code executeStage(context)} 继续执行</li>
     *   <li>新增循环控制：OPTIMIZATION 完成时检查是否进入新一轮循环</li>
     * </ul>
     */
    private void handleStageSuccess(TaskContext context, AgentStage stage,
                                     AgentResponse<Map<String, Object>> response) {
        // Merge artifacts
        if (response.getData() != null) {
            context.setOutputs(response.getData());
            if (context.getAccumulatedArtifacts() == null) {
                context.setAccumulatedArtifacts(new java.util.HashMap<>());
            }
            context.getAccumulatedArtifacts().put(stage.getCode(), response.getData());
        }

        // P2 集成：质量评估 + 人工行动清单
        assessAndEnrich(context, stage, response.getData());

        // ── A计划：循环边界检查（OPTIMIZATION → TOPIC_PLANNING） ──
        AgentStage nextStage = stage.next();
        if (isCycleBoundary(stage, nextStage)) {
            handleCycleBoundary(context, stage, response.getData());
            return;
        }

        // ── 正常阶段推进 ──
        if (context.isRequireHumanReview()) {
            context.setStatus(TaskStatus.AWAITING_HUMAN.name());
            context.setUpdatedAt(LocalDateTime.now());
            stateManager.saveWorkflowState(context.getWorkflowId(), context);
            log.info("[Workflow:{}] Stage {} completed. Awaiting human approval.",
                    context.getWorkflowId(), stage.getCode());
            publishEvent(StageTransitionEvent.completed(
                    context.getWorkflowId(), stage.getCode(),
                    stage.getCode(), response.getData()));
        } else {
            // Auto-advance to next stage
            context.setCurrentStage(nextStage.getCode());
            context.setStatus(TaskStatus.PENDING.name());
            context.setUpdatedAt(LocalDateTime.now());
            stateManager.saveWorkflowState(context.getWorkflowId(), context);
            log.info("[Workflow:{}] Stage {} → {} (auto-advance)",
                    context.getWorkflowId(), stage.getCode(), nextStage.getCode());
            publishEvent(StageTransitionEvent.completed(
                    context.getWorkflowId(), stage.getCode(),
                    nextStage.getCode(), response.getData()));

            // FIX: 修复自动推进断裂 —— 立即触发下一阶段执行
            executeStage(context);
        }
    }

    // ==================== P2 集成：质量评估与人工行动清单 ====================

    /**
     * P2 集成：对阶段输出进行质量评估，并生成人工行动清单。
     *
     * <p>将质量评分和行动清单存储到 accumulatedArtifacts 中，供前端展示和后续优化参考。
     * 质量评分不阻断流程（低分仅记录警告），由 AutoRetryService 在需要时触发重试。
     *
     * @param context 工作流上下文
     * @param stage   当前阶段
     * @param data    阶段输出数据
     */
    private void assessAndEnrich(TaskContext context, AgentStage stage, Map<String, Object> data) {
        if (data == null) return;

        try {
            // 提取文本内容用于质量评估
            String content = extractTextContent(data);

            // 质量评估
            QualityScore qualityScore = qualityAssessmentService.assessQuality(stage, content);
            if (context.getAccumulatedArtifacts() == null) {
                context.setAccumulatedArtifacts(new java.util.HashMap<>());
            }
            Map<String, Object> qualityMeta = new java.util.HashMap<>();
            qualityMeta.put("score", qualityScore.getTotalScore());
            qualityMeta.put("logic", qualityScore.getLogic());
            qualityMeta.put("readability", qualityScore.getReadability());
            qualityMeta.put("originality", qualityScore.getOriginality());
            qualityMeta.put("suggestions", qualityScore.getSuggestions());
            context.getAccumulatedArtifacts().put(stage.getCode() + ":quality", qualityMeta);

            if (qualityScore.getTotalScore() < 60) {
                log.warn("[Workflow:{}] Stage {} quality score {} below threshold. Suggestions: {}",
                        context.getWorkflowId(), stage.getCode(),
                        qualityScore.getTotalScore(), qualityScore.getSuggestions());
            } else {
                log.info("[Workflow:{}] Stage {} quality score: {} (logic={}, readability={}, originality={})",
                        context.getWorkflowId(), stage.getCode(),
                        qualityScore.getTotalScore(),
                        qualityScore.getLogic(), qualityScore.getReadability(),
                        qualityScore.getOriginality());
            }

            // 人工行动清单（"帮助而非替代"方法论）
            List<String> checklist = checklistGenerator.generateChecklist(stage, data);
            if (checklist != null && !checklist.isEmpty()) {
                context.getAccumulatedArtifacts().put(stage.getCode() + ":checklist", checklist);
                log.info("[Workflow:{}] Stage {} generated {} human action items",
                        context.getWorkflowId(), stage.getCode(), checklist.size());
            }
        } catch (Exception e) {
            log.warn("[Workflow:{}] Quality assessment failed for stage {}, continuing pipeline: {}",
                    context.getWorkflowId(), stage.getCode(), e.getMessage());
        }
    }

    /**
     * 从阶段输出数据中提取文本内容用于质量评估。
     */
    private String extractTextContent(Map<String, Object> data) {
        StringBuilder sb = new StringBuilder();
        for (Object value : data.values()) {
            if (value instanceof String s && !s.isBlank()) {
                sb.append(s).append("\n");
            } else if (value instanceof Map<?, ?> m) {
                extractTextFromMap(m, sb);
            } else if (value instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof String s) {
                        sb.append(s).append("\n");
                    } else if (item instanceof Map<?, ?> m) {
                        extractTextFromMap(m, sb);
                    }
                }
            }
        }
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private void extractTextFromMap(Map<?, ?> map, StringBuilder sb) {
        for (Object value : map.values()) {
            if (value instanceof String s && !s.isBlank()) {
                sb.append(s).append("\n");
            } else if (value instanceof Map<?, ?> m) {
                extractTextFromMap(m, sb);
            } else if (value instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof String s) {
                        sb.append(s).append("\n");
                    } else if (item instanceof Map<?, ?> m2) {
                        extractTextFromMap(m2, sb);
                    }
                }
            }
        }
    }

    // ==================== A计划：循环控制方法 ====================

    /**
     * 判断是否为循环边界（OPTIMIZATION → TOPIC_PLANNING）。
     */
    private boolean isCycleBoundary(AgentStage currentStage, AgentStage nextStage) {
        return currentStage == AgentStage.OPTIMIZATION
                && nextStage == AgentStage.TOPIC_PLANNING;
    }

    /**
     * 检查并处理循环边界（公开方法，供 WorkflowService.approveAndProceed 调用）。
     *
     * <p>当人工审批 OPTIMIZATION 阶段后，WorkflowService 调用此方法判断是否为循环边界：
     * <ul>
     *   <li>如果是循环边界且有剩余轮次：快照 + 开始新一轮 + 自动执行</li>
     *   <li>如果是循环边界但无剩余轮次：标记 COMPLETED</li>
     *   <li>如果不是循环边界：返回 false，调用方正常推进</li>
     * </ul>
     *
     * @return true 如果已处理循环边界（调用方不应继续正常推进），false 如果不是循环边界
     */
    @SuppressWarnings("unchecked")
    public boolean checkAndHandleCycleBoundary(TaskContext context,
                                                 AgentStage currentStage,
                                                 AgentStage nextStage) {
        if (!isCycleBoundary(currentStage, nextStage)) {
            return false;
        }

        // 从 accumulatedArtifacts 提取 OPTIMIZATION 阶段的产物作为响应数据
        Map<String, Object> optimizationData = null;
        if (context.getAccumulatedArtifacts() != null) {
            Object stored = context.getAccumulatedArtifacts().get(AgentStage.OPTIMIZATION.getCode());
            if (stored instanceof Map) {
                optimizationData = (Map<String, Object>) stored;
            }
        }

        handleCycleBoundary(context, currentStage, optimizationData);
        return true;
    }

    /**
     * 处理循环边界：OPTIMIZATION 完成后，决定是否开始新一轮循环。
     *
     * <p>逻辑：
     * <ol>
     *   <li>提取 OptimizeAgent 的优化反馈</li>
     *   <li>检查 {@code hasRemainingCycles()}：cycleCount < maxCycles</li>
     *   <li>若有剩余轮次：快照当前轮次产物 → 递增 cycleCount → 注入反馈 → 开始新一轮</li>
     *   <li>若无剩余轮次：标记工作流 COMPLETED</li>
     * </ol>
     */
    private void handleCycleBoundary(TaskContext context, AgentStage stage,
                                      Map<String, Object> responseData) {
        // 提取优化反馈（A3: 反馈注入）
        extractOptimizationFeedback(context, responseData);

        if (context.hasRemainingCycles()) {
            // 快照当前轮次产物 + 递增 cycleCount + 重置阶段 + 注入反馈
            context.startNewCycle();
            stateManager.saveWorkflowState(context.getWorkflowId(), context);

            log.info("[Workflow:{}] Cycle boundary: cycle {} → {}. Starting new cycle.",
                    context.getWorkflowId(), context.getCycleCount() - 1, context.getCycleCount());

            publishEvent(StageTransitionEvent.completed(
                    context.getWorkflowId(), stage.getCode(),
                    context.getCurrentStage(), responseData));

            // 开始新一轮的 TOPIC_PLANNING
            executeStage(context);
        } else {
            // 达到最大循环次数，工作流完成
            context.setStatus(TaskStatus.COMPLETED.name());
            context.setUpdatedAt(LocalDateTime.now());
            stateManager.saveWorkflowState(context.getWorkflowId(), context);

            log.info("[Workflow:{}] All {} cycles completed. Workflow COMPLETED.",
                    context.getWorkflowId(), context.getMaxCycles());

            publishEvent(StageTransitionEvent.completed(
                    context.getWorkflowId(), stage.getCode(),
                    "COMPLETED", responseData));
        }
    }

    /**
     * 从 OptimizeAgent 的响应中提取优化反馈。
     *
     * <p>尝试多个可能的 key（optimizationSuggestions / recommendations / feedback），
     * 将提取到的反馈存入 {@link TaskContext#lastOptimizationFeedback}，
     * 供下一轮循环的 TOPIC_PLANNING Agent 使用。
     */
    @SuppressWarnings("unchecked")
    private void extractOptimizationFeedback(TaskContext context, Map<String, Object> responseData) {
        if (responseData == null) return;
        Object feedback = responseData.get("optimizationSuggestions");
        if (feedback == null) feedback = responseData.get("recommendations");
        if (feedback == null) feedback = responseData.get("feedback");
        if (feedback == null) feedback = responseData.get("suggestions");
        if (feedback != null) {
            context.setLastOptimizationFeedback(feedback.toString());
            log.info("[Workflow:{}] Optimization feedback extracted ({} chars)",
                    context.getWorkflowId(), feedback.toString().length());
        }
    }

    /**
     * Handle failed stage execution.
     */
    private void handleStageFailure(TaskContext context, AgentStage stage, String error) {
        context.setStatus(TaskStatus.FAILED.name());
        context.setErrorMessage(error);
        context.setUpdatedAt(LocalDateTime.now());
        stateManager.saveWorkflowState(context.getWorkflowId(), context);

        publishEvent(StageTransitionEvent.failed(
                context.getWorkflowId(), stage.getCode(), error
        ));

        log.error("[Workflow:{}] Stage {} FAILED: {}",
                context.getWorkflowId(), stage.getCode(), error);
    }

    /**
     * Build an AgentTaskRequest from the workflow context.
     */
    private AgentTaskRequest buildRequest(TaskContext context) {
        AgentTaskRequest request = AgentTaskRequest.of(
                context.getWorkflowId(),
                context.getCurrentStage(),
                context.getAccountProfile(),
                context.getInputs(),
                context.getAccumulatedArtifacts()
        );
        request.setRequireHumanReview(context.isRequireHumanReview());
        return request;
    }

    /**
     * Publish stage transition event to Kafka.
     */
    private void publishEvent(StageTransitionEvent event) {
        if (kafkaTemplate == null) {
            // 单体模式下无 Kafka，仅记录日志
            log.debug("[Monolithic] Skipping Kafka event publish: {}", event.getType());
            return;
        }
        try {
            // 使用反射调用，避免编译期依赖 spring-kafka
            kafkaTemplate.getClass()
                    .getMethod("send", String.class, Object.class, Object.class)
                    .invoke(kafkaTemplate, AgentConstants.TASK_EVENT_TOPIC, event.getWorkflowId(), event);
        } catch (Exception e) {
            log.warn("Failed to publish Kafka event: {}", e.getMessage());
        }
    }
}
