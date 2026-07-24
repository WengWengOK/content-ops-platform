package com.contentops.topic.agent;

import com.contentops.common.dto.TopicPlanResult;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.spring.AiService;

import java.util.List;

/**
 * LangChain4j AI Service for content topic planning.
 *
 * <p>The {@code @AiService} marker is retained for declarative intent, but the actual bean is
 * built programmatically in {@link com.contentops.topic.config.TopicAgentConfig} using
 * {@code AiServices.builder()} so that the {@link com.contentops.topic.tool.TopicResearchTools}
 * are explicitly wired in.
 */
@AiService
@SystemMessage("""
        你是「选题策划Agent」，一个专业的内容选题分析师。你的核心任务是帮自媒体创作者做选题决策。

        你的能力包括：
        - 联网搜索当前热点话题和趋势
        - 分析竞品账号的内容方向
        - 结合账号定位推荐选题
        - 为每个选题提供切入角度和预期效果

        工作原则：
        1. 先明确账号定位（领域、目标受众、风格调性）
        2. 联网搜索该领域近7天的热门话题和关键词
        3. 分析竞品在同类话题上的表现
        4. 推荐3-5个选题，每个包含：标题、切入角度、推荐理由、预期互动率
        5. 为不同平台适配标题（公众号、小红书、头条等）

        输出要求：
        - 提供3-5个选题候选
        - 每个选题附带关键词标签
        - 给出平台适配的标题变体
        - 包含趋势关键词列表
        - 输出竞品分析摘要和推荐方向
        """)
public interface TopicPlanningAgent {

    /**
     * Plans content topics for the given account profile.
     *
     * <p>LangChain4j parses the structured {@link TopicPlanResult} return type, asks the model
     * to conform to the derived JSON schema, and deserializes the response automatically.
     *
     * @param accountNiche       the account domain/niche (e.g. "个人成长")
     * @param targetAudience     the target audience description
     * @param tone               the desired tone/style of the account
     * @param platforms          target publishing platforms (e.g. 公众号, 小红书, 头条)
     * @param additionalContext  any extra instructions or context
     * @return a structured topic plan with candidates, keywords and competitive analysis
     */
    @UserMessage("""
            请为以下账号进行选题策划：
            - 账号领域：{{accountNiche}}
            - 目标受众：{{targetAudience}}
            - 风格调性：{{tone}}
            - 目标平台：{{platforms}}
            - 补充说明：{{additionalContext}}

            请调用可用工具进行联网热点调研与竞品分析，并严格按照系统提示的输出要求，
            返回结构化的选题方案（3-5个选题候选、关键词标签、平台适配标题、趋势关键词、竞品分析摘要和推荐方向）。
            """)
    TopicPlanResult planTopics(String accountNiche,
                               String targetAudience,
                               String tone,
                               List<String> platforms,
                               String additionalContext);
}
