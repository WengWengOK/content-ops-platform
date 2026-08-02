package com.contentops.common.rag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link IncrementalDocumentUpdater} 单元测试 — 增量文档更新。
 *
 * <p>覆盖面试考点（小米必考）：
 * <ul>
 *   <li>SHA-256 哈希计算与比对</li>
 *   <li>变更检测（added/updated/deleted/unchanged）</li>
 *   <li>先删后插策略</li>
 *   <li>版本号与回滚</li>
 * </ul>
 */
@DisplayName("增量文档更新器测试")
class IncrementalDocumentUpdaterTest {

    @Nested
    @DisplayName("SHA-256 哈希计算")
    class HashComputation {

        @Test
        @DisplayName("相同内容应产生相同哈希")
        void sameContent_sameHash() {
            IncrementalDocumentUpdater updater = new IncrementalDocumentUpdater();
            String h1 = updater.computeDocumentHash("hello world");
            String h2 = updater.computeDocumentHash("hello world");
            assertEquals(h1, h2, "相同内容应产生相同哈希");
        }

        @Test
        @DisplayName("不同内容应产生不同哈希")
        void differentContent_differentHash() {
            IncrementalDocumentUpdater updater = new IncrementalDocumentUpdater();
            String h1 = updater.computeDocumentHash("content-A");
            String h2 = updater.computeDocumentHash("content-B");
            assertNotEquals(h1, h2, "不同内容应产生不同哈希");
        }

        @Test
        @DisplayName("哈希长度应为 64 字符（SHA-256 hex）")
        void hashLength_shouldBe64() {
            IncrementalDocumentUpdater updater = new IncrementalDocumentUpdater();
            String hash = updater.computeDocumentHash("test");
            assertEquals(64, hash.length(), "SHA-256 hex 应为 64 字符");
        }
    }

    @Nested
    @DisplayName("变更检测")
    class ChangeDetection {

        @Test
        @DisplayName("应正确识别新增、更新、删除和未变文档")
        void detectChanges_shouldClassifyCorrectly() {
            IncrementalDocumentUpdater updater = new IncrementalDocumentUpdater();

            // 已存储的哈希
            String doc1Hash = updater.computeDocumentHash("doc1 original");
            String doc2Hash = updater.computeDocumentHash("doc2 original");
            Map<String, String> storedHashes = Map.of("doc1", doc1Hash, "doc2", doc2Hash);

            // 当前文档集合
            List<IncrementalDocumentUpdater.DocumentMetadata> current = List.of(
                    new IncrementalDocumentUpdater.DocumentMetadata("doc1", "doc1 original", doc1Hash),  // unchanged
                    new IncrementalDocumentUpdater.DocumentMetadata("doc2", "doc2 modified", null),     // updated
                    new IncrementalDocumentUpdater.DocumentMetadata("doc3", "doc3 new", null)             // added
            );
            // doc2 哈希会变（内容改了），doc1 不变

            IncrementalDocumentUpdater.ChangeSet changes = updater.detectChanges(current, storedHashes);

            assertTrue(changes.added().contains("doc3"), "doc3 应为新增");
            assertTrue(changes.updated().contains("doc2"), "doc2 应为更新");
            assertTrue(changes.unchanged().contains("doc1"), "doc1 应为未变");
            assertTrue(changes.deleted().isEmpty(), "无删除");
        }

        @Test
        @DisplayName("存储中有但当前无的文档应标记为删除")
        void detectChanges_deletedDocs() {
            IncrementalDocumentUpdater updater = new IncrementalDocumentUpdater();

            Map<String, String> storedHashes = Map.of(
                    "doc1", "hash1",
                    "doc2", "hash2"
            );

            List<IncrementalDocumentUpdater.DocumentMetadata> current = List.of(
                    new IncrementalDocumentUpdater.DocumentMetadata("doc1", "content1", "hash1")
            );

            IncrementalDocumentUpdater.ChangeSet changes = updater.detectChanges(current, storedHashes);

            assertTrue(changes.deleted().contains("doc2"), "doc2 应标记为删除");
            assertTrue(changes.unchanged().contains("doc1"), "doc1 应为未变");
        }
    }

    @Nested
    @DisplayName("增量更新执行")
    class ApplyUpdate {

        @Test
        @DisplayName("新文档应触发索引且版本号从 0 变为 1")
        void newDocument_shouldIndexAndIncrementVersion() {
            IncrementalDocumentUpdater updater = new IncrementalDocumentUpdater();

            IncrementalDocumentUpdater.UpdateResult result = updater.applyIncrementalUpdate(
                    "doc-new", "这是新文档内容", null);

            assertTrue(result.reindexed(), "新文档应被重新索引");
            assertEquals(0, result.oldVersion(), "初始版本应为 0");
            assertEquals(1, result.newVersion(), "新文档版本应为 1");
        }

        @Test
        @DisplayName("内容变化应触发先删后插")
        void changedContent_shouldDeleteAndInsert() {
            IncrementalDocumentUpdater updater = new IncrementalDocumentUpdater();

            // 首次索引
            updater.applyIncrementalUpdate("doc-change", "原始内容", null);

            // 内容变化后更新
            String oldHash = updater.computeDocumentHash("原始内容");
            IncrementalDocumentUpdater.UpdateResult result = updater.applyIncrementalUpdate(
                    "doc-change", "修改后的内容", oldHash);

            assertTrue(result.reindexed(), "内容变化应触发重新索引");
            assertEquals(1, result.oldVersion(), "旧版本应为 1");
            assertEquals(2, result.newVersion(), "新版本应为 2");
        }

        @Test
        @DisplayName("内容未变不应触发重新索引")
        void unchangedContent_shouldNotReindex() {
            IncrementalDocumentUpdater updater = new IncrementalDocumentUpdater();

            // 首次索引
            updater.applyIncrementalUpdate("doc-unchanged", "固定内容", null);

            // 内容未变，再次更新
            String hash = updater.computeDocumentHash("固定内容");
            IncrementalDocumentUpdater.UpdateResult result = updater.applyIncrementalUpdate(
                    "doc-unchanged", "固定内容", hash);

            assertFalse(result.reindexed(), "内容未变不应重新索引");
        }
    }
}
