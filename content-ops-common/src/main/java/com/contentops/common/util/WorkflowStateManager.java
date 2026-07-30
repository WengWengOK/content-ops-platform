package com.contentops.common.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import com.contentops.common.constant.AgentConstants;
import com.contentops.common.dto.TaskContext;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Manages workflow state in Redis.
 * Each workflow's state is stored as a JSON-serialized TaskContext.
 */
@Slf4j
public class WorkflowStateManager {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public WorkflowStateManager(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
    }

    /**
     * Save workflow state to Redis.
     */
    public void saveWorkflowState(String workflowId, TaskContext context) {
        try {
            String key = AgentConstants.WORKFLOW_STATE_PREFIX + workflowId;
            String json = objectMapper.writeValueAsString(context);
            redisTemplate.opsForValue().set(key, json, Duration.ofHours(24));
            log.debug("Saved workflow state for: {}", workflowId);
        } catch (Exception e) {
            log.error("Failed to save workflow state: {}", workflowId, e);
        }
    }

    /**
     * Load workflow state from Redis.
     */
    public Optional<TaskContext> loadWorkflowState(String workflowId) {
        try {
            String key = AgentConstants.WORKFLOW_STATE_PREFIX + workflowId;
            String json = redisTemplate.opsForValue().get(key);
            if (json == null) return Optional.empty();
            return Optional.of(objectMapper.readValue(json, TaskContext.class));
        } catch (Exception e) {
            log.error("Failed to load workflow state: {}", workflowId, e);
            return Optional.empty();
        }
    }

    /**
     * Update a specific field in the workflow state.
     */
    public void updateOutputs(String workflowId, java.util.Map<String, Object> outputs) {
        loadWorkflowState(workflowId).ifPresent(context -> {
            context.setOutputs(outputs);
            saveWorkflowState(workflowId, context);
        });
    }

    /**
     * Merge new artifacts into the accumulated artifacts.
     */
    public void mergeArtifacts(String workflowId, java.util.Map<String, Object> newArtifacts) {
        loadWorkflowState(workflowId).ifPresent(context -> {
            if (context.getAccumulatedArtifacts() == null) {
                context.setAccumulatedArtifacts(new java.util.HashMap<>());
            }
            context.getAccumulatedArtifacts().putAll(newArtifacts);
            saveWorkflowState(workflowId, context);
        });
    }

    /**
     * Delete workflow state.
     */
    public void deleteWorkflowState(String workflowId) {
        String key = AgentConstants.WORKFLOW_STATE_PREFIX + workflowId;
        redisTemplate.delete(key);
    }

    /**
     * List all workflow states from Redis.
     * Scans keys with the workflow state prefix and deserializes each into TaskContext.
     *
     * @return list of all stored TaskContext objects (may be empty if none exist)
     */
    public List<TaskContext> listAllWorkflows() {
        List<TaskContext> workflows = new ArrayList<>();
        try {
            String pattern = AgentConstants.WORKFLOW_STATE_PREFIX + "*";
            Set<String> keys = redisTemplate.keys(pattern);
            if (keys == null || keys.isEmpty()) {
                return workflows;
            }
            for (String key : keys) {
                try {
                    String json = redisTemplate.opsForValue().get(key);
                    if (json != null) {
                        TaskContext ctx = objectMapper.readValue(json, TaskContext.class);
                        workflows.add(ctx);
                    }
                } catch (Exception e) {
                    log.warn("Failed to deserialize workflow state for key: {}", key, e);
                }
            }
        } catch (Exception e) {
            log.error("Failed to list all workflow states", e);
        }
        return workflows;
    }
}
