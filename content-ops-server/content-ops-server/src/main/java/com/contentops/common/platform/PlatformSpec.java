package com.contentops.common.platform;

import lombok.Getter;

import java.util.List;

/**
 * 平台内容规格 —— 每个平台一套产出约束（标题、字数、排版、标签、配图）。
 *
 * <p>这些约束最终通过 {@link #buildPromptFragment()} 注入内容 Agent 提示词，
 * 是实现"小红书风格 vs 公众号风格差异"的核心数据源。
 * 未来短视频能力上线时，为对应平台追加 {@code videoSpec} 即可，不影响图文链路。
 */
@Getter
public class PlatformSpec {

    private final ContentPlatform platform;
    private final String titleRule;
    private final String bodyRule;
    private final String structureRule;
    private final String tagRule;
    private final String imageRule;
    private final String toneRule;
    private final String forbiddenRule;

    public PlatformSpec(ContentPlatform platform,
                        String titleRule,
                        String bodyRule,
                        String structureRule,
                        String tagRule,
                        String imageRule,
                        String toneRule,
                        String forbiddenRule) {
        this.platform = platform;
        this.titleRule = titleRule;
        this.bodyRule = bodyRule;
        this.structureRule = structureRule;
        this.tagRule = tagRule;
        this.imageRule = imageRule;
        this.toneRule = toneRule;
        this.forbiddenRule = forbiddenRule;
    }

    /**
     * 组装为可直接拼入 Prompt 的中文适配指令块。
     */
    public String buildPromptFragment() {
        return """

                【%s 平台适配要求 —— 必须严格遵守，输出风格优先级高于通用规则】
                - 标题规则：%s
                - 正文规则：%s
                - 结构规则：%s
                - 标签规则：%s
                - 配图规则：%s
                - 语气规则：%s
                - 禁止事项：%s
                """.formatted(platform.getDisplayName(), titleRule, bodyRule, structureRule,
                tagRule, imageRule, toneRule, forbiddenRule);
    }

    /**
     * 供列表类场景使用的平台规则摘要。
     */
    public List<String> ruleHighlights() {
        return List.of(titleRule, bodyRule, tagRule, imageRule);
    }
}
