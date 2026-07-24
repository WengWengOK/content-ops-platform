package com.contentops.content.agent;

import com.contentops.common.dto.ContentDraftResult;
import com.contentops.common.dto.OutlineResult;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.spring.AiService;

/**
 * LangChain4j AI Service for content draft creation.
 *
 * <p><b>P1 渐进式生成（两阶段 Agent）：</b>
 * <ul>
 *   <li>阶段一 {@link #generateOutline}：生成文章框架大纲（含标题、段落结构、写作要点），
 *       供人工确认后再进入下一阶段 —— 「先搭框架，别一步到位」</li>
 *   <li>阶段二 {@link #generateDraft}：基于确认的大纲生成完整 Markdown 初稿、标题变体、标签和摘要</li>
 *   <li>兼容方法 {@link #createDraft}：一次性生成完整结果（保留向后兼容，不推荐用于新流程）</li>
 * </ul>
 *
 * <p>实际 bean 由 {@link com.contentops.content.config.ContentAgentConfig} 通过
 * {@code AiServices.builder()} 构建，注入 {@link com.contentops.content.tool.ContentTools}。
 */
@AiService
@SystemMessage("""
        你是「内容创作Agent」，一个专业的自媒体文案撰写助手。你的核心任务是根据选题生成高质量的文章。

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
        """)
public interface ContentCreationAgent {

    // ══════════════════ 阶段一：大纲生成 ══════════════════

    /**
     * 阶段一：生成文章框架大纲。
     *
     * <p>只生成大纲结构（标题、段落标题、写作要点、参考素材），不写正文。
     * 调用 {@code generateOutline} 工具检索知识库中的历史文章作为参考。
     *
     * <p>生成后返回给人工确认，确认后再调用 {@link #generateDraft}。
     *
     * @param memoryId         对话记忆 ID（格式：{agentCode}:{workflowId}）
     * @param topic            选题
     * @param angle            切入角度
     * @param accountNiche     账号领域
     * @param targetAudience   目标受众
     * @param tone             风格调性
     * @param additionalContext 补充说明
     * @return 大纲结果（含标题、框架、写作要点、参考、预计字数）
     */
    @UserMessage("""
            【阶段一：大纲生成】

            请根据以下信息生成文章框架大纲（此阶段不写正文，只搭框架）：
            - 选题：{{topic}}
            - 切入角度：{{angle}}
            - 账号领域：{{accountNiche}}
            - 目标受众：{{targetAudience}}
            - 风格调性：{{tone}}
            - 补充说明：{{additionalContext}}

            要求：
            1. 调用 generateOutline 工具检索知识库中的历史文章作为参考
            2. 生成包含开头引入、正文分段（每段有标题和要点）、结尾总结的完整大纲
            3. 为每段提供写作要点提示（不写正文内容）
            4. 给出预计字数
            5. 返回结构化的大纲结果
            """)
    OutlineResult generateOutline(@MemoryId String memoryId,
                                   String topic,
                                   String angle,
                                   String accountNiche,
                                   String targetAudience,
                                   String tone,
                                   String additionalContext);

    // ══════════════════ 阶段二：初稿生成 ══════════════════

    /**
     * 阶段二：基于确认的大纲生成完整初稿。
     *
     * <p>接收阶段一产出的（可能经过人工修改的）大纲，展开为完整的 Markdown 文章。
     * 调用 {@code saveDraft} 工具将初稿保存到本地文件并入库知识库。
     *
     * @param memoryId       对话记忆 ID（与阶段一相同，延续对话上下文）
     * @param confirmedOutline 人工确认后的大纲（通常是阶段一的产出，可能含修改）
     * @param topic          选题
     * @param accountNiche   账号领域
     * @param tone           风格调性
     * @param niche          账号领域（用于知识库入库）
     * @param workflowId     工作流 ID（用于文件命名和知识库追溯）
     * @return 完整的内容初稿结果（大纲、Markdown 正文、标题变体、标签、摘要）
     */
    @UserMessage("""
            【阶段二：初稿生成】

            基于以下已确认的大纲，生成完整的文章初稿：

            确认大纲：
            {{confirmedOutline}}

            选题：{{topic}}
            账号领域：{{accountNiche}}
            风格调性：{{tone}}

            要求：
            1. 严格按照大纲结构展开正文，每个段落都要有充实的内容（不是只写要点）
            2. 结合真实场景和例子，而非空泛说教
            3. 生成 3-5 个标题变体
            4. 生成 5-10 个 SEO 标签
            5. 生成 100 字以内的分享摘要
            6. 调用 saveDraft 工具将初稿保存为 Markdown 文件并入库知识库
            7. 返回结构化的文章初稿结果
            """)
    ContentDraftResult generateDraft(@MemoryId String memoryId,
                                     String confirmedOutline,
                                     String topic,
                                     String accountNiche,
                                     String tone,
                                     String niche,
                                     String workflowId);

    // ══════════════════ 兼容方法：一次性生成 ══════════════════

    /**
     * 一次性生成完整的文章初稿（含大纲+正文+标题+标签）。
     *
     * <p><b>已过时</b>：新流程应使用 {@link #generateOutline} + {@link #generateDraft} 两阶段方式。
     * 保留此方法用于向后兼容和快速测试。
     *
     * @param topic             选题
     * @param angle             切入角度
     * @param accountNiche      账号领域
     * @param targetAudience    目标受众
     * @param tone              风格调性
     * @param outline           大纲要求
     * @param additionalContext 补充说明
     * @return 完整的内容初稿结果
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
    @Deprecated(since = "P1", forRemoval = false)
    ContentDraftResult createDraft(@MemoryId String memoryId,
                                   String topic,
                                   String angle,
                                   String accountNiche,
                                   String targetAudience,
                                   String tone,
                                   String outline,
                                   String additionalContext);
}
