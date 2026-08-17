package com.contentops.common.agent;

import com.contentops.common.constant.AgentConstants;
import com.contentops.common.routing.ModelConfig;

import java.util.List;

/**
 * Agent 角色定义（多 Agent 协作框架）。
 *
 * <p>采用 Java 21 {@code record} 封装单个 Agent 角色的完整描述，包括角色名称、
 * 系统提示词、可使用的工具列表、模型配置和采样温度。角色定义是构建可复用、
 * 可组合 Agent 的基础单元，供 {@link ReActAgentExecutor}、
 * {@link PlanAndExecuteAgent} 与 {@link MultiAgentOrchestrator} 引用。
 *
 * <h3>预定义角色</h3>
 * <ul>
 *   <li>{@link #SUPERVISOR} —— 主管，负责任务分解、分配与结果聚合（层级协作模式）</li>
 *   <li>{@link #RESEARCHER} —— 研究员，负责信息检索与资料收集</li>
 *   <li>{@link #WRITER} —— 创作者，负责内容撰写与文案输出</li>
 *   <li>{@link #REVIEWER} —— 审稿人，负责内容评审与质量把控</li>
 *   <li>{@link #CRITIC} —— 批评者，负责挑刺与改进建议</li>
 * </ul>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * AgentRole role = AgentRole.WRITER;
 * AgentTask task = AgentTask.of("t-1", "撰写一篇关于 AI 趋势的文章", role.roleName(), inputs);
 * AgentResult result = reactExecutor.execute(task, role);
 * }</pre>
 *
 * @param roleName      角色名称（唯一标识，如 "writer"）
 * @param systemPrompt  系统提示词，定义角色的能力边界与行为规范
 * @param tools         该角色可调用的工具名称列表（对应已注册的 @Tool 方法名）
 * @param modelConfig   模型配置（模型名称、最大 token 等），复用 {@link ModelConfig}
 * @param temperature   采样温度（0.0 - 2.0），将覆盖 modelConfig 中的温度，用于细粒度控制角色创造力
 *
 * @see ModelConfig
 * @see AgentTask
 */
public record AgentRole(
        String roleName,
        String systemPrompt,
        List<String> tools,
        ModelConfig modelConfig,
        double temperature
) {

    // ──────────────────────── 预定义角色常量 ────────────────────────

    /** 主管角色：负责任务分解、子任务分配与最终结果聚合，使用低温度保证决策的确定性。 */
    public static final AgentRole SUPERVISOR = new AgentRole(
            "supervisor",
            """
            你是「主管 Agent」（Supervisor），负责将复杂任务分解为可执行的子任务，并合理分配给合适的 Worker Agent。
            你的职责：
            1. 分析任务目标，拆解为粒度适中、边界清晰的子任务
            2. 为每个子任务指定最合适的执行角色（researcher / writer / reviewer / critic）
            3. 明确子任务之间的依赖关系，形成有向无环图（DAG）
            4. 收集所有子任务结果后进行聚合，输出最终交付物
            工作原则：决策要果断、分配要均衡、依赖要明确、聚合要完整。
            """,
            List.of("task-decompose", "result-aggregate"),
            ModelConfig.builder()
                    .modelName("gpt-4o")
                    .temperature(AgentConstants.TEMPERATURE_PRECISE)
                    .maxTokens(4096)
                    .creative(false)
                    .provider("openai")
                    .build(),
            AgentConstants.TEMPERATURE_PRECISE
    );

    /** 研究员角色：负责信息检索、资料收集与事实核查，使用中等温度兼顾广度与准确。 */
    public static final AgentRole RESEARCHER = new AgentRole(
            "researcher",
            """
            你是「研究员 Agent」（Researcher），擅长信息检索、资料收集与事实核查。
            你的职责：
            1. 围绕给定主题进行多源检索，收集权威、最新的信息
            2. 对信息进行交叉验证，剔除不可靠来源
            3. 结构化整理调研结果，标注信息来源
            4. 识别信息缺口，必要时发起补充检索
            工作原则：信息要准确、来源要可信、覆盖要全面、结论要有据。
            """,
            List.of("search-trending-topics", "analyze-competitors", "get-hot-search-ranking"),
            ModelConfig.builder()
                    .modelName("gpt-4o")
                    .temperature(AgentConstants.TEMPERATURE_ANALYTICAL)
                    .maxTokens(4096)
                    .creative(false)
                    .provider("openai")
                    .build(),
            AgentConstants.TEMPERATURE_ANALYTICAL
    );

    /** 创作者角色：负责内容撰写与文案输出，使用高温度激发创造力。 */
    public static final AgentRole WRITER = new AgentRole(
            "writer",
            """
            你是「创作者 Agent」（Writer），擅长内容创作与文案撰写。
            你的职责：
            1. 基于调研资料与选题方向，撰写高质量原创内容
            2. 适配目标平台调性与读者画像，调整语言风格
            3. 注重结构清晰、逻辑连贯、可读性强
            4. 合理使用 Markdown 格式化，提升排版效果
            工作原则：内容要原创、表达要生动、结构要清晰、风格要契合。
            """,
            List.of("write-draft", "format-content"),
            ModelConfig.builder()
                    .modelName("gpt-4o")
                    .temperature(AgentConstants.TEMPERATURE_CREATIVE)
                    .maxTokens(8192)
                    .creative(true)
                    .provider("openai")
                    .build(),
            AgentConstants.TEMPERATURE_CREATIVE
    );

    /** 审稿人角色：负责内容评审与质量把控，使用低温度保证评审的客观性。 */
    public static final AgentRole REVIEWER = new AgentRole(
            "reviewer",
            """
            你是「审稿人 Agent」（Reviewer），负责内容评审与质量把控。
            你的职责：
            1. 从逻辑性、可读性、原创性三个维度评估内容质量
            2. 检查事实准确性、结构完整性、语言规范性
            3. 输出结构化的评审意见与质量评分
            4. 给出具体、可执行的修改建议
            工作原则：评审要客观、维度要全面、建议要具体、标准要一致。
            """,
            List.of("assess-quality", "check-facts"),
            ModelConfig.builder()
                    .modelName("gpt-4o")
                    .temperature(AgentConstants.TEMPERATURE_ANALYTICAL)
                    .maxTokens(4096)
                    .creative(false)
                    .provider("openai")
                    .build(),
            AgentConstants.TEMPERATURE_ANALYTICAL
    );

    /** 批评者角色：负责挑刺与提出改进建议，使用中等偏低温度保证批判的尖锐性与建设性。 */
    public static final AgentRole CRITIC = new AgentRole(
            "critic",
            """
            你是「批评者 Agent」（Critic），以挑刺与提出反例见长，帮助团队发现盲点。
            你的职责：
            1. 站在读者与对手的视角，找出内容的薄弱环节
            2. 提出尖锐但有建设性的反对意见与改进方向
            3. 识别潜在的风险、歧义与逻辑漏洞
            4. 推动内容从「不错」迭代到「优秀」
            工作原则：质疑要有理、批评要尖锐、建议要可落地、态度要建设性。
            """,
            List.of("find-weakness", "suggest-improvements"),
            ModelConfig.builder()
                    .modelName("gpt-4o")
                    .temperature(AgentConstants.TEMPERATURE_ANALYTICAL)
                    .maxTokens(4096)
                    .creative(false)
                    .provider("openai")
                    .build(),
            AgentConstants.TEMPERATURE_ANALYTICAL
    );

    /** 全部预定义角色列表，便于遍历注册。 */
    public static final List<AgentRole> PREDEFINED = List.of(
            SUPERVISOR, RESEARCHER, WRITER, REVIEWER, CRITIC
    );

    /**
     * 紧凑构造器：校验必填字段并规范化工具列表。
     *
     * @throws IllegalArgumentException 当 roleName 或 systemPrompt 为空时
     */
    public AgentRole {
        if (roleName == null || roleName.isBlank()) {
            throw new IllegalArgumentException("roleName 不能为空");
        }
        if (systemPrompt == null || systemPrompt.isBlank()) {
            throw new IllegalArgumentException("systemPrompt 不能为空");
        }
        if (modelConfig == null) {
            throw new IllegalArgumentException("modelConfig 不能为空");
        }
        tools = tools == null ? List.of() : List.copyOf(tools);
    }

    /**
     * 根据角色名称从预定义角色中查找。
     *
     * @param roleName 角色名称（不区分大小写）
     * @return 匹配的预定义角色
     * @throws IllegalArgumentException 当角色名称未预定义时
     */
    public static AgentRole fromName(String roleName) {
        if (roleName == null || roleName.isBlank()) {
            throw new IllegalArgumentException("roleName 不能为空");
        }
        return PREDEFINED.stream()
                .filter(r -> r.roleName.equalsIgnoreCase(roleName.trim()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未预定义的 Agent 角色: " + roleName));
    }

    /**
     * 判断当前角色是否为创意类角色（温度较高）。
     *
     * @return true 表示该角色使用高温度模型激发创造力
     */
    public boolean isCreative() {
        return temperature >= AgentConstants.TEMPERATURE_CREATIVE;
    }

    /**
     * 基于当前角色派生一个新角色，允许覆盖部分字段。
     *
     * @param newSystemPrompt 新系统提示词（为 null 时保留原值）
     * @param newTemperature  新温度（为 {@code null} 时保留原值）
     * @return 派生的新角色实例
     */
    public AgentRole derive(String newSystemPrompt, Double newTemperature) {
        return new AgentRole(
                roleName,
                newSystemPrompt != null ? newSystemPrompt : systemPrompt,
                tools,
                modelConfig,
                newTemperature != null ? newTemperature : temperature
        );
    }
}
