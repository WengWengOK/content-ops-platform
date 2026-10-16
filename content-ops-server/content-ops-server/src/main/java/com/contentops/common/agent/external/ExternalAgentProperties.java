package com.contentops.common.agent.external;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 外部 Agent 服务配置（{@code contentops.external-agents.*}）。
 *
 * <p>当前接入的是 **CrewAI 研究 crew**（独立 Python 服务，见 {@code content-ops-crewai/}）：
 * 平台侧发起调用、平台侧回写知识库，外部 crew 只通过 HTTP 使用平台工具 —— 租户隔离、RBAC、
 * 限流、审计、成本统计仍由平台统管。
 */
@Data
@Component
@ConfigurationProperties(prefix = "contentops.external-agents")
public class ExternalAgentProperties {

    /** 外部 Agent 总开关 */
    private boolean enabled = true;

    private CrewAi crewai = new CrewAi();

    /** CrewAI 研究 crew 配置 */
    @Data
    public static class CrewAi {

        /** 是否启用（默认关：需要先起 crew 服务再打开） */
        private boolean enabled = false;

        /** crew 服务地址 */
        private String baseUrl = "http://localhost:8000";

        /** 单次研究超时（毫秒）——多角色协作耗时较长，默认 4 分钟 */
        private int timeoutMs = 240_000;

        /** 默认账号定位/领域（请求未指定时使用） */
        private String defaultNiche = "";

        /** 产出是否回写知识库（进 RAG 检索与评测面） */
        private boolean writeBackToKb = true;
    }
}
