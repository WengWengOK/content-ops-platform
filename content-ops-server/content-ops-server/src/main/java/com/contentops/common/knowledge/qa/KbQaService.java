package com.contentops.common.knowledge.qa;

import com.contentops.common.exception.BusinessException;
import com.contentops.common.exception.ErrorCode;
import com.contentops.common.rag.AdvancedRagService;
import com.contentops.common.rag.HybridSearchService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 知识库问答（KB QA）：检索 → 充分性判断 → 接地生成 → 引用校验 → 忠实度校验 → 落库。
 *
 * <p>这是「RAG 错误自动识别」的载体：每一次问答都带齐四类错误的判定结果，
 * 便于在线拦截高风险、离线聚类盲区、转人工后复盘。
 *
 * <p>设计原则（与项目既有约定一致）：<b>只标记，不自动修正</b>。
 * 检出风险时标记 {@code NEEDS_REVIEW} / {@code SAMPLED} 并落库，由人工决定是改检索、改 Prompt 还是补知识库。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KbQaService {

    private final QaProperties properties;
    private final AdvancedRagService advancedRagService;
    private final HybridSearchService hybridSearchService;
    private final RetrievalRelevanceEvaluator relevanceEvaluator;
    private final CitationGuard citationGuard;
    private final AnswerFaithfulnessChecker faithfulnessChecker;
    private final FaithfulnessLlmJudge faithfulnessLlmJudge;
    private final GroundedAnswerGenerator answerGenerator;
    private final QaRepository qaRepository;
    private final ObjectMapper objectMapper;

    /** 检索（含改写重试）的结果集合。 */
    private record RetrievalOutcome(List<AdvancedRagService.RetrievalResult> hits,
                                    RetrievalRelevanceEvaluator.RelevanceAssessment assessment,
                                    List<String> queries, int rewriteRounds) {
    }

    /**
     * 提问并生成接地答案。
     *
     * @param question 用户问题
     * @param ownerId  租户
     * @param topK     返回证据条数（为空用配置默认）
     * @param filters  元数据过滤（如 type=document）
     * @return 落库后的问答记录（含五层质量信号）
     */
    public QaRecord ask(String question, String ownerId, Integer topK, Map<String, String> filters) {
        if (question == null || question.isBlank()) {
            throw new BusinessException(ErrorCode.MISSING_REQUIRED_INPUT, "question");
        }
        long started = System.currentTimeMillis();
        int limit = topK == null || topK <= 0 ? properties.getTopK() : topK;
        String qaId = "qa-" + UUID.randomUUID();

        RetrievalOutcome outcome = retrieveWithRetry(question, filters, limit);
        boolean refusedByRetrieval = !outcome.assessment().sufficient() && properties.isRefuseOnInsufficient();

        GroundedAnswerGenerator.GeneratedAnswer generated = refusedByRetrieval
                ? new GroundedAnswerGenerator.GeneratedAnswer(properties.getRefusalMessage(), "refuse")
                : answerGenerator.generate(question, outcome.hits());

        // 模型主动拒答（「知识库中没有找到…」）：没有引用是正常的，不按「引用不实」判，
        // 但要在检索侧记一个盲区信号——因为检索层认为找得到，模型却答不了。
        boolean refusedByModel = !refusedByRetrieval && answerGenerator.looksLikeRefusal(generated.answer());
        if (refusedByModel) {
            outcome = new RetrievalOutcome(outcome.hits(), withModelRefused(outcome.assessment()),
                    outcome.queries(), outcome.rewriteRounds());
        }
        boolean refused = refusedByRetrieval || refusedByModel;

        // 引用校验：拒答不参与（拒答本就不该有引用）
        CitationGuard.CitationCheck citationCheck = citationGuard.check(generated.answer(), outcome.hits().size());
        List<String> evidences = outcome.hits().stream()
                .map(AdvancedRagService.RetrievalResult::content).toList();
        AnswerFaithfulnessChecker.FaithfulnessReport faithfulness =
                faithfulnessChecker.check(generated.answer(), evidences, refused);

        // 可选：模糊带 / 高风险结论送模型复核（只改标记，不改答案）
        List<AnswerFaithfulnessChecker.ClaimVerdict> reJudged =
                faithfulnessLlmJudge.reJudge(faithfulness.claims(), evidences);
        if (reJudged != faithfulness.claims()) {
            faithfulness = faithfulnessChecker.summarize(reJudged, refused);
        }

        String riskLevel = aggregateRisk(citationCheck, faithfulness, outcome.assessment(), refused);
        QaRecord record = QaRecord.builder()
                .qaId(qaId)
                .ownerId(ownerId)
                .question(question)
                .answer(generated.answer())
                .refused(refused)
                .source(generated.source())
                .citations(toJson(citations(outcome.hits())))
                .citationCheck(toJson(citationCheck))
                .retrieval(toJson(retrievalPayload(outcome)))
                .faithfulness(toJson(faithfulness))
                .riskLevel(riskLevel)
                .reviewState(reviewStateOf(riskLevel))
                .suspectedError(false)
                .traceId(MDC.get("traceId"))
                .latencyMs(System.currentTimeMillis() - started)
                .createdAt(LocalDateTime.now())
                .build();
        qaRepository.insert(record);
        log.info("[KbQa] qaId={}, refused={}, source={}, risk={}, review={}, signals={}, "
                        + "relevant={}/{}, rounds={}, latencyMs={}",
                qaId, refused, generated.source(), riskLevel, record.getReviewState(),
                outcome.assessment().signals(), outcome.assessment().relevantCount(),
                outcome.assessment().candidateCount(), outcome.rewriteRounds(), record.getLatencyMs());
        return record;
    }

    /**
     * 复检：用当前检索结果对**已存答案**重新做引用 / 忠实度标记（改完检索或 Prompt 后跑回归用）。
     * 不修改答案文本，只更新标记与状态。
     */
    public QaRecord recheck(String qaId) {
        QaRecord record = require(qaId);
        int limit = properties.getTopK();
        RetrievalOutcome outcome = retrieveWithRetry(record.getQuestion(), null, limit);
        List<String> evidences = outcome.hits().stream()
                .map(AdvancedRagService.RetrievalResult::content).toList();
        CitationGuard.CitationCheck citationCheck = citationGuard.check(record.getAnswer(), outcome.hits().size());
        AnswerFaithfulnessChecker.FaithfulnessReport faithfulness =
                faithfulnessChecker.check(record.getAnswer(), evidences, record.isRefused());
        String riskLevel = aggregateRisk(citationCheck, faithfulness, outcome.assessment(), record.isRefused());
        qaRepository.updateReviewState(qaId, reviewStateOf(riskLevel));
        return qaRepository.findById(qaId).map(updated -> {
            QaRecord patched = QaRecord.builder()
                    .qaId(updated.getQaId())
                    .ownerId(updated.getOwnerId())
                    .question(updated.getQuestion())
                    .answer(updated.getAnswer())
                    .refused(updated.isRefused())
                    .source(updated.getSource())
                    .citations(toJson(citations(outcome.hits())))
                    .citationCheck(toJson(citationCheck))
                    .retrieval(toJson(retrievalPayload(outcome)))
                    .faithfulness(toJson(faithfulness))
                    .riskLevel(riskLevel)
                    .reviewState(reviewStateOf(riskLevel))
                    .suspectedError(updated.isSuspectedError())
                    .suspectedReason(updated.getSuspectedReason())
                    .traceId(updated.getTraceId())
                    .latencyMs(updated.getLatencyMs())
                    .createdAt(updated.getCreatedAt())
                    .build();
            // 覆盖更新标记字段
            qaRepository.updateMarkers(qaId, patched.getCitations(), patched.getCitationCheck(),
                    patched.getRetrieval(), patched.getFaithfulness(), riskLevel, patched.getReviewState());
            return patched;
        }).orElse(record);
    }

    public Optional<QaRecord> find(String qaId) {
        return qaRepository.findById(qaId);
    }

    public List<QaRecord> list(String ownerId, String reviewState, String riskLevel,
                               boolean refusedOnly, boolean suspectedOnly, int limit) {
        return qaRepository.list(ownerId, reviewState, riskLevel, refusedOnly, suspectedOnly, limit);
    }

    /** 高风险检索日志：拒答 / 有检索侧风险信号 / 高风险答案，人工每周优先过这批。 */
    public List<QaRecord> riskLog(String ownerId, int limit) {
        List<QaRecord> all = qaRepository.list(ownerId, null, null, false, false, Math.max(limit * 4, 200));
        List<QaRecord> risky = new ArrayList<>();
        for (QaRecord record : all) {
            if (isRisky(record)) {
                risky.add(record);
                if (risky.size() >= limit) {
                    break;
                }
            }
        }
        return risky;
    }

    private boolean isRisky(QaRecord record) {
        // 被隐式信号标为「疑似错误」的样本同样要进清单（用户没点踩也能被发现）
        if (record.isRefused() || record.isSuspectedError() || "HIGH".equals(record.getRiskLevel())) {
            return true;
        }
        String retrieval = record.getRetrieval();
        return retrieval != null && (retrieval.contains(RetrievalRelevanceEvaluator.NO_RESULT)
                || retrieval.contains(RetrievalRelevanceEvaluator.LOW_RELEVANT_COUNT)
                || retrieval.contains(RetrievalRelevanceEvaluator.LOW_TOP_SCORE)
                || retrieval.contains(RetrievalRelevanceEvaluator.REWRITE_EXHAUSTED)
                || retrieval.contains(RetrievalRelevanceEvaluator.PATH_DIVERGENT)
                || retrieval.contains(RetrievalRelevanceEvaluator.MODEL_REFUSED));
    }

    /** 检索判定充分但模型答不了：在检索侧评估上补一个「知识库盲区」信号。 */
    private RetrievalRelevanceEvaluator.RelevanceAssessment withModelRefused(
            RetrievalRelevanceEvaluator.RelevanceAssessment base) {
        List<String> signals = new ArrayList<>(base.signals());
        if (!signals.contains(RetrievalRelevanceEvaluator.MODEL_REFUSED)) {
            signals.add(RetrievalRelevanceEvaluator.MODEL_REFUSED);
        }
        return new RetrievalRelevanceEvaluator.RelevanceAssessment(base.candidateCount(), base.relevantCount(),
                base.topScore(), base.meanScore(), base.sufficient(), List.copyOf(signals),
                base.reason() + "；检索判定可答但模型答复无法作答（疑似知识库盲区）");
    }

    public void updateReviewState(String qaId, String reviewState) {
        require(qaId);
        qaRepository.updateReviewState(qaId, reviewState == null || reviewState.isBlank()
                ? "REVIEWED" : reviewState.trim().toUpperCase());
    }

    public Map<String, Object> stats(String ownerId) {
        return qaRepository.stats(ownerId);
    }

    public QaRecord require(String qaId) {
        return qaRepository.findById(qaId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "问答记录不存在: " + qaId));
    }

    // ──────────────────── 检索与改写 ────────────────────

    /** 检索 → 不充分则改写重试，最多 {@code maxRewriteRounds} 轮；两轮仍不充分即打「改写耗尽」信号。 */
    private RetrievalOutcome retrieveWithRetry(String question, Map<String, String> filters, int limit) {
        List<String> queries = new ArrayList<>();
        String current = question;
        queries.add(current);
        int round = 0;
        List<AdvancedRagService.RetrievalResult> hits = List.of();
        RetrievalRelevanceEvaluator.RelevanceAssessment assessment =
                relevanceEvaluator.assess(hits, List.of(), 0);
        while (true) {
            hits = safeRetrieve(current, filters, limit);
            List<HybridSearchService.HybridSearchResult> hybridHits = safeHybrid(current, filters, limit);
            assessment = relevanceEvaluator.assess(hits, hybridHits, round);
            if (assessment.sufficient() || round >= properties.getMaxRewriteRounds()) {
                break;
            }
            round++;
            String rewritten = rewriteQuery(question, hits, round);
            if (rewritten.isBlank() || rewritten.equals(current)) {
                // 改写没有产生新查询，标记改写耗尽后退出，避免空转
                assessment = relevanceEvaluator.assess(hits, hybridHits, properties.getMaxRewriteRounds());
                break;
            }
            current = rewritten;
            queries.add(current);
        }
        if (!assessment.sufficient() && !assessment.signals().contains(RetrievalRelevanceEvaluator.REWRITE_EXHAUSTED)) {
            assessment = relevanceEvaluator.assess(hits, List.of(), properties.getMaxRewriteRounds());
        }
        return new RetrievalOutcome(hits, assessment, List.copyOf(queries), round);
    }

    private List<AdvancedRagService.RetrievalResult> safeRetrieve(String query, Map<String, String> filters, int limit) {
        try {
            return advancedRagService.retrieveAndRerank(query, filters, limit);
        } catch (Exception e) {
            log.warn("[KbQa] 检索失败，按空结果处理: {}", e.getMessage());
            return List.of();
        }
    }

    private List<HybridSearchService.HybridSearchResult> safeHybrid(String query, Map<String, String> filters, int limit) {
        try {
            return hybridSearchService.search(query, Math.max(limit, properties.getPathCompareSize()), filters);
        } catch (Exception e) {
            log.debug("[KbQa] 混合检索（分歧检测用）失败: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 查询改写：优先用 {@link AdvancedRagService#rewriteQuery}（HyDE / Multi-query 变体），
     * 变体用尽后退化为「伪相关反馈」——把命中最强证据的标题拼进查询，提升二次召回。
     */
    private String rewriteQuery(String original, List<AdvancedRagService.RetrievalResult> hits, int round) {
        List<String> variants = advancedRagService.rewriteQuery(original);
        if (variants.size() > round) {
            String candidate = variants.get(round);
            if (candidate != null && !candidate.isBlank() && !candidate.equalsIgnoreCase(original)) {
                return candidate;
            }
        }
        if (!hits.isEmpty()) {
            Map<String, String> metadata = hits.get(0).metadata();
            String title = metadata == null ? null : metadata.get("title");
            if (title != null && !title.isBlank() && !original.contains(title)) {
                return original + " " + title;
            }
        }
        return "";
    }

    // ──────────────────── 风险汇总与序列化 ────────────────────

    /**
     * 综合风险：
     * <ul>
     *   <li>HIGH —— 引用不实，或忠实度判为高风险（生成幻觉）；</li>
     *   <li>MEDIUM —— 拒答（知识库盲区）、忠实度中风险、命中检索侧风险信号；</li>
     *   <li>LOW —— 少量部分支持；NONE —— 全部支持且引用合法。</li>
     * </ul>
     */
    private String aggregateRisk(CitationGuard.CitationCheck citationCheck,
                                 AnswerFaithfulnessChecker.FaithfulnessReport faithfulness,
                                 RetrievalRelevanceEvaluator.RelevanceAssessment assessment,
                                 boolean refused) {
        if (!refused && !citationCheck.valid()) {
            return "HIGH";
        }
        if ("HIGH".equals(faithfulness.riskLevel())) {
            return "HIGH";
        }
        if (refused || "MEDIUM".equals(faithfulness.riskLevel())) {
            return "MEDIUM";
        }
        if (assessment != null && !assessment.signals().isEmpty()) {
            return "MEDIUM";
        }
        if ("LOW".equals(faithfulness.riskLevel())) {
            return "LOW";
        }
        return "NONE";
    }

    private String reviewStateOf(String riskLevel) {
        return switch (riskLevel) {
            case "HIGH" -> "NEEDS_REVIEW";
            case "MEDIUM", "LOW" -> "SAMPLED";
            default -> "NONE";
        };
    }

    private List<Map<String, Object>> citations(List<AdvancedRagService.RetrievalResult> hits) {
        List<Map<String, Object>> citations = new ArrayList<>(hits.size());
        for (int i = 0; i < hits.size(); i++) {
            AdvancedRagService.RetrievalResult hit = hits.get(i);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("index", i + 1);
            entry.put("chunkId", hit.chunkId());
            entry.put("title", hit.metadata() == null ? null : hit.metadata().get("title"));
            entry.put("docId", hit.metadata() == null ? null : hit.metadata().get("docId"));
            entry.put("score", TextSimilarity.round3(hit.score()));
            citations.add(entry);
        }
        return citations;
    }

    private Map<String, Object> retrievalPayload(RetrievalOutcome outcome) {
        Map<String, Object> payload = new LinkedHashMap<>();
        RetrievalRelevanceEvaluator.RelevanceAssessment assessment = outcome.assessment();
        payload.put("candidateCount", assessment.candidateCount());
        payload.put("relevantCount", assessment.relevantCount());
        payload.put("topScore", assessment.topScore());
        payload.put("meanScore", assessment.meanScore());
        payload.put("sufficient", assessment.sufficient());
        payload.put("signals", assessment.signals());
        payload.put("reason", assessment.reason());
        payload.put("rewriteRounds", outcome.rewriteRounds());
        payload.put("queries", outcome.queries());
        return payload;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            log.warn("[KbQa] 序列化失败: {}", e.getMessage());
            return null;
        }
    }
}
