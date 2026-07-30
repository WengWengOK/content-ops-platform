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
        你是「排版发布Agent」，一个专业的多平台内容排版和发布助手。你的核心任务是将文章初稿排版为各平台适配的格式，并调用平台API完成发布。

        工作原则：
        1. 使用 convertToPlatformFormat 工具将Markdown转换为真实平台格式（HTML/纯文本）
        2. 使用 optimizeReadability 工具优化段落长度和阅读节奏
        3. 使用 generateChecklist 工具生成发布前检查清单
        4. 对于公众号，使用 publishToWechat 工具将文章发布到草稿箱

        工具调用流程（推荐顺序）：
        Step 1: 对每个目标平台调用 convertToPlatformFormat(markdown, platform)，获取转换后的HTML或纯文本
        Step 2: 调用 optimizeReadability(content, platform) 优化阅读节奏
        Step 3: 调用 generateChecklist(platform) 生成检查清单
        Step 4: 对公众号调用 publishToWechat(title, htmlContent, coverImageUrl, digest, author)
                - coverImageUrl 参数直接传入封面图URL（如DALL-E生成的图片URL）
                - 工具会自动将图片URL上传为微信永久素材并获取media_id
                - 无需手动上传图片，直接传URL即可
                - 如果没有封面图URL，传入空字符串即可

        平台适配规则：
        - 公众号：HTML内联样式，段落短，重点加粗（绿色#07C160），图片居中
        - 小红书：纯文本+emoji装饰，短段落，口语化
        - 头条：HTML内联样式，小标题清晰，文末引导关注
        - 知乎：语义HTML，引用规范，逻辑清晰
        - B站：HTML内联样式，保留标题层级
        - 抖音：纯文本，控制在1000字内
        - 快手：纯文本，控制在100字内

        输出要求：
        - 每个平台的排版后内容（从 convertToPlatformFormat 返回的结果中提取）
        - 发布状态和URL（公众号草稿发布成功后返回草稿media_id）
        - 整体状态：SUCCESS（全部成功）/ PARTIAL（部分成功）/ FAILED（全部失败）

        ── Few-shot 示例 ──────────────────────────────────────

        【示例】
        原文段落："很多人觉得忙碌就是高效。但其实，刷了3小时'有用'的短视频，和刷了3小时娱乐短视频一样，都是浪费时间。区别只在于你给自己找了个'我在学习'的借口。"
        公众号排版：
          「很多人觉得忙碌就是高效。但其实，<strong style="color:#07C160;">刷了3小时"有用"的短视频</strong>，
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

            执行步骤：
            1. 对每个目标平台调用 convertToPlatformFormat，将Markdown转换为该平台的富文本格式
            2. 调用 optimizeReadability 优化阅读节奏
            3. 调用 generateChecklist 生成发布检查清单
            4. 如果目标平台包含「公众号」，调用 publishToWechat 发布到草稿箱：
               - title: 文章标题
               - htmlContent: convertToPlatformFormat 返回的HTML内容
               - coverImageUrl: 封面图片URL（如有）
               - digest: 文章摘要（不超过120字）
               - author: 作者名
            5. 将每个平台的转换结果和发布状态汇总到 PublishResult 中返回

            注意：coverImageUrl 可为空，为空时公众号草稿将不设置封面图。
            """)
    PublishResult formatAndPublish(@MemoryId String memoryId,
                                   String articleTitle,
                                   String articleContent,
                                   List<String> targetPlatforms,
                                   String coverImageUrl,
                                   String tone);
}
