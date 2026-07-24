package com.contentops.content.agent;

import com.contentops.common.dto.ContentDraftResult;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.spring.AiService;

/**
 * LangChain4j AI Service for content draft creation.
 *
 * <p>The {@code @AiService} marker is retained for declarative intent, but the actual bean is
 * built programmatically in {@link com.contentops.content.config.ContentAgentConfig} using
 * {@code AiServices.builder()} so that the {@link com.contentops.content.tool.ContentTools}
 * are explicitly wired in.
 */
@AiService
@SystemMessage("""
        你是「内容创作Agent」，一个专业的自媒体文案撰写助手。你的核心任务是根据选题生成高质量的文章初稿。

        工作原则：
        1. 先搭建文章框架（大纲），包含：开头引入、正文分段、结尾总结
        2. 框架确认后再生成完整初稿
        3. 风格匹配账号定位（轻松/专业/感性等）
        4. 结合真实场景和例子，而非空泛说教
        5. 生成多个标题变体供选择
        6. 生成摘要和标签

        文章结构要求：
        - 开头：用场景/故事/提问引入，抓住注意力
        - 正文：每段有明确主题，用案例支撑观点
        - 结尾：总结升华，引导互动
        - 字数：1500-3000字
        - 格式：Markdown

        输出要求：
        - 文章框架（大纲）
        - 完整Markdown初稿
        - 3-5个标题变体
        - 5-10个标签
        - 100字以内的分享摘要
        """)
public interface ContentCreationAgent {

    /**
     * Creates a structured content draft for the given topic.
     *
     * <p>LangChain4j parses the structured {@link ContentDraftResult} return type, asks the
     * model to conform to the derived JSON schema, and deserializes the response automatically.
     *
     * @param topic             the selected topic/title to write about
     * @param angle             the angle/perspective to take
     * @param accountNiche      the account domain/niche (e.g. "个人成长")
     * @param targetAudience    the target audience description
     * @param tone              the desired tone/style of the account
     * @param outline           optional outline/framework constraint (may be null/empty)
     * @param additionalContext any extra instructions or context
     * @return a structured content draft with outline, Markdown body, titles, tags and summary
     */
    @UserMessage("""
            请根据以下信息创作文章初稿：
            - 选题：{{topic}}
            - 切入角度：{{angle}}
            - 账号领域：{{accountNiche}}
            - 目标受众：{{targetAudience}}
            - 风格调性：{{tone}}
            - 大纲要求：{{outline}}
            - 补充说明：{{additionalContext}}

            请先调用工具生成框架大纲并搜集相关案例素材，再按照系统提示的输出要求，
            返回结构化的文章初稿结果（框架大纲、完整Markdown初稿、3-5个标题变体、5-10个标签、100字以内的分享摘要）。
            """)
    ContentDraftResult createDraft(String topic,
                                   String angle,
                                   String accountNiche,
                                   String targetAudience,
                                   String tone,
                                   String outline,
                                   String additionalContext);
}
