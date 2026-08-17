package com.contentops.common.collection;

import com.contentops.common.exception.BusinessException;
import com.contentops.common.exception.ErrorCode;
import com.contentops.common.security.AuthContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * 作品合集业务：创建/修改/删除、作品归属、按类型区分、创建时/生成后指定合集。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkCollectionService {

    private final WorkCollectionRepository repository;
    private final WorkflowSummaryResolver summaryResolver;

    public WorkCollection create(String name, String type, String description) {
        if (name == null || name.isBlank()) {
            throw new BusinessException(ErrorCode.MISSING_REQUIRED_INPUT, "合集名称不能为空");
        }
        if (type == null || type.isBlank()) {
            throw new BusinessException(ErrorCode.MISSING_REQUIRED_INPUT, "合集类型不能为空");
        }
        String collectionId = UUID.randomUUID().toString();
        String ownerId = AuthContext.currentUserId();
        if (!repository.create(collectionId, ownerId, name.trim(), type.trim(), description)) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "创建作品合集失败");
        }
        log.info("[Collection] 创建合集成功: id={}, name={}, type={}, owner={}",
                collectionId, name, type, ownerId);
        return repository.findById(collectionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.INTERNAL_ERROR, "创建作品合集失败"));
    }

    public List<WorkCollection> list() {
        String ownerId = AuthContext.currentUserId();
        return ownerId == null ? repository.listAll() : repository.listByOwner(ownerId);
    }

    public WorkCollection get(String collectionId) {
        WorkCollection collection = findAndCheckOwner(collectionId);
        List<CollectionWork> works = repository.listWorkIds(collectionId).stream()
                .map(summaryResolver::load)
                .filter(java.util.Optional::isPresent)
                .map(java.util.Optional::get)
                .map(summaryResolver::summarize)
                .toList();
        collection.setWorks(works);
        collection.setWorkCount(works.size());
        return collection;
    }

    public WorkCollection update(String collectionId, String name, String type, String description) {
        WorkCollection collection = findAndCheckOwner(collectionId);
        String newName = name == null || name.isBlank() ? collection.getName() : name.trim();
        String newType = type == null || type.isBlank() ? collection.getType() : type.trim();
        repository.update(collectionId, newName, newType, description);
        return findAndCheckOwner(collectionId);
    }

    public void delete(String collectionId) {
        findAndCheckOwner(collectionId);
        repository.delete(collectionId);
        log.info("[Collection] 删除合集: id={}", collectionId);
    }

    public void addWork(String collectionId, String workflowId) {
        findAndCheckOwner(collectionId);
        summaryResolver.load(workflowId)
                .orElseThrow(() -> new BusinessException(ErrorCode.WORKFLOW_NOT_FOUND, workflowId));
        repository.addWork(collectionId, workflowId);
        log.info("[Collection] 作品加入合集: collection={}, workflow={}", collectionId, workflowId);
    }

    public void removeWork(String collectionId, String workflowId) {
        findAndCheckOwner(collectionId);
        repository.removeWork(collectionId, workflowId);
    }

    public List<WorkCollection> listByWorkflow(String workflowId) {
        return repository.listByWorkflow(workflowId);
    }

    /**
     * 创建作品时批量指定合集（跳过不存在或不属于当前用户的合集）。
     */
    public void addWorkToCollections(String workflowId, List<String> collectionIds) {
        if (collectionIds == null || collectionIds.isEmpty()) {
            return;
        }
        String currentUserId = AuthContext.currentUserId();
        for (String collectionId : collectionIds) {
            repository.findById(collectionId).ifPresent(collection -> {
                boolean ownerOk = currentUserId == null
                        || currentUserId.equals(collection.getOwnerId());
                if (ownerOk) {
                    repository.addWork(collectionId, workflowId);
                }
            });
        }
    }

    private WorkCollection findAndCheckOwner(String collectionId) {
        WorkCollection collection = repository.findById(collectionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_WORKFLOW_STATE,
                        "作品合集不存在: " + collectionId));
        String currentUserId = AuthContext.currentUserId();
        if (currentUserId != null && !currentUserId.equals(collection.getOwnerId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "该作品合集");
        }
        return collection;
    }
}
