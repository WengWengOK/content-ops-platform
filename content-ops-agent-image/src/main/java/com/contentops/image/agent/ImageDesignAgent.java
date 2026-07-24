package com.contentops.image.agent;

import com.contentops.common.dto.ImageDesignResult;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.spring.AiService;

import java.util.List;

/**
 * LangChain4j AI Service for content image design and cover generation.
 *
 * <p>The {@code @AiService} marker is retained for declarative intent, but the actual bean is
 * built programmatically in {@link com.contentops.image.config.ImageAgentConfig} using
 * {@code AiServices.builder()} so that the {@link com.contentops.image.tool.ImageTools}
 * are explicitly wired in.
 */
@AiService
@SystemMessage("""
        你是「配图设计Agent」，一个专业的AI图片生成规划师。你的核心任务是根据文章内容生成合适的配图和封面。

        工作原则：
        1. 分析文章内容，提取核心视觉元素
        2. 为每个配图位置生成详细的图片描述提示词
        3. 为不同平台生成不同尺寸的封面图
        4. 确保配图风格与文章调性一致
        5. 生成后可去除水印

        配图规则：
        - 文章配图：2-3张，分别用于开头、文中、结尾
        - 封面图：每个目标平台一张
          * 公众号：横版 900x383px
          * 小红书：竖版 1080x1440px
          * 头条：横版 660x370px
        - 图片风格：暖色调、有生活气息、与内容匹配
        - 避免过于抽象或与内容无关的图片

        输出要求：
        - 生成图片列表（每张配图的prompt和位置）
        - 平台封面列表（每个平台封面的尺寸和描述）
        """)
public interface ImageDesignAgent {

    /**
     * Designs article images and platform covers for the given content.
     *
     * <p>LangChain4j parses the structured {@link ImageDesignResult} return type, asks the model
     * to conform to the derived JSON schema, and deserializes the response automatically.
     *
     * @param articleTitle     the title of the article
     * @param articleContent   the full article content (Markdown or plain text)
     * @param articleTone      the desired tone/style of the article (e.g. 轻松、感性)
     * @param targetPlatforms  target publishing platforms (e.g. 公众号, 小红书, 头条)
     * @return a structured image design result with generated images and platform covers
     */
    @UserMessage("""
            请根据以下文章信息生成配图和封面：
            - 文章标题：{{articleTitle}}
            - 文章内容：{{articleContent}}
            - 文章调性：{{articleTone}}
            - 目标平台：{{targetPlatforms}}

            请调用可用工具提取视觉关键词并生成图片描述提示词，按照系统提示的配图规则与输出要求，
            返回结构化的配图设计结果（文章配图列表含prompt和位置、平台封面列表含尺寸和描述）。
            """)
    ImageDesignResult designImages(@MemoryId String memoryId,
                                   String articleTitle,
                                   String articleContent,
                                   String articleTone,
                                   List<String> targetPlatforms);
}
