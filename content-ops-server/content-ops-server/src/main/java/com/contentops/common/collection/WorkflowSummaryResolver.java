package com.contentops.common.collection;

import com.contentops.common.dto.TaskContext;
import com.contentops.common.util.WorkflowStateManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 从工作流持久化状态解析合集所需的作品摘要（标题/状态/平台等）。
 *
 * <p>独立组件，避免 WorkCollectionService 与 WorkflowService 互相依赖形成环。
 */
@Component
@RequiredArgsConstructor
public class WorkflowSummaryResolver {

    private final WorkflowStateManager stateManager;

    public Optional<TaskContext> load(String workflowId) {
        return stateManager.loadWorkflowState(workflowId);
    }

    public CollectionWork summarize(TaskContext context) {
        Map<String, Object> artifacts = context.getAccumulatedArtifacts();
        if (artifacts == null) {
            artifacts = Map.of();
        }
        return CollectionWork.builder()
                .workflowId(context.getWorkflowId())
                .title(resolveTitle(context, artifacts))
                .status(context.getStatus())
                .platforms(resolvePlatforms(context))
                .publishMode(resolvePublishMode(context))
                .createdAt(context.getCreatedAt())
                .build();
    }

    private String resolveTitle(TaskContext context, Map<String, Object> artifacts) {
        Map<String, Object> draft = asMap(artifacts.get("content-creation:draft"));
        if (draft != null) {
            Object title = draft.get("title");
            if (title != null && !String.valueOf(title).isBlank()) {
                return String.valueOf(title);
            }
        }
        Map<String, Object> content = asMap(artifacts.get("content-creation"));
        if (content != null) {
            Object title = content.get("title");
            if (title != null && !String.valueOf(title).isBlank()) {
                return String.valueOf(title);
            }
        }
        Map<String, Object> topic = asMap(artifacts.get("topic-planning"));
        if (topic != null) {
            Object t = topic.get("topic");
            if (t != null && !String.valueOf(t).isBlank()) {
                return String.valueOf(t);
            }
        }
        if (context.getInputs() != null) {
            Object inputTitle = context.getInputs().get("articleTitle");
            if (inputTitle != null && !String.valueOf(inputTitle).isBlank()) {
                return String.valueOf(inputTitle);
            }
            Object topicInput = context.getInputs().get("topic");
            if (topicInput != null && !String.valueOf(topicInput).isBlank()) {
                return String.valueOf(topicInput);
            }
        }
        return "未命名作品";
    }

    @SuppressWarnings("unchecked")
    private List<String> resolvePlatforms(TaskContext context) {
        if (context.getInputs() != null && context.getInputs().get("platformNames") instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        if (context.getInputs() != null && context.getInputs().get("platform") != null) {
            return List.of(String.valueOf(context.getInputs().get("platform")));
        }
        return List.of();
    }

    private String resolvePublishMode(TaskContext context) {
        if (context.getInputs() != null && context.getInputs().get("publishMode") != null) {
            return String.valueOf(context.getInputs().get("publishMode"));
        }
        return "text-cover";
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return null;
    }
}
