package com.contentops.common.agent;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 多 Agent 协作框架配置属性。
 *
 * <p>通过 {@code contentops.multi-agent.*} 在 application.yml 中绑定，统一控制
 * 多 Agent 协作编排器、ReAct 执行器与 Plan-and-Execute 执行器的行为参数。
 *
 * <h3>配置示例（application.yml）</h3>
 * <pre>{@code
 * contentops:
 *   multi-agent:
 *     enabled: true
 *     task-timeout-seconds: 120
 *     max-retries: 2
 *     react:
 *       max-iterations: 10
 *       intermediate-cache-size: 100
 *     plan-and-execute:
 *       max-replan-count: 2
 *       parallel-subtasks: true
 *     thread-pool:
 *       core-pool-size: 4
 *       max-pool-size: 16
 *       queue-capacity: 100
 *       thread-name-prefix: multi-agent-
 * }</pre>
 *
 * @see MultiAgentOrchestrator
 * @see ReActAgentExecutor
 * @see PlanAndExecuteAgent
 */
@Data
@Component
@ConfigurationProperties(prefix = "contentops.multi-agent")
public class MultiAgentProperties {

    /** 是否启用多 Agent 协作框架。 */
    private boolean enabled = true;

    /** 单个任务执行的超时时间（秒），超时后降级返回部分结果。 */
    private long taskTimeoutSeconds = 120L;

    /** 单个任务失败后的最大重试次数（不含首次执行）。 */
    private int maxRetries = 2;

    /** 重试之间的退避等待时间（毫秒）。 */
    private long retryBackoffMs = 500L;

    /** ReAct 执行器配置。 */
    private ReactConfig react = new ReactConfig();

    /** Plan-and-Execute 执行器配置。 */
    private PlanAndExecuteConfig planAndExecute = new PlanAndExecuteConfig();

    /** 通信协议配置。 */
    private CommunicationConfig communication = new CommunicationConfig();

    /** 自定义线程池配置。 */
    private ThreadPoolConfig threadPool = new ThreadPoolConfig();

    /** 角色级覆盖配置：key 为角色名，value 为该角色的温度/模型覆盖项。 */
    private Map<String, RoleOverride> roles = new HashMap<>();

    /**
     * ReAct（Reasoning + Acting）执行器配置。
     */
    @Data
    public static class ReactConfig {
        /** ReAct 循环最大迭代次数（Thought→Action→Observation 最多重复次数）。 */
        private int maxIterations = 10;

        /** 中间结果缓存最大容量（按工具调用缓存 Observation）。 */
        private int intermediateCacheSize = 100;

        /** 单次 LLM 调用超时（秒）。 */
        private long llmTimeoutSeconds = 60L;
    }

    /**
     * Plan-and-Execute 执行器配置。
     */
    @Data
    public static class PlanAndExecuteConfig {
        /** 最大重新规划次数（执行中发现计划不合理时允许重新规划的次数）。 */
        private int maxReplanCount = 2;

        /** 是否允许子任务并行执行（依赖满足后并行调度）。 */
        private boolean parallelSubtasks = true;

        /** 子任务默认超时时间（秒）。 */
        private long subtaskTimeoutSeconds = 90L;
    }

    /**
     * Agent 间通信协议配置。
     */
    @Data
    public static class CommunicationConfig {
        /** 每个 Agent 邮箱（BlockingQueue）的容量上限。 */
        private int queueCapacity = 1000;

        /** 接收消息时的默认轮询超时（毫秒）。 */
        private long defaultPollTimeoutMs = 1000L;
    }

    /**
     * 自定义线程池配置。
     */
    @Data
    public static class ThreadPoolConfig {
        /** 核心线程数。 */
        private int corePoolSize = 4;

        /** 最大线程数。 */
        private int maxPoolSize = 16;

        /** 工作队列容量。 */
        private int queueCapacity = 100;

        /** 线程空闲存活时间（秒）。 */
        private long keepAliveSeconds = 60L;

        /** 线程名前缀。 */
        private String threadNamePrefix = "multi-agent-";
    }

    /**
     * 单个角色的配置覆盖项。
     */
    @Data
    public static class RoleOverride {
        /** 覆盖温度（为 null 时保留角色默认温度）。 */
        private Double temperature;

        /** 覆盖模型名称（为 null 时保留角色默认模型）。 */
        private String modelName;
    }
}
