package com.contentops.common.enums;

/**
 * Represents the 6 stages of the content operations pipeline.
 * Each stage corresponds to a specialized AI Agent microservice.
 */
public enum AgentStage {

    TOPIC_PLANNING(1, "topic-planning", "选题策划", "联网调研与热点追踪，输出选题方案"),
    CONTENT_CREATION(2, "content-creation", "内容创作", "框架搭建与初稿生成，输出结构化文章"),
    IMAGE_DESIGN(3, "image-design", "配图设计", "AI生图与多尺寸适配，输出平台匹配配图"),
    PUBLISHING(4, "publishing", "排版发布", "文档排版与多平台分发，输出可发布成品"),
    DATA_ANALYSIS(5, "data-analysis", "数据分析", "可视化图表与趋势洞察，输出分析报告"),
    OPTIMIZATION(6, "optimization", "优化迭代", "策略调整与方向校准，输出优化建议");

    private final int order;
    private final String code;
    private final String nameCn;
    private final String description;

    AgentStage(int order, String code, String nameCn, String description) {
        this.order = order;
        this.code = code;
        this.nameCn = nameCn;
        this.description = description;
    }

    public int getOrder() { return order; }
    public String getCode() { return code; }
    public String getNameCn() { return nameCn; }
    public String getDescription() { return description; }

    /**
     * Returns the next stage in the pipeline, or OPTIMIZATION if already at the end.
     */
    public AgentStage next() {
        AgentStage[] stages = values();
        int idx = this.ordinal();
        return idx < stages.length - 1 ? stages[idx + 1] : stages[0]; // Loop back to TOPIC_PLANNING
    }

    /**
     * Returns the previous stage in the pipeline.
     */
    public AgentStage previous() {
        AgentStage[] stages = values();
        int idx = this.ordinal();
        return idx > 0 ? stages[idx - 1] : stages[stages.length - 1];
    }

    public static AgentStage fromCode(String code) {
        for (AgentStage stage : values()) {
            if (stage.code.equals(code)) return stage;
        }
        throw new IllegalArgumentException("Unknown agent stage code: " + code);
    }
}
