package com.contentops.common.constant;

/**
 * Platform-wide constants for the content operations agent platform.
 */
public final class AgentConstants {

    private AgentConstants() {}

    /** Kafka topic name prefix for agent tasks */
    public static final String TOPIC_PREFIX = "content-ops.agent.";

    /** Kafka topic for task lifecycle events */
    public static final String TASK_EVENT_TOPIC = "content-ops.task.events";

    /** Redis key prefix for workflow state */
    public static final String WORKFLOW_STATE_PREFIX = "contentops:workflow:";

    /** Redis key prefix for agent context */
    public static final String AGENT_CONTEXT_PREFIX = "contentops:agent:context:";

    /** Default LLM temperature for creative agents */
    public static final double TEMPERATURE_CREATIVE = 0.8;

    /** Default LLM temperature for analytical agents */
    public static final double TEMPERATURE_ANALYTICAL = 0.3;

    /** Default LLM temperature for formatting agents */
    public static final double TEMPERATURE_PRECISE = 0.1;

    /** Max retries for LLM calls */
    public static final int MAX_LLM_RETRIES = 3;

    /** Default timeout for agent tasks (seconds) */
    public static final long DEFAULT_TASK_TIMEOUT_SECONDS = 120;

    /** Agent service names for inter-service communication */
    public static final String SERVICE_TOPIC = "content-ops-agent-topic";
    public static final String SERVICE_CONTENT = "content-ops-agent-content";
    public static final String SERVICE_IMAGE = "content-ops-agent-image";
    public static final String SERVICE_PUBLISH = "content-ops-agent-publish";
    public static final String SERVICE_ANALYSIS = "content-ops-agent-analysis";
    public static final String SERVICE_OPTIMIZE = "content-ops-agent-optimize";
    public static final String SERVICE_ORCHESTRATOR = "content-ops-orchestrator";

    /** Redis key prefix for discussion sessions */
    public static final String DISCUSSION_SESSION_PREFIX = "contentops:discussion:";

    /** Memory ID format: {agentCode}:{workflowId} — ensures per-agent per-workflow isolation */
    public static final String MEMORY_ID_FORMAT = "%s:%s";

    /** Chat memory window size (number of messages retained) */
    public static final int CHAT_MEMORY_WINDOW_SIZE = 20;
}
