package com.contentops.common.profile.style;

import com.contentops.common.profile.style.StyleProfile.ContentStyle;
import com.contentops.common.profile.style.StyleProfile.LanguageStyle;
import com.contentops.common.profile.style.StyleProfile.StructureStyle;
import com.contentops.common.profile.style.StyleProfile.StyleDimension;
import com.contentops.common.profile.style.StyleProfile.VisualStyle;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * 风格注入器（P0 改造）。
 *
 * <p>在 Agent 调用前，将账号的风格画像上下文注入到 Prompt 中，使生成内容与账号历史风格保持一致。
 * 这是「从静态 tone 字段升级为基于作品分析的风格画像」的关键消费端：各 Agent 不再依赖
 * {@code accountProfile.tone} 单一字段，而是接收四维风格特征的精细化指导。
 *
 * <h3>注入场景</h3>
 * <ul>
 *   <li>{@link #enrichTopicPlanningPrompt} —— 选题时注入风格画像，使选题角度契合账号调性</li>
 *   <li>{@link #enrichContentCreationPrompt} —— 创作时注入风格画像，保持语言/结构/内容一致性</li>
 *   <li>{@link #enrichImageDesignPrompt} —— 配图时注入视觉风格偏好（色调/配图风格/排版密度）</li>
 * </ul>
 *
 * <h3>降级策略</h3>
 * <p>当账号尚无风格画像时，各注入方法原样返回原始 Prompt，不影响 Agent 正常执行。
 *
 * @see StyleProfileManager
 * @see StyleDimension
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StyleEnricher {

    private final StyleProfileManager profileManager;

    // ════════════════════════════════════════════════════════════════
    //  Prompt 注入
    // ════════════════════════════════════════════════════════════════

    /**
     * 选题时注入风格画像，使选题方向与账号调性契合。
     *
     * @param accountId 账号 ID
     * @param prompt    原始选题 Prompt
     * @return 注入风格上下文后的 Prompt；无画像时原样返回
     */
    public String enrichTopicPlanningPrompt(String accountId, String prompt) {
        return enrich(accountId, prompt, "选题策划", """
                请在选题时参考该账号的风格画像：
                - 选题角度应契合账号既有的情感倾向与观点鲜明度
                - 标题套路保持与历史高表现作品一致
                """);
    }

    /**
     * 创作时注入风格画像，保持语言、结构、内容特征的一致性。
     *
     * @param accountId 账号 ID
     * @param prompt    原始创作 Prompt
     * @return 注入风格上下文后的 Prompt；无画像时原样返回
     */
    public String enrichContentCreationPrompt(String accountId, String prompt) {
        return enrich(accountId, prompt, "内容创作", """
                请严格保持以下风格特征的一致性：
                - 句式分布、用词复杂度、口语化程度与历史作品一致
                - 沿用账号惯用的开头模式、段落结构与结尾模式
                - 观点鲜明度、数据引用习惯、情感倾向与历史保持一致
                """);
    }

    /**
     * 配图时注入视觉风格偏好（色调/配图风格/排版密度）。
     *
     * @param accountId 账号 ID
     * @param prompt    原始配图 Prompt
     * @return 注入视觉风格上下文后的 Prompt；无画像时原样返回
     */
    public String enrichImageDesignPrompt(String accountId, String prompt) {
        Optional<StyleProfile> profileOpt = profileManager.getProfile(accountId);
        if (profileOpt.isEmpty()) {
            log.debug("[StyleEnricher] 账号无风格画像，配图 Prompt 不注入: accountId={}", accountId);
            return prompt;
        }
        StyleProfile profile = profileOpt.get();
        VisualStyle vs = profile.visualStyle();
        String visualBlock = """
                ## 视觉风格偏好（基于历史作品分析）
                - 封面色调倾向：%s
                - 配图风格：%s
                - 排版密度：%s
                - 标题排版偏好：%s
                请在配图设计时遵循以上视觉风格偏好。
                """.formatted(
                vs.coverTone().label(),
                vs.illustrationStyle().label(),
                vs.layoutDensity().label(),
                vs.titleLayoutPreference()
        );
        return appendBlock(prompt, visualBlock);
    }

    /**
     * 生成人类可读的风格指南。
     *
     * <p>使用模式匹配 switch 对 {@link StyleDimension} 密封接口的四个实现分别渲染，
     * 编译器可进行穷尽性检查。
     *
     * @param styleProfile 风格画像
     * @return 人类可读的风格指南文本
     */
    public String generateStyleGuide(StyleProfile styleProfile) {
        if (styleProfile == null) {
            return "（暂无风格画像）";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("## 你的风格画像（基于历史高表现作品分析）\n");
        sb.append(renderDimension(styleProfile.languageStyle())).append('\n');
        sb.append(renderDimension(styleProfile.structureStyle())).append('\n');
        sb.append(renderDimension(styleProfile.contentStyle())).append('\n');
        sb.append("请保持以上风格特征的一致性。");
        return sb.toString();
    }

    // ════════════════════════════════════════════════════════════════
    //  内部方法
    // ════════════════════════════════════════════════════════════════

    /**
     * 通用注入逻辑：获取画像 → 生成风格指南块 → 追加到原始 Prompt。
     *
     * @param accountId 账号 ID
     * @param prompt    原始 Prompt
     * @param scenario  场景名称（日志用）
     * @param guidance  场景专属指导文本
     * @return 注入后的 Prompt
     */
    private String enrich(String accountId, String prompt, String scenario, String guidance) {
        Optional<StyleProfile> profileOpt = profileManager.getProfile(accountId);
        if (profileOpt.isEmpty()) {
            log.debug("[StyleEnricher] 账号无风格画像，{} Prompt 不注入: accountId={}", scenario, accountId);
            return prompt;
        }
        StyleProfile profile = profileOpt.get();
        String guide = generateStyleGuide(profile);
        String block = guide + "\n\n" + guidance;
        log.debug("[StyleEnricher] 已为 {} 注入风格画像: accountId={}", scenario, accountId);
        return appendBlock(prompt, block);
    }

    /**
     * 使用模式匹配 switch 渲染单个风格维度（密封接口穷尽匹配）。
     *
     * @param dimension 风格维度
     * @return 该维度的可读描述
     */
    private String renderDimension(StyleDimension dimension) {
        return switch (dimension) {
            case LanguageStyle ls -> """
                    - 语言风格：句式偏好{长句%d%%/短句%d%%/问句%d%%}，用词%s，口语化程度%s，平均句长%d字"""
                    .formatted(
                            pct(ls.longSentenceRatio()),
                            pct(ls.shortSentenceRatio()),
                            pct(ls.questionSentenceRatio()),
                            describeTerminology(ls.terminologyDensity()),
                            describeLevel(ls.colloquialism()),
                            (int) ls.averageSentenceLength()
                    );
            case StructureStyle ss -> """
                    - 结构偏好：%s结构，%s开头，%s结尾，%s标题"""
                    .formatted(
                            ss.paragraphStructure().label(),
                            ss.openingMode().label(),
                            ss.endingMode().label(),
                            ss.titleStyle().label()
                    );
            case ContentStyle cs -> """
                    - 内容特征：观点鲜明度%s，数据引用%s，案例%s，个人经历占比%d%%，情感倾向%s，幽默感%s"""
                    .formatted(
                            describeLevel(cs.opinionClarity()),
                            describeFrequency(cs.dataCitationFrequency()),
                            describeFrequency(cs.caseUsageFrequency()),
                            pct(cs.personalExperienceRatio()),
                            cs.emotionalTendency().label(),
                            describeLevel(cs.humorLevel())
                    );
            case VisualStyle vs -> """
                    - 视觉风格：封面%s，%s配图，%s排版，标题%s"""
                    .formatted(
                            vs.coverTone().label(),
                            vs.illustrationStyle().label(),
                            vs.layoutDensity().label(),
                            vs.titleLayoutPreference()
                    );
        };
    }

    /** 将风格块追加到原始 Prompt 之后。 */
    private String appendBlock(String prompt, String block) {
        if (prompt == null || prompt.isBlank()) {
            return block;
        }
        return prompt + "\n\n" + block;
    }

    /** 比率转百分比整数。 */
    private int pct(double ratio) {
        return (int) Math.round(ratio * 100);
    }

    /** 0~1 值映射为「低/中/高」三档描述。 */
    private String describeLevel(double v) {
        if (v >= 0.66) {
            return "高";
        }
        if (v >= 0.33) {
            return "中";
        }
        return "低";
    }

    /** 0~1 值映射为「罕见/偶尔/频繁」三档描述。 */
    private String describeFrequency(double v) {
        if (v >= 0.66) {
            return "频繁";
        }
        if (v >= 0.33) {
            return "偶尔";
        }
        return "罕见";
    }

    /** 术语密度映射为「通俗/适中/专业」三档描述。 */
    private String describeTerminology(double v) {
        if (v >= 0.66) {
            return "专业";
        }
        if (v >= 0.33) {
            return "适中";
        }
        return "通俗";
    }
}
