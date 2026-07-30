package com.contentops.common.enums;

import java.util.List;

/**
 * 子阶段枚举 —— 渐进式生成（两阶段 Agent）的核心概念。
 *
 * <p>一个 {@link AgentStage} 可以包含多个子阶段，实现「先搭框架，别一步到位」的技巧：
 * <ul>
 *   <li>{@link #CONTENT_OUTLINE} → {@link #CONTENT_DRAFT}：内容创作先出大纲，确认后再写初稿</li>
 *   <li>{@link #IMAGE_STYLES} → {@link #IMAGE_GENERATE}：配图设计先出风格方向，确认后再生图</li>
 * </ul>
 *
 * <p>其他 AgentStage（选题策划、排版发布、数据分析、优化迭代）暂时只有单步，
 * 不使用子阶段，编排器直接走原有的 {@code /execute} 路径。
 */
public enum SubStage {

    // ──────────── 内容创作子阶段 ────────────
    CONTENT_OUTLINE("content-creation", 1, "outline", "大纲生成",
            "生成文章框架大纲，供人工确认后再写初稿"),
    CONTENT_DRAFT("content-creation", 2, "draft", "初稿生成",
            "基于确认的大纲生成完整 Markdown 初稿、标题变体、标签和摘要"),

    // ──────────── 配图设计子阶段 ────────────
    IMAGE_STYLES("image-design", 1, "styles", "风格方向",
            "生成 3 个配图风格方向供人工选择，包含视觉关键词和色调建议"),
    IMAGE_GENERATE("image-design", 2, "generate", "批量生图",
            "基于确认的风格方向批量生成文章配图和平台封面");

    private final String parentStageCode;  // 所属 AgentStage 的 code
    private final int order;               // 子阶段在父阶段内的顺序
    private final String code;             // 子阶段唯一标识
    private final String nameCn;           // 中文名称
    private final String description;       // 描述

    SubStage(String parentStageCode, int order, String code, String nameCn, String description) {
        this.parentStageCode = parentStageCode;
        this.order = order;
        this.code = code;
        this.nameCn = nameCn;
        this.description = description;
    }

    public String getParentStageCode() { return parentStageCode; }
    public int getOrder() { return order; }
    public String getCode() { return code; }
    public String getNameCn() { return nameCn; }
    public String getDescription() { return description; }

    /**
     * 获取指定 AgentStage 下的所有子阶段（按顺序排列）。
     * 如果该 Stage 没有子阶段，返回空列表。
     */
    public static List<SubStage> ofStage(AgentStage stage) {
        return java.util.Arrays.stream(values())
                .filter(s -> s.parentStageCode.equals(stage.getCode()))
                .sorted(java.util.Comparator.comparingInt(SubStage::getOrder))
                .toList();
    }

    /**
     * 判断指定 AgentStage 是否有子阶段。
     */
    public static boolean hasSubStages(AgentStage stage) {
        return java.util.Arrays.stream(values())
                .anyMatch(s -> s.parentStageCode.equals(stage.getCode()));
    }

    /**
     * 获取子阶段的完整标识，格式为 {parentCode}:{subCode}，
     * 例如 "content-creation:outline"。
     */
    public String fullCode() {
        return parentStageCode + ":" + code;
    }

    /**
     * 获取下一个子阶段（同一父阶段内），如果没有返回 null。
     */
    public SubStage next() {
        List<SubStage> siblings = ofStage(AgentStage.fromCode(parentStageCode));
        int idx = siblings.indexOf(this);
        return idx < siblings.size() - 1 ? siblings.get(idx + 1) : null;
    }

    /**
     * 获取第一个子阶段（同一父阶段内）。
     */
    public static SubStage firstOf(AgentStage stage) {
        List<SubStage> subs = ofStage(stage);
        return subs.isEmpty() ? null : subs.get(0);
    }

    /**
     * 根据 fullCode（如 "content-creation:outline"）查找子阶段。
     */
    public static SubStage fromFullCode(String fullCode) {
        for (SubStage s : values()) {
            if (s.fullCode().equals(fullCode)) return s;
        }
        throw new IllegalArgumentException("Unknown sub-stage full code: " + fullCode);
    }

    /**
     * 根据 code 查找子阶段（code 在全局唯一）。
     */
    public static SubStage fromCode(String code) {
        for (SubStage s : values()) {
            if (s.code.equals(code)) return s;
        }
        throw new IllegalArgumentException("Unknown sub-stage code: " + code);
    }
}
