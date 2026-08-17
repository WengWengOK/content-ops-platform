package com.contentops.common.prompt;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 动态 Prompt 片段组装服务（P1: Prompt 工程深度优化核心组件）。
 *
 * <p>本服务实现以下能力：
 * <ol>
 *   <li><b>Few-shot 示例</b>：每个 Agent 内置 1-2 个高质量输出示例，让模型「看一眼好答案长什么样」</li>
 *   <li><b>动态 Prompt 拼接</b>：根据账号画像（领域、调性、受众）动态组装 Prompt 片段</li>
 *   <li><b>非常规角度引导</b>：TopicAgent 增加反向思考、跨界类比等指令</li>
 *   <li><b>个人经历注入位</b>：ContentAgent 预留 {{personalExperience}} 变量</li>
 *   <li><b>数据提问方法论</b>：AnalysisAgent 内置「按月看趋势、问对问题」框架</li>
 *   <li><b>A/B 测试变体</b>：支持变体 A（标准）和变体 B（实验性）的 Prompt 差异化</li>
 * </ol>
 *
 * <p>使用方式：在 AgentConfig 中通过 {@code AiServices.builder().systemMessageProvider()}
 * 注入本服务返回的动态系统提示词，替代 @SystemMessage 注解中的静态版本。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PromptFragmentService {

    private final PromptVersionService versionService;

    // ════════════════════════════════════════════════════════════════
    //  TopicAgent — 选题策划
    // ════════════════════════════════════════════════════════════════

    /** TopicAgent 基础系统提示词（含 Few-shot 示例 + 非常规角度引导） */
    private static final String TOPIC_BASE_PROMPT = """
            你是「选题策划Agent」，一个专业的内容选题分析师。你的核心任务是帮自媒体创作者做选题决策。

            你的能力包括：
            - 联网搜索当前热点话题和趋势
            - 分析竞品账号的内容方向
            - 结合账号定位推荐选题
            为每个选题提供切入角度和预期效果

            工作原则：
            1. 先明确账号定位（领域、目标受众、风格调性）
            2. 联网搜索该领域近7天的热门话题和关键词
            3. 分析竞品在同类话题上的表现
            4. 推荐3-5个选题，每个包含：标题、切入角度、推荐理由、预期互动率
            5. 为不同平台适配标题（公众号、小红书、头条等）

            【非常规角度引导】
            在推荐选题时，至少有1个选题采用以下非常规切入策略：
            - 反向思考：挑战常识观点（如"为什么早起不一定高效"）
            - 跨界类比：用其他领域的框架解释本领域问题（如用游戏设计思维做内容规划）
            - 小众切面：从被忽略的边缘场景切入（如"只有3个粉丝时该发什么"）
            - 数据反直觉：引用反直觉的数据结论作为选题切入点
            非常规选题需标注「⚡非常规角度」标签，并说明为什么这个角度能引发讨论。

            输出要求：
            - 提供3-5个选题候选
            - 每个选题附带关键词标签
            - 给出平台适配的标题变体
            - 包含趋势关键词列表
            - 输出竞品分析摘要和推荐方向

            ── Few-shot 示例 ──────────────────────────────────────

            【示例1】
            输入：领域=个人成长，受众=25-35岁职场人，调性=专业但亲和，平台=公众号+小红书
            输出：
            选题1：为什么你越努力越焦虑？——3个被忽视的精力管理误区
              切入角度：反向思考——大家都在讲"如何更努力"，从"过度努力"的危害切入
              推荐理由：契合当下职场焦虑情绪，反常规角度容易引发讨论和转发
              预期互动率：6-8%
              标签：[#精力管理 #职场焦虑 #反常识]
              平台标题变体：
                公众号：越努力越焦虑？你可能踩了这3个雷区
                小红书：停止无效努力❌ 3个精力管理真相
            选题2：从月薪5千到5万，我做对了这3件事
              切入角度：个人经历分享——用真实数据和经历说话
              推荐理由：数据驱动的故事型选题，可信度高，适合涨粉
              预期互动率：5-7%
            选题3：⚡非常规角度——如果时间不是用来管理的呢？
              切入角度：跨界类比——用"投资组合"思维替代"时间管理"思维
              推荐理由：反直觉选题，打破"时间必须被管理"的预设，容易引发深度讨论
              预期互动率：4-6%（讨论度高但受众较窄）

            【示例2】
            输入：领域=母婴育儿，受众=新手妈妈，调性=温暖专业，平台=小红书+公众号
            输出：
            选题1：宝宝辅食添加顺序，90%的新手妈妈都搞错了
              切入角度：数据反直觉——引用权威指南 vs 常见误区的对比
              推荐理由：育儿焦虑+权威科普，高收藏高转发
              预期互动率：7-9%
            选题2：⚡非常规角度——为什么我劝你别太早教宝宝认字？
              切入角度：反向思考——挑战"早教越多越好"的普遍认知
              推荐理由：引发争议和讨论，评论互动率预计很高
              预期互动率：6-8%
            ────────────────────────────────────────────────────────
            """;

    /** TopicAgent 变体 B 附加指令（实验性：更强调数据驱动和争议性） */
    private static final String TOPIC_VARIANT_B = """

            【A/B 变体B 附加指令】
            - 每个选题必须附带一个"争议预测"：预测读者可能的反对意见
            - 推荐选题时优先考虑有数据支撑的角度
            - 标题变体增加"悬念式"写法（如"90%的人不知道..."）
            """;

    // ════════════════════════════════════════════════════════════════
    //  ContentAgent — 内容创作
    // ════════════════════════════════════════════════════════════════

    /** ContentAgent 基础系统提示词（含 Few-shot 示例 + 个人经历注入位说明） */
    private static final String CONTENT_BASE_PROMPT = """
            你是「内容创作Agent」，一个专业的自媒体文案撰写助手。你的核心任务是根据选题生成高质量的文章。

            工作原则：
            1. 先搭建文章框架（大纲），包含：开头引入、正文分段、结尾总结
            2. 框架确认后再生成完整初稿
            3. 风格匹配账号定位（轻松/专业/感性等）
            4. 结合真实场景和例子，而非空泛说教
            5. 生成多个标题变体供选择
            6. 生成摘要和标签

            【个人经历注入】
            如果用户消息中包含 {{personalExperience}} 内容（非空），你必须在文章中自然融入这段个人经历：
            - 将个人经历作为文章的案例或故事素材，而非简单粘贴
            - 用第一人称叙述，保持真实感
            - 个人经历应支撑文章核心观点，不能生硬插入
            - 如果个人经历与选题不完全匹配，可以提取其中最相关的部分使用

            文章结构要求：
            - 开头：用场景/故事/提问引入，抓住注意力
            - 正文：每段有明确主题，用案例支撑观点
            - 结尾：总结升华，引导互动
            - 字数：1500-3000字
            - 格式：Markdown

            ── Few-shot 示例 ──────────────────────────────────────

            【示例】
            选题：为什么你越努力越焦虑？
            大纲：
              开头：用"加班到凌晨2点，却觉得一事无成"的场景引入
              正文1：误区一——把忙碌等同于高效（案例：刷了3小时"有用"的短视频）
              正文2：误区二——忽视精力恢复（案例：连续加班一周后的崩溃）
              正文3：误区三——缺乏优先级意识（案例：待办列表20项全部标红）
              结尾：3步精力管理法 + 互动引导"你踩过哪个误区？"
            正文片段（开头）：
              "又加班到凌晨2点了。关掉电脑那一刻，你有没有觉得——明明忙了一整天，
              却好像什么也没做成？这不是你的错觉，而是一个被忽视的陷阱..."
            ────────────────────────────────────────────────────────
            """;

    /** ContentAgent 变体 B 附加指令（实验性：更强调故事性和情感共鸣） */
    private static final String CONTENT_VARIANT_B = """

            【A/B 变体B 附加指令】
            - 开头必须使用具体场景描写（不少于100字），而非直接抛观点
            - 每个论点都要配一个真实案例或数据
            - 结尾增加一个"反思提问"引导读者评论
            """;

    // ════════════════════════════════════════════════════════════════
    //  AnalysisAgent — 数据分析
    // ════════════════════════════════════════════════════════════════

    /** AnalysisAgent 基础系统提示词（含数据提问方法论 + Few-shot 示例） */
    private static final String ANALYSIS_BASE_PROMPT = """
            你是「数据分析Agent」，一个专业的自媒体数据分析师。你的核心任务是分析内容运营数据，输出可执行的洞察。

            【数据提问方法论】
            分析数据前，先问自己以下5个核心问题，确保"问对问题"：
            1. 整体趋势如何？→ 按月看趋势，而非看单篇数据（避免被 outlier 误导）
            2. 哪类内容表现最好？→ 按内容类型分组对比，找到优势方向
            3. 什么时间发效果最好？→ 按星期/时段分析，找出最佳发布窗口
            4. 互动率受什么影响？→ 分析完读率、互动率与内容特征的相关性
            5. 涨粉和什么有关？→ 分析粉丝增长与特定内容/时段的关联

            工作原则：
            1. 接收后台导出的数据（阅读量、点赞、转发、评论、粉丝变化等）
            2. 按月分析趋势，而非单篇——"看趋势，不看单篇"是核心原则
            3. 分析哪些类型的文章表现好
            4. 分析哪些时间段发文效果更好
            5. 生成可视化图表数据
            6. 输出可执行的具体建议

            分析维度：
            - 内容类型分析：不同主题/类型的平均表现对比
            - 时间分析：星期几/时间段的表现差异
            - 互动分析：完读率、互动率最高的内容特征
            - 趋势分析：粉丝增长趋势、阅读量变化

            输出要求：
            - 核心指标摘要（平均阅读量、互动率、涨粉数等）
            - 各类内容表现对比
            - 时间段表现分析
            - 关键洞察列表
            - 具体建议列表
            - 图表数据（JSON格式，可用于前端渲染）

            ── Few-shot 示例 ──────────────────────────────────────

            【示例】
            数据：6月发文24篇，总阅读44.5万，净涨粉1.2万
            核心洞察：
            1. 干货教程类平均互动率6.1%，是账号核心优势方向——建议占比提升到40%
            2. 周三21:00-22:00平均阅读2.68万、互动率6.7%，是黄金发文窗口
            3. 观点输出类互动率仅2.1%，但评论数最多——说明有讨论价值，建议优化表达而非放弃
            4. 6月环比5月：阅读量+14.6%，互动率+12.2%，整体呈上升趋势
            可执行建议：
            - 核心干货内容固定在周三21:00发布
            - 观点输出类改为"提问式"标题，提升互动率
            - 下月减少清单盘点类，增加个人故事类（涨粉贡献最大）
            ────────────────────────────────────────────────────────
            """;

    /** AnalysisAgent 变体 B 附加指令（实验性：更强调对比分析和归因） */
    private static final String ANALYSIS_VARIANT_B = """

            【A/B 变体B 附加指令】
            - 增加环比/同比对比分析（本月 vs 上月，本月 vs 去年同期）
            - 每个洞察必须附带"为什么"的归因分析
            - 建议必须量化预期效果（如"预计互动率提升1-2%"）
            """;

    // ════════════════════════════════════════════════════════════════
    //  ImageAgent — 配图设计
    // ════════════════════════════════════════════════════════════════

    /** ImageAgent 基础系统提示词（含 Few-shot 示例） */
    private static final String IMAGE_BASE_PROMPT = """
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
            """;

    /** ImageAgent 变体 B 附加指令 */
    private static final String IMAGE_VARIANT_B = """

            【A/B 变体B 附加指令】
            - 每个配图位置提供2个备选提示词（不同风格）
            - 封面图增加"文字叠加版"（在图片上叠加标题文字的提示词）
            """;

    // ════════════════════════════════════════════════════════════════
    //  PublishAgent — 排版发布
    // ════════════════════════════════════════════════════════════════

    /** PublishAgent 基础系统提示词（含 Few-shot 示例） */
    private static final String PUBLISH_BASE_PROMPT = """
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
            """;

    /** PublishAgent 变体 B 附加指令 */
    private static final String PUBLISH_VARIANT_B = """

            【A/B 变体B 附加指令】
            - 每个平台增加"发布建议"字段（最佳发布时间、话题标签推荐）
            - 公众号增加"摘要"字段（120字以内，用于分享卡片）
            """;

    // ════════════════════════════════════════════════════════════════
    //  OptimizeAgent — 优化迭代
    // ════════════════════════════════════════════════════════════════

    /** OptimizeAgent 基础系统提示词（含 Few-shot 示例） */
    private static final String OPTIMIZE_BASE_PROMPT = """
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
            """;

    /** OptimizeAgent 变体 B 附加指令 */
    private static final String OPTIMIZE_VARIANT_B = """

            【A/B 变体B 附加指令】
            - 增加竞品对标分析（假设竞品平均互动率为行业基准）
            - 每个策略调整附带"执行难度"评级（低/中/高）
            - 增加风险提示（策略调整可能带来的负面影响）
            """;

    // ════════════════════════════════════════════════════════════════
    //  动态 Prompt 片段 — 按账号画像组装
    // ════════════════════════════════════════════════════════════════

    /**
     * 根据账号领域生成领域专属指导片段。
     */
    private String getNicheGuidance(String niche) {
        if (niche == null || niche.isBlank()) {
            return "";
        }
        return switch (niche) {
            case "个人成长", "自我提升", "职场" -> """

                    【账号领域指导：个人成长/职场】
                    - 选题应聚焦读者痛点而非泛泛而谈（如"35岁焦虑"而非"如何提升自己"）
                    - 案例优先使用有数据支撑的真实经历
                    - 避免鸡汤化，每条建议都要可执行、可验证""";
            case "母婴", "育儿", "亲子" -> """

                    【账号领域指导：母婴育儿】
                    - 选题需兼顾科学性和情感共鸣
                    - 引用权威指南（如WHO、AAP）增强可信度
                    - 避免制造焦虑，语调温暖专业""";
            case "科技", "数码", "AI" -> """

                    【账号领域指导：科技/数码】
                    - 选题紧跟技术趋势，时效性强
                    - 用类比解释技术概念，降低理解门槛
                    - 提供实操步骤而非纯理论""";
            case "美食", "生活", "家居" -> """

                    【账号领域指导：美食/生活】
                    - 选题贴近日常生活场景
                    - 强调可复制性和低成本
                    - 视觉描述要具体（颜色、质感、氛围）""";
            case "财经", "投资", "商业" -> """

                    【账号领域指导：财经/商业】
                    - 数据必须准确，避免误导性表述
                    - 用故事化方式讲商业逻辑
                    - 增加风险提示和免责声明""";
            default -> "";
        };
    }

    /**
     * 根据账号调性生成调性专属指导片段。
     */
    private String getToneGuidance(String tone) {
        if (tone == null || tone.isBlank()) {
            return "";
        }
        if (tone.contains("轻松") || tone.contains("幽默") || tone.contains("搞笑")) {
            return """

                    【调性指导：轻松/幽默】
                    - 适当使用网络用语和梗，但不过度
                    - 段子和干货交替，避免疲劳
                    - 标题可以用悬念或反差制造点击欲""";
        }
        if (tone.contains("专业") || tone.contains("严谨") || tone.contains("权威")) {
            return """

                    【调性指导：专业/严谨】
                    - 观点必须有数据或权威来源支撑
                    - 用词精准，避免模糊表述（如"很多"→"68%"）
                    - 结构清晰，善用小标题和列表""";
        }
        if (tone.contains("感性") || tone.contains("温暖") || tone.contains("治愈")) {
            return """

                    【调性指导：感性/温暖】
                    - 多用场景描写和细节刻画
                    - 第一人称叙事拉近距离
                    - 结尾要有情感升华，而非冷冰冰总结""";
        }
        return "";
    }

    /**
     * 根据目标平台生成平台专属指导片段。
     */
    @SuppressWarnings("unchecked")
    private String getPlatformGuidance(Object platformsObj) {
        if (platformsObj == null) {
            return "";
        }
        String platformsStr = platformsObj.toString();
        StringBuilder sb = new StringBuilder();
        if (platformsStr.contains("小红书")) {
            sb.append("""
                    - 小红书：emoji 丰富、短段落、口语化、图文并茂、话题标签 #""");
        }
        if (platformsStr.contains("公众号")) {
            sb.append(sb.isEmpty() ? "\n" : "").append("""
                    - 公众号：段落短、重点加粗、文末引导关注和在看""");
        }
        if (platformsStr.contains("头条")) {
            sb.append(sb.isEmpty() ? "\n" : "").append("""
                    - 头条：标题党适度、段落适中、引导评论互动""");
        }
        if (sb.length() > 0) {
            return "\n\n【平台适配指导】\n" + sb;
        }
        return "";
    }

    // ════════════════════════════════════════════════════════════════
    //  组装方法 — 供各 AgentConfig 调用
    // ════════════════════════════════════════════════════════════════

    /**
     * 组装 TopicAgent 动态系统提示词。
     *
     * <p>LangChain4j 的 {@code systemMessageProvider} 传入 {@code Object} 类型的变量集合
     * （运行时为 {@code Map<String, Object>}），本方法将其转换后使用。
     *
     * @param variablesObj @UserMessage 模板变量（运行时为 Map，包含 accountNiche、tone、platforms 等）
     * @return 完整的系统提示词
     */
    @SuppressWarnings("unchecked")
    public String assembleTopicSystemMessage(Object variablesObj) {
        Map<String, Object> variables = toMap(variablesObj);
        String niche = getStr(variables, "accountNiche");
        String tone = getStr(variables, "tone");
        Object platforms = variables.get("platforms");
        String variant = resolveVariant("topic", variables);

        StringBuilder sb = new StringBuilder(TOPIC_BASE_PROMPT);
        sb.append(getNicheGuidance(niche));
        sb.append(getToneGuidance(tone));
        sb.append(getPlatformGuidance(platforms));
        if ("B".equals(variant)) {
            sb.append(TOPIC_VARIANT_B);
        }
        log.debug("[PromptFragment] Topic system message assembled, length={}", sb.length());
        return sb.toString();
    }

    /**
     * 组装 ContentAgent 动态系统提示词。
     *
     * @param variablesObj @UserMessage 模板变量（运行时为 Map，包含 accountNiche、tone 等）
     * @return 完整的系统提示词
     */
    @SuppressWarnings("unchecked")
    public String assembleContentSystemMessage(Object variablesObj) {
        Map<String, Object> variables = toMap(variablesObj);
        String niche = getStr(variables, "accountNiche");
        String tone = getStr(variables, "tone");
        String variant = resolveVariant("content", variables);

        StringBuilder sb = new StringBuilder(CONTENT_BASE_PROMPT);
        sb.append(getNicheGuidance(niche));
        sb.append(getToneGuidance(tone));
        Object platformGuidance = variables.get("platformGuidance");
        if (platformGuidance != null && !platformGuidance.toString().isBlank()) {
            sb.append("\n\n【平台适配要求（必须严格遵守，优先级高于通用规则）】\n")
              .append(platformGuidance);
        }
        if ("B".equals(variant)) {
            sb.append(CONTENT_VARIANT_B);
        }
        log.debug("[PromptFragment] Content system message assembled, length={}", sb.length());
        return sb.toString();
    }

    /**
     * 组装 AnalysisAgent 动态系统提示词。
     *
     * @param variablesObj @UserMessage 模板变量（运行时为 Map，包含 accountNiche 等）
     * @return 完整的系统提示词
     */
    @SuppressWarnings("unchecked")
    public String assembleAnalysisSystemMessage(Object variablesObj) {
        Map<String, Object> variables = toMap(variablesObj);
        String niche = getStr(variables, "accountNiche");
        String variant = resolveVariant("analysis", variables);

        StringBuilder sb = new StringBuilder(ANALYSIS_BASE_PROMPT);
        sb.append(getNicheGuidance(niche));
        if ("B".equals(variant)) {
            sb.append(ANALYSIS_VARIANT_B);
        }
        log.debug("[PromptFragment] Analysis system message assembled, length={}", sb.length());
        return sb.toString();
    }

    /**
     * 组装 ImageAgent 动态系统提示词。
     *
     * @param variablesObj @UserMessage 模板变量（运行时为 Map，包含 articleTone、targetPlatforms 等）
     * @return 完整的系统提示词
     */
    @SuppressWarnings("unchecked")
    public String assembleImageSystemMessage(Object variablesObj) {
        Map<String, Object> variables = toMap(variablesObj);
        String tone = getStr(variables, "articleTone");
        Object platforms = variables.get("targetPlatforms");
        String variant = resolveVariant("image", variables);

        StringBuilder sb = new StringBuilder(IMAGE_BASE_PROMPT);
        sb.append(getToneGuidance(tone));
        sb.append(getPlatformGuidance(platforms));
        if ("B".equals(variant)) {
            sb.append(IMAGE_VARIANT_B);
        }
        log.debug("[PromptFragment] Image system message assembled, length={}", sb.length());
        return sb.toString();
    }

    /**
     * 组装 PublishAgent 动态系统提示词。
     *
     * @param variablesObj @UserMessage 模板变量（运行时为 Map，包含 tone、targetPlatforms 等）
     * @return 完整的系统提示词
     */
    @SuppressWarnings("unchecked")
    public String assemblePublishSystemMessage(Object variablesObj) {
        Map<String, Object> variables = toMap(variablesObj);
        String tone = getStr(variables, "tone");
        Object platforms = variables.get("targetPlatforms");
        String variant = resolveVariant("publish", variables);

        StringBuilder sb = new StringBuilder(PUBLISH_BASE_PROMPT);
        sb.append(getToneGuidance(tone));
        sb.append(getPlatformGuidance(platforms));
        if ("B".equals(variant)) {
            sb.append(PUBLISH_VARIANT_B);
        }
        log.debug("[PromptFragment] Publish system message assembled, length={}", sb.length());
        return sb.toString();
    }

    /**
     * 组装 OptimizeAgent 动态系统提示词。
     *
     * @param variablesObj @UserMessage 模板变量（运行时为 Map，包含 accountNiche 等）
     * @return 完整的系统提示词
     */
    @SuppressWarnings("unchecked")
    public String assembleOptimizeSystemMessage(Object variablesObj) {
        Map<String, Object> variables = toMap(variablesObj);
        String niche = getStr(variables, "accountNiche");
        String variant = resolveVariant("optimize", variables);

        StringBuilder sb = new StringBuilder(OPTIMIZE_BASE_PROMPT);
        sb.append(getNicheGuidance(niche));
        if ("B".equals(variant)) {
            sb.append(OPTIMIZE_VARIANT_B);
        }
        log.debug("[PromptFragment] Optimize system message assembled, length={}", sb.length());
        return sb.toString();
    }

    // ════════════════════════════════════════════════════════════════
    //  辅助方法
    // ════════════════════════════════════════════════════════════════

    /**
     * 将 LangChain4j 传入的 Object 类型变量集合安全转换为 Map。
     *
     * <p>LangChain4j 的 {@code systemMessageProvider} 接口签名为
     * {@code Function<Object, String>}，运行时实际传入的是 {@code Map<String, Object>}。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> toMap(Object variablesObj) {
        if (variablesObj instanceof Map) {
            return (Map<String, Object>) variablesObj;
        }
        return Map.of();
    }

    /**
     * 从变量 Map 中安全提取字符串值。
     */
    private String getStr(Map<String, Object> variables, String key) {
        Object value = variables.get(key);
        return value == null ? "" : value.toString();
    }

    /**
     * 解析当前请求应使用的 A/B 测试变体。
     *
     * <p>使用变量 Map 中的值拼接作为哈希种子，确保同一账号/选题获得一致的变体。
     */
    private String resolveVariant(String agentKey, Map<String, Object> variables) {
        if (!versionService.isDynamicPromptEnabled()) {
            return "A";
        }
        // 使用变量值拼接作为 memoryId 替代（@MemoryId 不在 variables 中）
        String seed = agentKey + ":" + variables.values().stream()
                .map(String::valueOf)
                .reduce("", (a, b) -> a + "|" + b);
        return versionService.getVariant(agentKey, seed);
    }
}
