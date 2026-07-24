package com.contentops.optimize.agent;

import com.contentops.common.dto.OptimizationResult;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.spring.AiService;

/**
 * LangChain4j AI Service for content operations strategy optimization.
 *
 * <p>The {@code @AiService} marker is retained for declarative intent, but the actual bean is
 * built programmatically in {@link com.contentops.optimize.config.OptimizeAgentConfig} using
 * {@code AiServices.builder()} so that the {@link com.contentops.optimize.tool.OptimizeTools}
 * are explicitly wired in.
 */
@AiService
@SystemMessage("""
        你是「优化迭代Agent」，一个专业的内容运营策略优化师。你的核心任务是根据数据分析结果调整运营策略。

        工作原则：
        1. 接收数据分析Agent的输出
        2. 识别表现好的方向和需要改进的方向
        3. 输出具体的策略调整建议
        4. 推荐下一周期的选题方向
        5. 总结本周期的经验教训
        6. 给出运营健康评分

        优化维度：
        - 内容类型调整：哪些类型应该多做/少做
        - 发布时间优化：最佳发布时间窗口
        - 平台重心调整：哪个平台值得更多投入
        - 内容风格微调：调性、长度、互动方式
        - 选题方向：基于数据推荐下周期3-5个选题

        输出要求：
        - 策略调整列表（维度、当前值、建议值、理由、预期影响）
        - 下周期推荐选题（3-5个）
        - 经验总结
        - 运营健康评分（0-100）
        - 周期总结

        ── Few-shot 示例 ──────────────────────────────────────

        【示例】
        数据分析摘要：干货教程类互动率6.1%最佳，周三21:00为黄金时段，观点输出类评论多但互动低
        策略调整：
          1. [内容类型] 当前：干货33% / 故事25% / 热点21% → 建议：干货40% / 故事30% / 热点15% / 观点15%
             理由：干货类互动率最高，故事类涨粉贡献最大，应增加这两类占比
             预期影响：整体互动率预计从4.6%提升到5.2%
          2. [发布时间] 当前：随机时段 → 建议：核心干货固定周三21:00
             理由：该时段平均阅读2.68万、互动率6.7%，显著优于其他时段
             预期影响：核心内容阅读量预计提升30%
        运营健康评分：72/100（内容质量优秀但发布策略和类型配比待优化）
        ────────────────────────────────────────────────────────

        注：当动态 Prompt 启用时（contentops.prompt.enabled=true），本注解内容将被
        PromptFragmentService 动态组装的版本覆盖，后者会根据账号领域追加专属指导片段，
        并支持 A/B 测试变体。
        """)
public interface OptimizationAgent {

    /**
     * Optimizes the content operations strategy based on analysis results.
     *
     * <p>LangChain4j parses the structured {@link OptimizationResult} return type, asks the
     * model to conform to the derived JSON schema, and deserializes the response automatically.
     *
     * @param accountNiche             the account domain/niche (e.g. "个人成长")
     * @param analysisSummary          the summary output from the Data Analysis Agent
     * @param currentStrategy          the current operations strategy description
     * @param historicalPerformance    historical performance context (may be null/empty)
     * @return a structured optimization result with strategy adjustments, recommended topics and health score
     */
    @UserMessage("""
            请根据以下信息优化运营策略：
            - 账号领域：{{accountNiche}}
            - 数据分析摘要：
            {{analysisSummary}}
            - 当前策略：
            {{currentStrategy}}
            - 历史表现：
            {{historicalPerformance}}

            请先调用可用工具对比当前策略与数据表现的差距、生成策略调整建议、评估运营健康度并打分，
            以及基于数据趋势推荐下周期选题。然后严格按照系统提示的输出要求，
            返回结构化的优化结果（策略调整列表、下周期推荐选题3-5个、经验总结、运营健康评分0-100、周期总结）。
            """)
    OptimizationResult optimizeStrategy(@MemoryId String memoryId,
                                        String accountNiche,
                                        String analysisSummary,
                                        String currentStrategy,
                                        String historicalPerformance);
}
