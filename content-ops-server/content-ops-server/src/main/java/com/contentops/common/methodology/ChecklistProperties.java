package com.contentops.common.methodology;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;

/**
 * 人工行动清单配置（v2.2.0 方法论：「辅助而非替代」）。
 *
 * <p>绑定到 application.yml 中的 {@code contentops.methodology.checklist}：
 * <pre>
 * contentops:
 *   methodology:
 *     checklist:
 *       enabled: true
 *       min-items: 2
 *       stage-items:
 *         TOPIC_PLANNING:
 *           - 人工确认选题方向
 *           - 调整目标受众
 *         CONTENT_CREATION:
 *           - 人工润色
 *           - 事实核查
 * </pre>
 *
 * <p>该配置驱动 {@link HumanActionChecklistGenerator}：为每个 Agent 阶段的输出
 * 生成「需要人工行动」的清单，明确 AI 输出仅为辅助，关键决策与合规校验必须由人完成。
 * {@link #stageItems} 允许运维方在不改代码的前提下覆盖默认清单。
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "contentops.methodology.checklist")
public class ChecklistProperties {

    /** 是否启用人工行动清单生成（关闭时 generateChecklist 返回空列表） */
    private boolean enabled = true;

    /** 每个阶段清单的最少条数，低于该值会在日志中告警（不阻断） */
    private int minItems = 2;

    /**
     * 各阶段自定义检查项覆盖配置。
     * <p>key 为 {@link com.contentops.common.enums.AgentStage} 的名称（如 {@code TOPIC_PLANNING}），
     * value 为该阶段的检查项列表。未配置的阶段使用 {@link HumanActionChecklistGenerator} 内置默认清单。
     */
    private Map<String, List<String>> stageItems;
}
