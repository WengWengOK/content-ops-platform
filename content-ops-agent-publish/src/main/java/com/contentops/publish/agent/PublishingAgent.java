package com.contentops.publish.agent;

import com.contentops.common.dto.PublishResult;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.spring.AiService;

import java.util.List;

/**
 * LangChain4j AI Service for multi-platform content formatting and publishing.
 *
 * <p>The {@code @AiService} marker is retained for declarative intent, but the actual bean is
 * built programmatically in {@link com.contentops.publish.config.PublishAgentConfig} using
 * {@code AiServices.builder()} so that the {@link com.contentops.publish.tool.PublishTools}
 * are explicitly wired in.
 */
@AiService
@SystemMessage("""
        你是「排版发布Agent」，一个专业的多平台内容排版和发布助手。你的核心任务是将文章初稿排版为各平台适配的格式。

        工作原则：
        1. 根据目标平台调整排版格式
        2. 插入配图到正确位置
        3. 优化段落长度和阅读节奏
        4. 添加适当的emoji和分隔符（根据平台风格）
        5. 生成各平台的最终发布内容

        平台适配规则：
        - 公众号：支持富文本，段落短，重点加粗，图片居中
        - 小红书：短段落，emoji丰富，口语化，图片穿插
        - 头条：段落适中，小标题清晰，文末引导关注
        - 知乎：专业排版，引用规范，逻辑清晰

        输出要求：
        - 每个平台的排版后内容
        - 发布状态和URL（如果支持自动发布）

        ── Few-shot 示例 ──────────────────────────────────────

        【示例】
        原文段落："很多人觉得忙碌就是高效。但其实，刷了3小时'有用'的短视频，和刷了3小时娱乐短视频一样，都是浪费时间。区别只在于你给自己找了个'我在学习'的借口。"
        公众号排版：
          「很多人觉得忙碌就是高效。但其实，**刷了3小时"有用"的短视频**，
          和刷了3小时娱乐短视频一样，都是浪费时间。
          区别只在于——你给自己找了个"我在学习"的借口。」
        小红书排版：
          「很多人觉得忙碌 = 高效 ❌
          但其实...
          刷3小时"有用"短视频 📱
          = 刷3小时娱乐短视频 🎬
          都是浪费时间！

          区别只是你给自己找了个"我在学习"的借口 😅
          你中枪了吗？👇」
        ────────────────────────────────────────────────────────

        注：当动态 Prompt 启用时（contentops.prompt.enabled=true），本注解内容将被
        PromptFragmentService 动态组装的版本覆盖，后者会根据文章调性和平台追加专属指导片段，
        并支持 A/B 测试变体。
        """)
public interface PublishingAgent {

    /**
     * Formats the article draft for each target platform and (where supported) publishes it.
     *
     * <p>LangChain4j parses the structured {@link PublishResult} return type, asks the model
     * to conform to the derived JSON schema, and deserializes the response automatically.
     *
     * @param articleTitle     the title of the article
     * @param articleContent   the full article content (Markdown or plain text)
     * @param targetPlatforms  target publishing platforms (e.g. 公众号, 小红书, 头条, 知乎)
     * @param coverImageUrl    the cover image URL (may be null if no cover is available)
     * @param tone             the desired tone/style of the article (e.g. 轻松、专业)
     * @return a structured publish result with per-platform publications and overall status
     */
    @UserMessage("""
            请根据以下信息进行多平台排版与发布：
            - 文章标题：{{articleTitle}}
            - 文章内容：{{articleContent}}
            - 目标平台：{{targetPlatforms}}
            - 封面图片URL：{{coverImageUrl}}
            - 文章调性：{{tone}}

            请调用可用工具进行格式转换、可读性优化并生成发布检查清单，按照系统提示的平台适配规则与输出要求，
            返回结构化的发布结果（每个平台的排版后内容、发布状态和URL）。
            """)
    PublishResult formatAndPublish(@MemoryId String memoryId,
                                   String articleTitle,
                                   String articleContent,
                                   List<String> targetPlatforms,
                                   String coverImageUrl,
                                   String tone);
}
