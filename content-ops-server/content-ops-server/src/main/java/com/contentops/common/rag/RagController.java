package com.contentops.common.rag;

import com.contentops.common.dto.AgentResponse;
import com.contentops.common.knowledge.KnowledgeBaseService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RAG 真实接入接口：文档/文本摄入、混合检索（向量+BM25+重排）、状态、RAGAS 评测。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/rag")
@RequiredArgsConstructor
@Tag(name = "RAG 知识库")
public class RagController {

    private final KnowledgeBaseService knowledgeBaseService;
    private final DocumentIngestionPipeline ingestionPipeline;
    private final AdvancedRagService advancedRagService;
    private final HybridSearchService hybridSearchService;
    private final RagasEvaluationService ragasEvaluationService;

    @GetMapping("/status")
    @Operation(summary = "RAG 状态：向量存储模式/维度/BM25 索引大小")
    public AgentResponse<Map<String, Object>> status() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("available", knowledgeBaseService.isAvailable());
        data.put("storeMode", knowledgeBaseService.storeMode());
        data.put("embeddingDimension", knowledgeBaseService.embeddingDimension());
        data.put("bm25IndexSize", hybridSearchService.indexSize());
        return AgentResponse.success("rag", data);
    }

    @PostMapping(value = "/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "上传文档摄入知识库（pdf/docx/md/txt）")
    public AgentResponse<Map<String, Object>> ingestDocument(
            @RequestParam("file") MultipartFile file,
            @RequestParam(required = false) String niche,
            @RequestParam(required = false) String type) {
        Map<String, String> metadata = new HashMap<>();
        metadata.put("type", type == null || type.isBlank() ? "document" : type);
        metadata.put("agent", "user");
        if (niche != null && !niche.isBlank()) {
            metadata.put("niche", niche);
        }
        try {
            DocumentIngestionPipeline.IngestionResult result = ingestionPipeline.ingest(
                    file.getBytes(), file.getOriginalFilename(), metadata);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("documentId", result.documentId());
            data.put("chunkCount", result.chunkCount());
            data.put("success", result.success());
            data.put("message", result.message());
            return AgentResponse.success("rag", data);
        } catch (Exception e) {
            return AgentResponse.failure("rag", "文档摄入失败: " + e.getMessage());
        }
    }

    @PostMapping("/text")
    @Operation(summary = "文本摄入知识库（创作者素材/历史内容）")
    public AgentResponse<Map<String, Object>> ingestText(@RequestBody IngestTextRequest request) {
        Map<String, String> metadata = new HashMap<>();
        metadata.put("type", request.getType() == null || request.getType().isBlank()
                ? "document" : request.getType());
        metadata.put("agent", "user");
        if (request.getNiche() != null && !request.getNiche().isBlank()) {
            metadata.put("niche", request.getNiche());
        }
        if (request.getTitle() != null && !request.getTitle().isBlank()) {
            metadata.put("title", request.getTitle());
        }
        String fileName = request.getTitle() == null || request.getTitle().isBlank()
                ? "text.txt" : request.getTitle() + ".txt";
        DocumentIngestionPipeline.IngestionResult result =
                ingestionPipeline.ingestText(request.getContent(), fileName, metadata);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("documentId", result.documentId());
        data.put("chunkCount", result.chunkCount());
        data.put("success", result.success());
        data.put("message", result.message());
        return AgentResponse.success("rag", data);
    }

    @GetMapping("/search")
    @Operation(summary = "混合检索：向量 + BM25 + 重排")
    public AgentResponse<Map<String, Object>> search(
            @RequestParam String query,
            @RequestParam(required = false) String niche,
            @RequestParam(required = false) Integer topK) {
        Map<String, String> filters = (niche == null || niche.isBlank())
                ? null : Map.of("niche", niche);
        List<AdvancedRagService.RetrievalResult> results =
                advancedRagService.retrieveAndRerank(query, filters, topK == null ? 0 : topK);
        List<Map<String, Object>> list = new ArrayList<>();
        for (AdvancedRagService.RetrievalResult r : results) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("chunkId", r.chunkId());
            item.put("content", r.content());
            item.put("score", Math.round(r.score() * 10000) / 10000.0);
            item.put("source", r.source());
            item.put("metadata", r.metadata());
            list.add(item);
        }
        return AgentResponse.success("rag", Map.of("total", list.size(), "results", list));
    }

    @PostMapping("/evaluate")
    @Operation(summary = "RAGAS 评测：三策略基线对比（向量/混合/混合+重排）")
    public AgentResponse<Map<String, Object>> evaluate(@RequestBody EvaluateRequest request) {
        int topK = request.getTopK() == null || request.getTopK() <= 0 ? 5 : request.getTopK();
        List<RagasEvaluationService.RetrievalResult> vector = new ArrayList<>();
        for (KnowledgeBaseService.SearchResult r :
                knowledgeBaseService.searchSimilar(request.getQuery(), topK, 0.0)) {
            vector.add(new RagasEvaluationService.RetrievalResult(
                    "vector:" + r.content().hashCode(), r.content(), r.score(), "VECTOR_ONLY"));
        }
        List<RagasEvaluationService.RetrievalResult> hybrid = new ArrayList<>();
        for (AdvancedRagService.RetrievalResult r :
                advancedRagService.retrieve(request.getQuery(), null, topK)) {
            hybrid.add(new RagasEvaluationService.RetrievalResult(
                    r.chunkId(), r.content(), r.score(), "HYBRID"));
        }
        List<RagasEvaluationService.RetrievalResult> reranked = new ArrayList<>();
        for (AdvancedRagService.RetrievalResult r :
                advancedRagService.retrieveAndRerank(request.getQuery(), null, topK)) {
            reranked.add(new RagasEvaluationService.RetrievalResult(
                    r.chunkId(), r.content(), r.score(), "HYBRID_WITH_RERANK"));
        }
        RagasEvaluationService.BaselineComparison comparison =
                ragasEvaluationService.compareStrategies(
                        request.getQuery(), request.getGroundTruth(), vector, hybrid, reranked);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("query", comparison.query());
        data.put("winner", comparison.winner());
        data.put("recommendation", comparison.recommendation());
        data.put("vectorOnly", comparison.vectorOnly());
        data.put("hybrid", comparison.hybrid());
        data.put("hybridWithRerank", comparison.hybridWithRerank());
        return AgentResponse.success("rag", data);
    }

    @Data
    public static class IngestTextRequest {
        private String title;
        private String content;
        private String niche;
        private String type;
    }

    @Data
    public static class EvaluateRequest {
        private String query;
        private String groundTruth;
        private Integer topK;
    }
}
