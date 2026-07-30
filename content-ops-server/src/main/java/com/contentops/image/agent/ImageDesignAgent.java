package com.contentops.image.agent;

import com.contentops.common.dto.ImageDesignResult;
import com.contentops.common.dto.StyleDirectionResult;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.spring.AiService;

import java.util.List;

/**
 * LangChain4j AI Service for content image design and cover generation.
 *
 * <p><b>P1 渐进式生成（两阶段 Agent）：</b>
 * <ul>
 *   <li>阶段一 {@link #generateStyleDirections}：分析文章内容，提取视觉关键词，
 *       给出 3 个配图风格方向供人工选择 —— 「先定调子，再出图」</li>
 *   <li>阶段二 {@link #generateImages}：基于确认的风格方向批量生成文章配图和平台封面</li>
 *   <li>兼容方法 {@link #designImages}：一次性生成全部配图（保留向后兼容）</li>
 * </ul>
 *
 * <p>实际 bean 由 {@link com.contentops.image.config.ImageAgentConfig} 通过
 * {@code AiServices.builder()} 构建，注入 {@link com.contentops.image.tool.ImageTools}。
 */
@AiService
@SystemMessage("""
        你是「配图设计Agent」，一个专业的AI图片生成规划师。你的核心任务是根据文章内容生成合适的配图和封面。

        工作原则：
        1. 分析文章内容，提取核心视觉元素
        2. 为每个配图位置生成详细的图片描述提示词
        3. 调用 generateImagePrompt 工具时，工具会自动调用 DALL-E 3 API 生成真实图片并返回 imageUrl
        4. 为不同平台生成不同尺寸的封面图
        5. 确保配图风格与文章调性一致
        6. 将工具返回的 imageUrl 填入 ImageDesignResult 的对应字段

        配图规则：
        - 文章配图：2-3张，分别用于开头、文中、结尾
        - 封面图：每个目标平台一张
          * 公众号/头条：横版 1792x1024（DALL-E支持的最大横版尺寸）
          * 小红书：竖版 1024x1792（DALL-E支持的最大竖版尺寸）
        - 图片风格：暖色调、有生活气息、与内容匹配
        - 避免过于抽象或与内容无关的图片

        ── Few-shot 示例 ──────────────────────────────────────

        【示例】
        文章标题：为什么你越努力越焦虑？
        视觉关键词：加班、焦虑、疲惫、灯、电脑、咖啡、深夜
        风格方向1：写实摄影风——暖黄色台灯光下的凌乱办公桌，暗示深夜加班
        风格方向2：扁平插画风——简约人物+情绪符号，适合小红书年轻受众
        风格方向3：极简文字风——大字报式排版，"忙≠高效"直接冲击视觉
        配图提示词示例（开头图）：
          "A cluttered office desk at 2 AM, warm desk lamp light, half-empty coffee cup,
          laptop screen glowing in the dark, tired but determined atmosphere,
          photorealistic style, warm color tone"
        ────────────────────────────────────────────────────────

        注：当动态 Prompt 启用时（contentops.prompt.enabled=true），本注解内容将被
        PromptFragmentService 动态组装的版本覆盖，后者会根据文章调性和平台追加专属指导片段，
        并支持 A/B 测试变体。
        """)
public interface ImageDesignAgent {

    // ══════════════════ 阶段一：风格方向 ══════════════════

    /**
     * 阶段一：生成配图风格方向。
     *
     * <p>分析文章内容，提取视觉关键词，给出 3 个候选风格方向
     * （含风格名称、描述、色调建议、提示词前缀、推荐指数）。
     * 供人工选择后再调用 {@link #generateImages} 批量生图。
     *
     * @param memoryId      对话记忆 ID
     * @param articleTitle  文章标题
     * @param articleContent 文章内容
     * @param articleTone   文章调性
     * @param targetPlatforms 目标平台
     * @return 风格方向结果（含视觉关键词 + 3 个风格方向 + 调性分析）
     */
    @UserMessage("""
            【阶段一：风格方向】

            请分析以下文章，生成 3 个配图风格方向供人工选择：

            - 文章标题：{{articleTitle}}
            - 文章内容：{{articleContent}}
            - 文章调性：{{articleTone}}
            - 目标平台：{{targetPlatforms}}

            要求：
            1. 调用 extractVisualKeywords 工具提取文章的视觉关键词
            2. 基于视觉关键词和文章调性，生成 3 个差异化的配图风格方向
            3. 每个风格方向需包含：风格名称、描述、色调建议、提示词前缀、适合位置、推荐指数(1-5)
            4. 给出文章整体调性分析
            5. 此阶段不生成具体图片，只给方向建议
            6. 返回结构化的风格方向结果
            """)
    StyleDirectionResult generateStyleDirections(@MemoryId String memoryId,
                                                 String articleTitle,
                                                 String articleContent,
                                                 String articleTone,
                                                 List<String> targetPlatforms);

    // ══════════════════ 阶段二：批量生图 ══════════════════

    /**
     * 阶段二：基于确认的风格方向批量生成配图和封面。
     *
     * <p>接收阶段一产出（可能经人工选择/修改）的确认风格，生成具体的文章配图
     * （2-3 张）和各平台封面图。调用 {@code generateImagePrompt} 工具生成提示词。
     *
     * @param memoryId        对话记忆 ID（与阶段一相同）
     * @param confirmedStyle  人工确认的风格方向（通常是阶段一选择的方向描述）
     * @param articleTitle    文章标题
     * @param articleContent  文章内容
     * @param articleTone     文章调性
     * @param targetPlatforms 目标平台
     * @return 配图设计结果（含生成的配图列表和平台封面列表）
     */
    @UserMessage("""
            【阶段二：批量生图】

            基于以下已确认的配图风格方向，生成具体的配图和封面：

            确认风格方向：
            {{confirmedStyle}}

            - 文章标题：{{articleTitle}}
            - 文章内容：{{articleContent}}
            - 文章调性：{{articleTone}}
            - 目标平台：{{targetPlatforms}}

            要求：
            1. 按照确认的风格方向，调用 generateImagePrompt 工具为每个配图位置生成详细提示词并自动生成真实图片。该工具会返回 imageUrl，请将返回的 imageUrl 填入结果中
            2. 生成 2-3 张文章配图（开头、文中、结尾各一张），根据位置选择合适尺寸：
               开头/文中配图使用 1792x1024（横版），结尾配图使用 1024x1024（正方形）
            3. 为每个目标平台生成封面图：
               公众号/头条使用 1792x1024（横版），小红书使用 1024x1792（竖版）
            4. 确保所有配图风格统一，与确认的方向一致
            5. 将 generateImagePrompt 返回的 imageUrl 填入 ImageDesignResult 的 imageUrl 字段
            6. 返回结构化的配图设计结果
            """)
    ImageDesignResult generateImages(@MemoryId String memoryId,
                                     String confirmedStyle,
                                     String articleTitle,
                                     String articleContent,
                                     String articleTone,
                                     List<String> targetPlatforms);

    // ══════════════════ 兼容方法：一次性生成 ══════════════════

    /**
     * 一次性生成全部配图和封面。
     *
     * <p><b>已过时</b>：新流程应使用 {@link #generateStyleDirections} + {@link #generateImages} 两阶段方式。
     * 保留此方法用于向后兼容和快速测试。
     *
     * @param articleTitle    文章标题
     * @param articleContent  文章内容
     * @param articleTone     文章调性
     * @param targetPlatforms 目标平台
     * @return 配图设计结果
     */
    @UserMessage("""
            请根据以下文章信息生成配图和封面：
            - 文章标题：{{articleTitle}}
            - 文章内容：{{articleContent}}
            - 文章调性：{{articleTone}}
            - 目标平台：{{targetPlatforms}}

            请调用可用工具提取视觉关键词，然后调用 generateImagePrompt 工具生成图片描述提示词并自动生成真实图片。
            工具会返回 imageUrl，请将其填入 ImageDesignResult 的对应字段。
            按照系统提示的配图规则与输出要求，返回结构化的配图设计结果
            （文章配图列表含prompt和imageUrl和位置、平台封面列表含尺寸和imageUrl和描述）。
            """)
    @Deprecated(since = "P1", forRemoval = false)
    ImageDesignResult designImages(@MemoryId String memoryId,
                                   String articleTitle,
                                   String articleContent,
                                   String articleTone,
                                   List<String> targetPlatforms);
}
