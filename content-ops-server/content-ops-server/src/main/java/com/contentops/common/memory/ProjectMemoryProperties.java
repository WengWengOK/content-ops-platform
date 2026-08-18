package com.contentops.common.memory;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 跨工作流项目记忆配置（长期记忆与上下文工程 P2）。
 *
 * <p>绑定到 application.yml 中的 {@code contentops.project-memory}：
 * <pre>
 * contentops:
 *   project-memory:
 *     enabled: false
 *     ttl-days: 30
 *     max-recent-summaries: 10
 *     top-performing-topics-count: 5
 *     key-prefix: "contentops:project-memory:"
 * </pre>
 *
 * <p>默认关闭（{@code enabled=false}），渐进开启。开启后：
 * <ul>
 *   <li>工作流启动时调用 {@link ProjectMemoryService#enrichContextWithMemory}
 *       把项目记忆摘要注入 TaskContext.inputs</li>
 *   <li>工作流完成时调用 {@link ProjectMemoryService#summarizeWorkflow}
 *       沉淀本次工作流摘要</li>
 * </ul>
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "contentops.project-memory")
public class ProjectMemoryProperties {

    /** 是否启用跨工作流项目记忆（默认关闭，渐进开启） */
    private boolean enabled = false;

    /** Redis TTL（天），项目记忆保留时长 */
    private int ttlDays = 30;

    /** 保留的近期工作流摘要最大条数 */
    private int maxRecentSummaries = 10;

    /** 从知识库聚合的历史高表现选题数量 */
    private int topPerformingTopicsCount = 5;

    /** Redis key 前缀 */
    private String keyPrefix = "contentops:project-memory:";
}
