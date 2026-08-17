package com.contentops.common.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 增量文档更新器（小米必考：SHA-256 增量更新 + 先删后插 + 版本回滚）。
 *
 * <p>本组件实现 RAG 知识库的<b>增量更新机制</b>，核心目标是「只对真正发生变化的文档
 * 重新分块与向量化，避免全量重建索引」。通过为每篇文档计算内容指纹（SHA-256）并与
 * 已索引版本比对，精准识别 added / updated / deleted / unchanged 四类变更，再以
 * <b>先删后插（delete-then-insert）</b>策略原子地替换变化文档的全部向量。
 *
 * <h2>设计动机（面试洞察）</h2>
 *
 * <h3>1. SHA-256 哈希增量比对（SHA-256 Incremental Update）</h3>
 * <p>全量重建索引代价昂贵：每次源文档变更都重新分块 + 重新向量化 + 重新入库，
 * 既浪费嵌入模型算力，又会造成向量库短暂不可用。增量更新的关键在于<b>以最小代价
 * 定位变化</b>——为每篇文档计算 SHA-256 内容指纹并持久化，更新时只需比对指纹即可
 * 判定文档是否变化。SHA-256 具备抗碰撞性：内容任意改动（哪怕一个字符）都会导致哈希
 * 完全不同，因此哈希一致即内容一致，可安全跳过重新索引。
 * <ul>
 *   <li>{@link #computeDocumentHash(String)}：对文档 UTF-8 字节计算 SHA-256，输出
 *       64 位十六进制字符串作为内容指纹；</li>
 *   <li>{@link #detectChanges(List, Map)}：将当前文档集合的指纹与已存储指纹逐篇比对，
 *       产出 {@link ChangeSet}（added / updated / deleted / unchanged）；</li>
 *   <li>哈希一致 → {@code unchanged}，跳过重新索引；哈希不同 → {@code updated}，
 *       触发先删后插；仅存在于一侧 → {@code added} / {@code deleted}。</li>
 * </ul>
 *
 * <h3>2. 先删后插策略（Delete-Then-Insert）</h3>
 * <p>当文档内容变化时，其旧分块向量已全部失效，必须从向量库中清除，否则残留的陈旧
 * 向量会污染检索结果。本组件采用「先删后插」原子替换：先按 {@code doc_id} 批量删除
 * 该文档的全部旧向量，再对最新内容重新分块、向量化并入库。这与「先插后删」相比更
 * 安全——若中途失败，向量库中至多残留旧向量（检索质量不降），而不会出现「新向量已入、
 * 旧向量未清」的重复污染。{@link #applyIncrementalUpdate(String, String, String)}
 * 即按此流程编排：判定变化 → {@link #deleteVectorsByDocId(String)} →
 * {@link #indexDocument(String, String, String, int)}。
 *
 * <h3>3. doc_version 版本号与回滚（Version Rollback）</h3>
 * <p>每次成功应用更新（新增或变化重索引）后，文档版本号 {@code doc_version} 自增；
 * 通过 {@link #documentVersionStore} 维护 {@code doc_id -> version} 映射，使每篇
 * 文档具备可追溯的版本历史。版本号支撑<b>回滚</b>能力：当某次重索引产出质量退化
 * （例如新内容被错误解析、向量化异常导致检索召回下降），可调用
 * {@link #rollbackDocument(String, String)} 将版本号回退一档并以历史内容重新索引，
 * 快速恢复到上一个已知良好状态。版本号同时是幂等与可观测的关键——日志与监控据此
 * 区分「首次索引」与「第 N 次重索引」。
 *
 * <h3>4. 变更统计（Change Statistics）</h3>
 * <p>{@link ChangeSet} 显式承载 added / updated / deleted / unchanged 四类文档 ID
 * 列表，{@link #detectChanges} 在返回前以 INFO 级别输出各类计数，便于监控增量更新
 * 的实际工作量（例如「本次仅 3 篇变化，跳过 997 篇未变文档」），并驱动调度决策。
 *
 * <h2>与既有组件的集成</h2>
 * <p>本组件聚焦「变更检测 + 更新编排」，向量化与入库的具体实现以
 * {@link #deleteVectorsByDocId(String)}、{@link #indexDocument(String, String, String, int)}
 * 两个 {@code protected} 钩子方法暴露，默认实现仅记录日志并估算分块数。生产环境可
 * 覆写这两个钩子，委托 {@link DocumentChunker} 分块、{@code KnowledgeBaseService}
 * 入库（PGVector + BGE 嵌入），并与 {@link DocumentIngestionPipeline} 的全量摄入
 * 链路协同：全量摄入用于首建，增量更新用于日常维护。
 *
 * <h2>线程安全</h2>
 * <p>版本存储与哈希存储均使用 {@link ConcurrentHashMap}，支持多线程并发调用
 * {@link #applyIncrementalUpdate}；但<b>同一 doc_id 的并发更新仍应由上层串行化</b>
 * （例如按 doc_id 加锁或单分区消费），以避免先删后插过程中的竞态。
 *
 * <h2>Java 21 特性</h2>
 * <ul>
 *   <li>记录类型 {@link DocumentMetadata}、{@link ChangeSet}、{@link UpdateResult}；</li>
 *   <li>密封接口 {@link UpdateDecision} 配合 {@code switch} 模式匹配
 *       （{@link NewDocument} / {@link UnchangedDocument} / {@link ChangedDocument}
 *       三分支穷尽匹配，无需 {@code default}）；</li>
 *   <li>{@link HexFormat} 进行字节→十六进制转换；增强 {@code switch} 表达式。</li>
 * </ul>
 *
 * @see DocumentIngestionPipeline
 * @see DocumentChunker
 * @see HybridSearchService
 */
@Slf4j
@Component
public class IncrementalDocumentUpdater {

    /** SHA-256 哈希的十六进制长度（256 bit = 64 hex chars）。 */
    private static final int SHA256_HEX_LENGTH = 64;

    /** 估算分块数时使用的默认分块大小（字符数），仅用于日志统计，不影响实际分块。 */
    private static final int DEFAULT_CHUNK_SIZE = 512;

    /** 十六进制格式化器（小写），用于 SHA-256 字节 → 十六进制字符串转换。 */
    private static final HexFormat HEX = HexFormat.of();

    /**
     * 文档版本存储：{@code doc_id -> doc_version}。
     *
     * <p>每次成功新增或重索引后版本号自增；{@code 0} 表示该文档尚未被索引过。
     * 该映射是<b>回滚</b>能力的基础——回滚即把版本号回退一档。
     */
    private final Map<String, Integer> documentVersionStore = new ConcurrentHashMap<>();

    /**
     * 文档哈希存储：{@code doc_id -> SHA-256 hex}。
     *
     * <p>记录当前已索引内容的指纹。增量更新时以此为基准比对，判定文档是否变化，
     * 从而决定是否触发先删后插。
     */
    private final Map<String, String> documentHashStore = new ConcurrentHashMap<>();

    // ─────────────────────────── 数据模型（记录类型） ───────────────────────────

    /**
     * 文档元数据（增量比对的输入单元）。
     *
     * @param docId   文档唯一标识
     * @param content 文档纯文本内容（用于在 {@link #hash} 缺省时现算指纹）
     * @param hash    文档内容的 SHA-256 哈希（十六进制），可为 {@code null}，
     *                此时由 {@link #computeDocumentHash(String)} 基于 {@code content} 现算
     */
    public record DocumentMetadata(String docId, String content, String hash) {
    }

    /**
     * 变更集合：增量检测 {@link #detectChanges(List, Map)} 的产物。
     *
     * <p>四类列表互斥且并集覆盖全部受影响文档 ID，承载<b>变更统计</b>信息
     * （各列表长度即各类计数）。
     *
     * @param added     新增文档 ID（当前存在、存储中不存在）
     * @param updated   更新文档 ID（哈希发生变化，需先删后插）
     * @param deleted   删除文档 ID（存储中存在、当前不存在，需清理向量）
     * @param unchanged 未变更文档 ID（哈希一致，跳过重新索引）
     */
    public record ChangeSet(List<String> added, List<String> updated,
                            List<String> deleted, List<String> unchanged) {
    }

    /**
     * 增量更新结果：{@link #applyIncrementalUpdate} / {@link #removeDocument} /
     * {@link #rollbackDocument} 的产物，描述本次执行的实际操作。
     *
     * @param docId      文档唯一标识
     * @param oldVersion 操作前版本号（{@code 0} 表示此前不存在）
     * @param newVersion 操作后版本号
     * @param reindexed  是否执行了重新索引（先删后插 / 首次插入 / 回滚重索引均为 {@code true}；
     *                   内容未变或文档不存在时为 {@code false}）
     * @param reason     操作说明，便于日志与可观测
     */
    public record UpdateResult(String docId, int oldVersion, int newVersion,
                               boolean reindexed, String reason) {
    }

    // ─────────────── 更新决策（密封接口 + switch 模式匹配） ───────────────

    /**
     * 更新决策密封接口：由 {@link #decide} 产出，供 {@link #applyIncrementalUpdate}
     * 以 {@code switch} 模式匹配穷尽处理。
     */
    private sealed interface UpdateDecision permits NewDocument, UnchangedDocument, ChangedDocument {
    }

    /** 决策：新增文档（此前无任何哈希记录，需首次插入）。 */
    private record NewDocument(String newHash) implements UpdateDecision {
    }

    /** 决策：文档未变化（新哈希与旧哈希一致，跳过重新索引）。 */
    private record UnchangedDocument(String hash, int version) implements UpdateDecision {
    }

    /** 决策：文档已变化（哈希不同，需先删后插，版本号自增）。 */
    private record ChangedDocument(String oldHash, String newHash, int oldVersion) implements UpdateDecision {
    }

    // ─────────────────────────── 核心方法 ───────────────────────────

    /**
     * 计算文档内容的 SHA-256 哈希（十六进制字符串）。
     *
     * <p>对文档 UTF-8 字节做 SHA-256 摘要，再以 {@link HexFormat} 转为 64 位小写十六进制
     * 字符串。该指纹是增量比对的基础：内容任意改动都会导致指纹完全不同，指纹一致即
     * 内容一致。
     *
     * @param content 文档纯文本内容，{@code null} 视作空串
     * @return 64 位十六进制 SHA-256 哈希字符串
     * @throws IllegalStateException 当前 JDK 不支持 SHA-256（理论上不会发生，SHA-256 为标准算法）
     */
    public String computeDocumentHash(String content) {
        String safe = content == null ? "" : content;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(safe.getBytes(StandardCharsets.UTF_8));
            return HEX.formatHex(hashBytes);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the Java specification, so this is effectively unreachable.
            throw new IllegalStateException("SHA-256 algorithm not available in this JDK", e);
        }
    }

    /**
     * 检测文档变更：将当前文档集合与已存储哈希逐篇比对，产出 {@link ChangeSet}。
     *
     * <p>比对规则：
     * <ul>
     *   <li>当前存在、存储中无指纹 → {@code added}（新增）；</li>
     *   <li>指纹不同 → {@code updated}（更新，待先删后插）；</li>
     *   <li>指纹相同 → {@code unchanged}（未变，跳过）；</li>
     *   <li>存储中存在、当前不存在 → {@code deleted}（删除，待清理向量）。</li>
     * </ul>
     * 当 {@link DocumentMetadata#hash()} 为 {@code null} 时，基于
     * {@link DocumentMetadata#content()} 现算指纹，避免调用方重复计算。
     *
     * @param current       当前文档元数据列表（最新快照），可为 {@code null}
     * @param storedHashes  已存储的 {@code doc_id -> SHA-256} 映射，可为 {@code null}
     * @return 变更集合，各列表均为不可变且保持输入顺序
     */
    public ChangeSet detectChanges(List<DocumentMetadata> current, Map<String, String> storedHashes) {
        List<String> added = new ArrayList<>();
        List<String> updated = new ArrayList<>();
        List<String> unchanged = new ArrayList<>();
        Set<String> currentIds = new HashSet<>();

        if (current != null) {
            for (DocumentMetadata doc : current) {
                if (doc == null || doc.docId() == null || doc.docId().isBlank()) {
                    continue;
                }
                currentIds.add(doc.docId());
                String effectiveHash = doc.hash() != null
                        ? doc.hash()
                        : computeDocumentHash(doc.content());
                String storedHash = storedHashes == null ? null : storedHashes.get(doc.docId());
                if (storedHash == null) {
                    added.add(doc.docId());
                } else if (!Objects.equals(storedHash, effectiveHash)) {
                    updated.add(doc.docId());
                } else {
                    unchanged.add(doc.docId());
                }
            }
        }

        List<String> deleted = new ArrayList<>();
        if (storedHashes != null) {
            for (String storedId : storedHashes.keySet()) {
                if (!currentIds.contains(storedId)) {
                    deleted.add(storedId);
                }
            }
        }

        // 变更统计：以 INFO 输出各类计数，驱动调度与监控
        log.info("Change detection: added={}, updated={}, deleted={}, unchanged={}",
                added.size(), updated.size(), deleted.size(), unchanged.size());
        return new ChangeSet(
                List.copyOf(added),
                List.copyOf(updated),
                List.copyOf(deleted),
                List.copyOf(unchanged));
    }

    /**
     * 应用增量更新：依据新旧哈希判定操作类型，对变化文档执行先删后插并递增版本号。
     *
     * <p>编排流程（小米必考要点）：
     * <ol>
     *   <li>计算新内容指纹 {@code newHash}；</li>
     *   <li>解析旧哈希（优先入参 {@code oldHash}，缺省回退到 {@link #documentHashStore}）；</li>
     *   <li>通过 {@link #decide} 产出 {@link UpdateDecision}，以 {@code switch} 模式匹配分发：
     *     <ul>
     *       <li>{@link NewDocument}：首次插入，版本 {@code 0 -> 1}，{@code reindexed=true}；</li>
     *       <li>{@link UnchangedDocument}：哈希一致，跳过重索引，版本不变，{@code reindexed=false}；</li>
     *       <li>{@link ChangedDocument}：<b>先删后插</b>——{@link #deleteVectorsByDocId}
     *           删除该 doc_id 全部旧向量，{@link #indexDocument} 重新分块向量化入库，
     *           版本自增，{@code reindexed=true}。</li>
     *     </ul>
     *   </li>
     *   <li>更新 {@link #documentVersionStore} 与 {@link #documentHashStore}。</li>
     * </ol>
     *
     * <p>「只对变化文档重索引」是性能关键：未变文档直接返回，不触碰向量库，避免全量重建。
     *
     * @param docId      文档唯一标识，不可为空白
     * @param newContent 最新文档内容，{@code null} 视作空串
     * @param oldHash    此前已索引内容的 SHA-256 哈希；{@code null} 表示新增文档
     *                   （此时若 {@link #documentHashStore} 亦无记录则按新增处理）
     * @return 更新结果，描述版本变迁与是否重索引
     * @throws IllegalArgumentException {@code docId} 为空白
     */
    public UpdateResult applyIncrementalUpdate(String docId, String newContent, String oldHash) {
        if (docId == null || docId.isBlank()) {
            throw new IllegalArgumentException("docId must not be blank");
        }
        String safeContent = newContent == null ? "" : newContent;
        String newHash = computeDocumentHash(safeContent);

        // 解析旧哈希：优先入参，其次查内存存储（兼顾显式传参与状态自洽两种用法）
        String effectiveOldHash = oldHash != null ? oldHash : documentHashStore.get(docId);
        int oldVersion = documentVersionStore.getOrDefault(docId, 0);

        UpdateDecision decision = decide(effectiveOldHash, newHash, oldVersion);

        // switch 模式匹配：密封类型三分支穷尽，无需 default
        return switch (decision) {
            case NewDocument nd -> {
                // 新增文档：纯插入（先删后插 N/A，因无旧向量可删）
                indexDocument(docId, safeContent, nd.newHash(), 1);
                documentVersionStore.put(docId, 1);
                documentHashStore.put(docId, nd.newHash());
                log.info("Incremental update [INSERT]: docId={}, version 0 -> 1, reindexed=true", docId);
                yield new UpdateResult(docId, 0, 1, true,
                        "new document inserted (pure insert, delete-then-insert N/A)");
            }
            case UnchangedDocument ud -> {
                // 哈希一致：跳过重新索引，避免全量重建
                log.info("Incremental update [UNCHANGED]: docId={}, SHA-256 matched, skip reindex, version stays {}",
                        docId, ud.version());
                yield new UpdateResult(docId, ud.version(), ud.version(), false,
                        "content unchanged, skip reindex (SHA-256 identical)");
            }
            case ChangedDocument cd -> {
                // 内容变化：先删后插（delete-then-insert）
                int deleted = deleteVectorsByDocId(docId);
                int newVersion = cd.oldVersion() + 1;
                int inserted = indexDocument(docId, safeContent, cd.newHash(), newVersion);
                documentVersionStore.put(docId, newVersion);
                documentHashStore.put(docId, cd.newHash());
                log.info("Incremental update [DELETE-THEN-INSERT]: docId={}, deletedVectors={}, insertedChunks={}, version {} -> {}",
                        docId, deleted, inserted, cd.oldVersion(), newVersion);
                yield new UpdateResult(docId, cd.oldVersion(), newVersion, true,
                        "content changed, applied delete-then-insert (deleted=" + deleted
                                + ", inserted=" + inserted + ")");
            }
        };
    }

    /**
     * 删除文档：按 {@code doc_id} 清理全部向量并从版本/哈希存储移除。
     *
     * <p>对应 {@link ChangeSet#deleted()} 的处理：当文档从知识库下线时，先删除其全部
     * 向量，再清除 {@link #documentVersionStore} 与 {@link #documentHashStore} 中的记录，
     * 版本号归零。
     *
     * @param docId 文档唯一标识，不可为空白
     * @return 更新结果；文档不存在时为空操作（{@code reindexed=false}）
     * @throws IllegalArgumentException {@code docId} 为空白
     */
    public UpdateResult removeDocument(String docId) {
        if (docId == null || docId.isBlank()) {
            throw new IllegalArgumentException("docId must not be blank");
        }
        int oldVersion = documentVersionStore.getOrDefault(docId, 0);
        if (oldVersion == 0 && !documentHashStore.containsKey(docId)) {
            log.info("Remove document [NOOP]: docId={} not found in store", docId);
            return new UpdateResult(docId, 0, 0, false, "document not found, nothing to remove");
        }
        int deleted = deleteVectorsByDocId(docId);
        documentVersionStore.remove(docId);
        documentHashStore.remove(docId);
        log.info("Remove document [DELETE]: docId={}, deletedVectors={}, version {} -> 0", docId, deleted, oldVersion);
        return new UpdateResult(docId, oldVersion, 0, true,
                "document removed (deleted " + deleted + " vectors)");
    }

    /**
     * 回滚文档到上一版本（版本号机制支撑回滚）。
     *
     * <p>当某次重索引产出质量退化时，调用本方法以历史内容重新索引，把版本号回退一档，
     * 恢复到上一个已知良好状态。回滚同样采用先删后插：先清除当前（可能劣化的）向量，
     * 再以历史内容重新分块向量化入库。
     *
     * @param docId           文档唯一标识，不可为空白
     * @param previousContent 回滚目标的历史内容
     * @return 回滚结果；已处于初始版本（无法再回退）时为空操作
     * @throws IllegalArgumentException {@code docId} 为空白
     */
    public UpdateResult rollbackDocument(String docId, String previousContent) {
        if (docId == null || docId.isBlank()) {
            throw new IllegalArgumentException("docId must not be blank");
        }
        int currentVersion = documentVersionStore.getOrDefault(docId, 0);
        if (currentVersion <= 1) {
            log.warn("Rollback skipped: docId={} already at initial version {}, nothing to roll back",
                    docId, currentVersion);
            return new UpdateResult(docId, currentVersion, currentVersion, false,
                    "already at initial version, nothing to roll back");
        }
        int targetVersion = currentVersion - 1;
        String restoredHash = computeDocumentHash(previousContent);
        // 回滚同样先删后插：删除当前向量，以历史内容重新索引
        int deleted = deleteVectorsByDocId(docId);
        int inserted = indexDocument(docId, previousContent == null ? "" : previousContent,
                restoredHash, targetVersion);
        documentVersionStore.put(docId, targetVersion);
        documentHashStore.put(docId, restoredHash);
        log.info("Rollback [DELETE-THEN-INSERT]: docId={}, deletedVectors={}, insertedChunks={}, version {} -> {}",
                docId, deleted, inserted, currentVersion, targetVersion);
        return new UpdateResult(docId, currentVersion, targetVersion, true,
                "rolled back to previous version (deleted=" + deleted + ", inserted=" + inserted + ")");
    }

    // ─────────────────────────── 访问器 ───────────────────────────

    /**
     * 获取文档当前版本号。
     *
     * @param docId 文档唯一标识
     * @return 版本号，{@code 0} 表示尚未索引
     */
    public int getDocumentVersion(String docId) {
        return documentVersionStore.getOrDefault(docId, 0);
    }

    /**
     * 获取文档当前已索引内容的 SHA-256 哈希。
     *
     * @param docId 文档唯一标识
     * @return 哈希字符串，未索引过返回 {@code null}
     */
    public String getStoredHash(String docId) {
        return documentHashStore.get(docId);
    }

    /**
     * 返回版本存储的不可变快照（用于监控/诊断）。
     *
     * @return {@code doc_id -> version} 不可变映射
     */
    public Map<String, Integer> getDocumentVersionStore() {
        return Map.copyOf(documentVersionStore);
    }

    /**
     * 返回哈希存储的不可变快照（用于监控/诊断）。
     *
     * @return {@code doc_id -> SHA-256} 不可变映射
     */
    public Map<String, String> getDocumentHashStore() {
        return Map.copyOf(documentHashStore);
    }

    // ─────────────────────────── 决策与钩子 ───────────────────────────

    /**
     * 依据旧哈希、新哈希与旧版本号产出更新决策。
     *
     * @param effectiveOldHash 生效的旧哈希（入参与存储取并，{@code null} 表示新增）
     * @param newHash          新内容哈希
     * @param oldVersion       旧版本号
     * @return 更新决策
     */
    private UpdateDecision decide(String effectiveOldHash, String newHash, int oldVersion) {
        if (effectiveOldHash == null) {
            return new NewDocument(newHash);
        }
        if (newHash.equals(effectiveOldHash)) {
            return new UnchangedDocument(newHash, oldVersion);
        }
        return new ChangedDocument(effectiveOldHash, newHash, oldVersion);
    }

    /**
     * 删除指定文档的全部旧向量（先删后插的「删」阶段）。
     *
     * <p>默认实现仅记录日志并返回 {@code 0}；生产环境应覆写为按 {@code doc_id}
     * 批量删除向量库中的旧分块向量（例如 PGVector 中按元数据 {@code docId} 过滤删除）。
     *
     * @param docId 文档唯一标识
     * @return 实际删除的向量数（估算），默认 {@code 0}
     */
    protected int deleteVectorsByDocId(String docId) {
        log.debug("Delete-then-insert [delete phase]: removing all vectors for docId={}", docId);
        return 0;
    }

    /**
     * 对文档重新分块并向量化入库（先删后插的「插」阶段）。
     *
     * <p>默认实现仅记录日志并按 {@link #DEFAULT_CHUNK_SIZE} 估算分块数；生产环境应覆写为
     * 调用 {@link DocumentChunker#chunk(String, Map)} 分块 + 向量库写入
     * （参见 {@link DocumentIngestionPipeline}）。仅对变化文档执行本方法，避免全量重索引。
     *
     * @param docId   文档唯一标识
     * @param content 文档内容
     * @param hash    文档哈希（可写入分块元数据便于溯源）
     * @param version 目标版本号
     * @return 入库的分块数（估算）
     */
    protected int indexDocument(String docId, String content, String hash, int version) {
        int estimatedChunks = estimateChunkCount(content);
        log.debug("Delete-then-insert [insert phase]: re-chunk & vectorize docId={}, version={}, estimatedChunks={}",
                docId, version, estimatedChunks);
        return estimatedChunks;
    }

    /**
     * 估算分块数：按 {@link #DEFAULT_CHUNK_SIZE} 向上取整，仅用于日志统计。
     *
     * @param content 文档内容
     * @return 估算分块数，空内容返回 {@code 0}
     */
    private static int estimateChunkCount(String content) {
        if (content == null || content.isEmpty()) {
            return 0;
        }
        return Math.max(1, (content.length() + DEFAULT_CHUNK_SIZE - 1) / DEFAULT_CHUNK_SIZE);
    }
}
