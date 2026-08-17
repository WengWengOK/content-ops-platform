package com.contentops.common.collection;

import com.contentops.common.dto.AgentResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 作品合集接口：用户自建合集，按类型区分，创建/生成后可把作品归入合集。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/collections")
@RequiredArgsConstructor
@Tag(name = "作品合集")
public class WorkCollectionController {

    private final WorkCollectionService collectionService;

    @PostMapping
    @Operation(summary = "创建作品合集（按类型区分）")
    public AgentResponse<WorkCollection> create(@RequestBody CreateCollectionRequest request) {
        WorkCollection collection = collectionService.create(
                request.getName(), request.getType(), request.getDescription());
        return AgentResponse.success("collection", collection);
    }

    @GetMapping
    @Operation(summary = "列出我的作品合集")
    public AgentResponse<List<WorkCollection>> list() {
        return AgentResponse.success("collection", collectionService.list());
    }

    @GetMapping("/{collectionId}")
    @Operation(summary = "合集详情（含作品列表）")
    public AgentResponse<WorkCollection> get(@PathVariable String collectionId) {
        return AgentResponse.success("collection", collectionService.get(collectionId));
    }

    @PutMapping("/{collectionId}")
    @Operation(summary = "更新合集名称/类型/描述")
    public AgentResponse<WorkCollection> update(
            @PathVariable String collectionId,
            @RequestBody UpdateCollectionRequest request) {
        WorkCollection collection = collectionService.update(
                collectionId, request.getName(), request.getType(), request.getDescription());
        return AgentResponse.success("collection", collection);
    }

    @DeleteMapping("/{collectionId}")
    @Operation(summary = "删除合集")
    public AgentResponse<Map<String, Object>> delete(@PathVariable String collectionId) {
        collectionService.delete(collectionId);
        return AgentResponse.success("collection", Map.of("deleted", true, "collectionId", collectionId));
    }

    @PostMapping("/{collectionId}/works")
    @Operation(summary = "把作品加入合集")
    public AgentResponse<Map<String, Object>> addWork(
            @PathVariable String collectionId,
            @RequestBody AddWorkRequest request) {
        collectionService.addWork(collectionId, request.getWorkflowId());
        return AgentResponse.success("collection", Map.of(
                "added", true,
                "collectionId", collectionId,
                "workflowId", request.getWorkflowId()));
    }

    @DeleteMapping("/{collectionId}/works/{workflowId}")
    @Operation(summary = "把作品移出合集")
    public AgentResponse<Map<String, Object>> removeWork(
            @PathVariable String collectionId,
            @PathVariable String workflowId) {
        collectionService.removeWork(collectionId, workflowId);
        return AgentResponse.success("collection", Map.of(
                "removed", true,
                "collectionId", collectionId,
                "workflowId", workflowId));
    }

    @GetMapping("/works/{workflowId}")
    @Operation(summary = "查询某作品所在的合集列表")
    public AgentResponse<List<WorkCollection>> listByWorkflow(@PathVariable String workflowId) {
        return AgentResponse.success("collection", collectionService.listByWorkflow(workflowId));
    }

    @Data
    public static class CreateCollectionRequest {
        @NotBlank(message = "合集名称不能为空")
        private String name;
        @NotBlank(message = "合集类型不能为空")
        private String type;
        private String description;
    }

    @Data
    public static class UpdateCollectionRequest {
        private String name;
        private String type;
        private String description;
    }

    @Data
    public static class AddWorkRequest {
        @NotBlank(message = "workflowId 不能为空")
        private String workflowId;
    }
}
