package com.contentops.common.agent;

import com.contentops.common.metrics.TokenMetricsService;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.service.tool.DefaultToolExecutor;
import dev.langchain4j.service.tool.ToolExecutor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * ReAct（Reasoning + Acting）模式 Agent 执行器。
 *
 * <p>实现经典的 ReAct 推理-行动循环：模型先进行推理（Thought），决定是否调用工具
 * （Action），获取工具返回的观察结果（Observation），再将观察结果反馈给模型继续推理，
 * 如此往复直到模型给出最终答案或达到最大迭代次数。
 *
 * <h3>执行循环</h3>
 * <pre>
 *   Thought  →  Action  →  Observation  →  Thought  →  ...  →  Final Answer
 * </pre>
 *
 * <p>本执行器集成 LangChain4j 的 {@link ChatModel} 与原生工具执行能力：
 * <ul>
 *   <li>通过 {@link ToolSpecifications#toolSpecificationFrom(Method)} 从 {@code @Tool}
 *       注解方法提取工具规格</li>
 *   <li>通过 {@link DefaultToolExecutor} 反射执行工具方法</li>
 *   <li>通过 {@link ChatRequest} 将工具规格随消息一同提交给模型，由模型决定调用时机</li>
 * </ul>
 *
 * <h3>特性</h3>
 * <ul>
 *   <li>最大迭代次数限制（{@link MultiAgentProperties.ReactConfig#getMaxIterations()}）</li>
 *   <li>中间结果缓存（按「工具名:迭代轮次」缓存 Observation）</li>
 *   <li>token 用量累计与可选的 {@link TokenMetricsService} 指标记录</li>
 *   <li>异常降级：模型不可用或工具执行异常时返回失败结果而非抛出异常</li>
 * </ul>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * reactExecutor.registerTools(topicResearchTools, contentTools);
 * AgentResult result = reactExecutor.execute(task, AgentRole.RESEARCHER);
 * }</pre>
 *
 * @see AgentTask
 * @see AgentRole
 * @see AgentResult
 * @see MultiAgentProperties
 */
@Slf4j
@Component
public class ReActAgentExecutor {

    private final ObjectProvider<ChatModel> chatModelProvider;
    private final ObjectProvider<TokenMetricsService> tokenMetricsServiceProvider;
    private final MultiAgentProperties properties;

    /** 已注册工具：工具名 → 工具条目（规格 + 执行器）。 */
    private final Map<String, ToolEntry> registeredTools = new ConcurrentHashMap<>();

    /** 中间结果缓存：缓存键 → 观察结果。 */
    private final Map<String, String> intermediateCache = new ConcurrentHashMap<>();

    /** ReAct 引导提示词，指导模型遵循 Thought→Action→Observation 范式。 */
    private static final String REACT_GUIDE = """
            请使用 ReAct（推理-行动）模式完成以下任务：
            1. 【Thought】先分析当前状态与下一步该做什么
            2. 【Action】如需外部信息，调用合适的工具；如已有足够信息，直接给出最终答案
            3. 【Observation】工具返回结果后，结合观察继续推理
            重复以上步骤直到完成任务，最后输出最终答案。
            注意：最终答案应直接以可交付的文本形式呈现，不要再附带工具调用。
            """;

    /**
     * 构造执行器。
     *
     * @param chatModelProvider        ChatModel 提供者（允许缺失，缺失时降级）
     * @param tokenMetricsServiceProvider token 指标服务提供者（可选）
     * @param properties               多 Agent 配置
     */
    public ReActAgentExecutor(ObjectProvider<ChatModel> chatModelProvider,
                               ObjectProvider<TokenMetricsService> tokenMetricsServiceProvider,
                               MultiAgentProperties properties) {
        this.chatModelProvider = chatModelProvider;
        this.tokenMetricsServiceProvider = tokenMetricsServiceProvider;
        this.properties = properties;
        log.info("[ReAct] ReActAgentExecutor 已初始化, maxIterations={}",
                properties.getReact().getMaxIterations());
    }

    // ──────────────────────── 工具注册 ────────────────────────

    /**
     * 注册一个或多个含 {@code @Tool} 注解方法的对象。
     *
     * <p>扫描对象中所有 {@link Tool} 注解方法，为每个方法构建 {@link ToolSpecification}
     * 与 {@link DefaultToolExecutor}，按工具名存入注册表。重复注册同名工具会覆盖旧值。
     *
     * @param toolObjects 含 @Tool 方法的工具对象
     */
    public void registerTools(Object... toolObjects) {
        if (toolObjects == null) {
            return;
        }
        for (Object tool : toolObjects) {
            if (tool == null) {
                continue;
            }
            for (Method method : tool.getClass().getMethods()) {
                if (method.isAnnotationPresent(Tool.class)) {
                    try {
                        method.setAccessible(true);
                        ToolSpecification spec = ToolSpecifications.toolSpecificationFrom(method);
                        ToolExecutor executor = new DefaultToolExecutor(tool, method);
                        registeredTools.put(spec.name(), new ToolEntry(spec, executor));
                        log.debug("[ReAct] 已注册工具: {}", spec.name());
                    } catch (Exception e) {
                        log.warn("[ReAct] 注册工具失败: {}.{}", tool.getClass().getSimpleName(),
                                method.getName(), e);
                    }
                }
            }
        }
        log.info("[ReAct] 工具注册完成, 当前已注册 {} 个工具", registeredTools.size());
    }

    /**
     * 获取所有已注册的工具名称。
     *
     * @return 工具名称列表
     */
    public List<String> registeredToolNames() {
        return List.copyOf(registeredTools.keySet());
    }

    /**
     * 清空已注册工具与中间缓存。
     */
    public void clear() {
        registeredTools.clear();
        intermediateCache.clear();
    }

    // ──────────────────────── 核心执行 ────────────────────────

    /**
     * 同步执行 ReAct 循环。
     *
     * <p>根据 {@link AgentRole#tools()} 过滤可用工具，构建初始对话上下文后进入
     * Thought→Action→Observation 循环，直至模型给出最终答案或达到最大迭代次数。
     *
     * @param task 待执行任务
     * @param role 执行角色（提供系统提示词、温度与可用工具）
     * @return 执行结果
     */
    public AgentResult execute(AgentTask task, AgentRole role) {
        long start = System.currentTimeMillis();
        ChatModel chatModel = chatModelProvider.getIfAvailable();
        if (chatModel == null) {
            log.warn("[ReAct] ChatModel 不可用，任务降级失败: taskId={}", task.taskId());
            return AgentResult.failure(task.taskId(), "ChatModel 未配置或不可用");
        }

        int maxIterations = properties.getReact().getMaxIterations();
        int cacheLimit = properties.getReact().getIntermediateCacheSize();

        // 构建初始消息上下文
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(role.systemPrompt()));
        messages.add(UserMessage.from(buildUserPrompt(task)));

        // 解析该角色可用的工具规格与执行器
        Map<ToolSpecification, ToolExecutor> usableTools = resolveTools(role);
        List<ToolSpecification> specs = new ArrayList<>(usableTools.keySet());

        TokenUsage totalUsage = new TokenUsage(0, 0, 0);
        String workflowTag = task.taskId();

        log.info("[ReAct] 开始执行, taskId={}, role={}, tools={}, maxIter={}",
                task.taskId(), role.roleName(), specs.size(), maxIterations);

        try {
            for (int iteration = 0; iteration < maxIterations; iteration++) {
                // ── 调用模型（Thought + Action 决策） ──
                var requestBuilder = ChatRequest.builder()
                        .messages(messages)
                        .temperature(role.temperature());
                if (!specs.isEmpty()) {
                    requestBuilder.toolSpecifications(specs);
                }
                ChatRequest request = requestBuilder.build();

                var response = chatModel.chat(request);
                AiMessage aiMessage = response.aiMessage();
                messages.add(aiMessage);

                // 累计 token 用量
                if (response.tokenUsage() != null) {
                    totalUsage = totalUsage.add(response.tokenUsage());
                }

                List<ToolExecutionRequest> toolRequests = aiMessage.toolExecutionRequests();

                // 无工具调用 → 最终答案
                if (toolRequests == null || toolRequests.isEmpty()) {
                    String output = aiMessage.text();
                    long elapsed = System.currentTimeMillis() - start;
                    recordMetrics(workflowTag, totalUsage, true);
                    log.info("[ReAct] 任务完成, taskId={}, iterations={}, outputLen={}, elapsedMs={}",
                            task.taskId(), iteration + 1,
                            output != null ? output.length() : 0, elapsed);
                    return AgentResult.success(task.taskId(), output, elapsed, totalUsage);
                }

                // ── 执行工具调用（Action → Observation） ──
                log.debug("[ReAct] taskId={}, iter={}, 模型请求调用 {} 个工具",
                        task.taskId(), iteration, toolRequests.size());
                for (ToolExecutionRequest toolRequest : toolRequests) {
                    String observation = executeTool(usableTools, toolRequest, iteration, cacheLimit);
                    messages.add(ToolExecutionResultMessage.from(toolRequest, observation));
                }
            }

            // 达到最大迭代次数，降级返回最后的文本
            String partial = extractLastText(messages);
            long elapsed = System.currentTimeMillis() - start;
            recordMetrics(workflowTag, totalUsage, false);
            log.warn("[ReAct] 达到最大迭代次数, taskId={}, maxIter={}, 降级返回部分结果",
                    task.taskId(), maxIterations);
            return AgentResult.timeout(task.taskId(), partial, elapsed);

        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            recordMetrics(workflowTag, totalUsage, false);
            log.error("[ReAct] 执行异常, taskId={}", task.taskId(), e);
            return AgentResult.failure(task.taskId(),
                    "ReAct 执行异常: " + e.getMessage(), elapsed);
        }
    }

    /**
     * 异步执行 ReAct 循环，附带整体超时控制。
     *
     * @param task     待执行任务
     * @param role     执行角色
     * @param executor 线程池
     * @return 带超时的 CompletableFuture
     */
    public java.util.concurrent.CompletableFuture<AgentResult> executeAsync(
            AgentTask task, AgentRole role, java.util.concurrent.Executor executor) {
        long timeoutSeconds = properties.getReact().getLlmTimeoutSeconds();
        return java.util.concurrent.CompletableFuture
                .supplyAsync(() -> execute(task, role), executor)
                .orTimeout(timeoutSeconds, TimeUnit.SECONDS)
                .exceptionally(ex -> {
                    long elapsed = 0L;
                    log.warn("[ReAct] 任务超时或异常, taskId={}, cause={}",
                            task.taskId(), ex.getMessage());
                    return AgentResult.timeout(task.taskId(), null, elapsed);
                });
    }

    // ──────────────────────── 内部方法 ────────────────────────

    /**
     * 解析角色可用的工具：从全局注册表中按角色 tools() 过滤。
     */
    private Map<ToolSpecification, ToolExecutor> resolveTools(AgentRole role) {
        Map<ToolSpecification, ToolExecutor> usable = new LinkedHashMap<>();
        List<String> allowed = role.tools();
        // 角色未声明工具时，开放全部已注册工具
        boolean restrict = allowed != null && !allowed.isEmpty();
        for (ToolEntry entry : registeredTools.values()) {
            if (!restrict || allowed.contains(entry.spec().name())) {
                usable.put(entry.spec(), entry.executor());
            }
        }
        return usable;
    }

    /**
     * 执行单个工具调用并缓存观察结果。
     */
    private String executeTool(Map<ToolSpecification, ToolExecutor> usableTools,
                                ToolExecutionRequest toolRequest, int iteration, int cacheLimit) {
        String toolName = toolRequest.name();
        String cacheKey = toolName + ":" + iteration;

        // 命中缓存直接返回
        if (intermediateCache.size() < cacheLimit && intermediateCache.containsKey(cacheKey)) {
            log.debug("[ReAct] 命中中间结果缓存: {}", cacheKey);
            return intermediateCache.get(cacheKey);
        }

        // 通过工具名精确匹配执行器
        ToolEntry entry = registeredTools.get(toolName);
        ToolExecutor executor = entry != null ? entry.executor() : null;
        if (executor == null) {
            String msg = "未找到工具: " + toolName;
            log.warn("[ReAct] {}", msg);
            return msg;
        }

        try {
            log.debug("[ReAct] 执行工具: {}, args={}", toolName, toolRequest.arguments());
            String result = executor.execute(toolRequest, null);
            if (result == null) {
                result = "工具返回空结果";
            }
            // 写入缓存
            if (intermediateCache.size() < cacheLimit) {
                intermediateCache.put(cacheKey, result);
            }
            return result;
        } catch (Exception e) {
            String err = "工具执行失败[" + toolName + "]: " + e.getMessage();
            log.warn("[ReAct] {}", err);
            return err;
        }
    }

    /**
     * 构建用户消息：ReAct 引导 + 任务描述 + 输入参数。
     */
    private String buildUserPrompt(AgentTask task) {
        StringBuilder sb = new StringBuilder(REACT_GUIDE);
        sb.append("\n---\n【任务描述】").append(task.description());
        if (!task.inputs().isEmpty()) {
            sb.append("\n【输入参数】").append(task.inputs());
        }
        if (task.hasDependencies()) {
            sb.append("\n【依赖任务】").append(task.dependencies());
        }
        return sb.toString();
    }

    /**
     * 从消息历史中提取最后一条 AI 文本输出。
     */
    private String extractLastText(List<ChatMessage> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage msg = messages.get(i);
            if (msg instanceof AiMessage ai && ai.text() != null && !ai.text().isBlank()) {
                return ai.text();
            }
        }
        return null;
    }

    /**
     * 记录 token 指标（指标服务不可用时静默跳过）。
     */
    private void recordMetrics(String tag, TokenUsage usage, boolean success) {
        try {
            TokenMetricsService metrics = tokenMetricsServiceProvider.getIfAvailable();
            if (metrics != null && usage != null) {
                int input = usage.inputTokenCount() != null ? usage.inputTokenCount() : 0;
                int output = usage.outputTokenCount() != null ? usage.outputTokenCount() : 0;
                metrics.recordTokenUsage(tag, "react", input, output);
                metrics.recordAgentCall("react", success);
            }
        } catch (Exception e) {
            log.debug("[ReAct] 记录指标失败: {}", e.getMessage());
        }
    }

    // ──────────────────────── 工具条目 ────────────────────────

    /**
     * 工具条目：封装工具规格与执行器。
     *
     * @param spec     工具规格
     * @param executor 工具执行器
     */
    private record ToolEntry(ToolSpecification spec, ToolExecutor executor) {
    }
}
