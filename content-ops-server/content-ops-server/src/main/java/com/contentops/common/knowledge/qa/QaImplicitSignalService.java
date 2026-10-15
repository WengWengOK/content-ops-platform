package com.contentops.common.knowledge.qa;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 用户隐式信号归因（第 4 层）：不用等用户点踩，也能捕获一部分错误问答。
 *
 * <p>信号来源：
 * <ul>
 *   <li>{@code EXPLICIT_DISLIKE} —— 显式点踩；</li>
 *   <li>{@code DOUBT_REPHRASE} —— 用户追问「不对吧」「还是找不到」；</li>
 *   <li>{@code REPEAT_QUESTION} —— 时间窗内重复问同一问题；</li>
 *   <li>{@code CITATION_BOUNCE} —— 点开引用章节很快返回；</li>
 *   <li>{@code HANDOFF_CONFIRMED} —— 转人工后确认答案错误。</li>
 * </ul>
 *
 * <p>命中后只做两件事：写信号表 + 给问答记录打「疑似错误」标签（不修改答案内容）。
 * 这些记录是「训小分类器 / 补知识库」的样本来源。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QaImplicitSignalService {

    public static final String EXPLICIT_DISLIKE = "EXPLICIT_DISLIKE";
    public static final String DOUBT_REPHRASE = "DOUBT_REPHRASE";
    public static final String REPEAT_QUESTION = "REPEAT_QUESTION";
    public static final String CITATION_BOUNCE = "CITATION_BOUNCE";
    public static final String HANDOFF_CONFIRMED = "HANDOFF_CONFIRMED";

    /** 置信度达到该值的信号，直接把问答标为「疑似错误」 */
    private static final double SUSPECT_THRESHOLD = 0.7;

    private final QaProperties properties;
    private final QaRepository qaRepository;
    private final QaSignalRepository signalRepository;

    /**
     * 记录一次信号（统一出口）。
     *
     * @param qaId        被评价的问答
     * @param ownerId     租户
     * @param type        信号类型
     * @param confidence  可信度 0~1
     * @param detail      说明
     * @param relatedQaId 关联的上一条问答（追问/重复提问时有值）
     */
    public QaSignal emit(String qaId, String ownerId, String type, double confidence,
                         String detail, String relatedQaId) {
        QaSignal signal = QaSignal.builder()
                .signalId("qas-" + UUID.randomUUID())
                .qaId(qaId)
                .ownerId(ownerId)
                .signalType(type)
                .confidence(TextSimilarity.round3(confidence))
                .detail(detail)
                .relatedQaId(relatedQaId)
                .createdAt(LocalDateTime.now())
                .build();
        signalRepository.insert(signal);
        if (confidence >= SUSPECT_THRESHOLD) {
            qaRepository.markSuspected(qaId, type + "：" + detail);
        }
        log.info("[QaSignal] type={}, qaId={}, confidence={}, detail={}", type, qaId, confidence, detail);
        return signal;
    }

    /** 显式反馈：用户点踩（或点「没解决」）。 */
    public QaSignal explicitDislike(String qaId, String ownerId, String reason) {
        return emit(qaId, ownerId, EXPLICIT_DISLIKE, 0.9,
                reason == null || reason.isBlank() ? "用户反馈答案没解决问题" : reason, null);
    }

    /** 引用点击：停留过短说明「点开引用也没帮上忙」。 */
    public QaSignal citationClick(String qaId, String ownerId, long dwellMs) {
        if (!properties.getImplicit().isEnabled()) {
            return null;
        }
        int threshold = properties.getImplicit().getCitationDwellMs();
        if (dwellMs >= threshold) {
            return null;
        }
        return emit(qaId, ownerId, CITATION_BOUNCE, 0.55,
                "引用章节停留 " + dwellMs + "ms（低于 " + threshold + "ms），用户可能没在引用里找到答案", null);
    }

    /** 转人工结果回填：实施/客服确认答案错误时，就是一条高置信度错误样本。 */
    public QaSignal handoff(String qaId, String ownerId, boolean answerWasWrong, String note) {
        if (!answerWasWrong) {
            return null;
        }
        return emit(qaId, ownerId, HANDOFF_CONFIRMED, 0.95,
                note == null || note.isBlank() ? "转人工后确认原答案有误" : note, null);
    }

    /**
     * 新问题落库后立即归因：质疑词 + 时间窗内重复提问。
     *
     * @param newQaId  本次问答
     * @param question 本次问题
     * @param ownerId  租户
     * @return 本次产生的信号
     */
    public List<QaSignal> detectOnNewQuestion(String newQaId, String question, String ownerId) {
        QaProperties.Implicit cfg = properties.getImplicit();
        if (!cfg.isEnabled() || question == null || question.isBlank()) {
            return List.of();
        }
        List<QaSignal> created = new ArrayList<>();
        LocalDateTime since = LocalDateTime.now().minusMinutes(cfg.getRepeatWindowMinutes());
        List<QaRecord> recent = qaRepository.findRecent(ownerId, since, cfg.getRepeatLookback());
        QaRecord previous = recent.isEmpty() ? null : recent.get(0);

        String marker = matchedDoubtMarker(question, cfg.getDoubtMarkers());
        if (marker != null) {
            created.add(emit(newQaId, ownerId, DOUBT_REPHRASE, 0.8,
                    "用户追问出现质疑词「" + marker + "」，上一条答案可能未被采纳",
                    previous == null ? null : previous.getQaId()));
            if (previous != null) {
                qaRepository.markSuspected(previous.getQaId(), "被后续追问质疑：" + marker);
            }
        }

        QaRecord duplicate = findDuplicate(question, recent, newQaId, cfg.getRepeatSimilarity());
        if (duplicate != null) {
            created.add(emit(newQaId, ownerId, REPEAT_QUESTION, 0.75,
                    "与 " + cfg.getRepeatWindowMinutes() + " 分钟内的历史问题高度重复，上次可能没答到点上",
                    duplicate.getQaId()));
            qaRepository.markSuspected(duplicate.getQaId(), "短时间内被重复提问");
        }
        return created;
    }

    public List<QaSignal> signalsOf(String qaId) {
        return signalRepository.listByQa(qaId);
    }

    public List<Map<String, Object>> typeStats(String ownerId) {
        return signalRepository.typeStats(ownerId);
    }

    private String matchedDoubtMarker(String question, List<String> markers) {
        for (String marker : markers) {
            if (question.contains(marker)) {
                return marker;
            }
        }
        return null;
    }

    private QaRecord findDuplicate(String question, List<QaRecord> recent, String newQaId, double threshold) {
        QaRecord best = null;
        double bestScore = 0;
        for (QaRecord record : recent) {
            if (record.getQaId() == null || record.getQaId().equals(newQaId)) {
                continue;
            }
            double score = TextSimilarity.jaccard(question, record.getQuestion());
            if (score > bestScore) {
                bestScore = score;
                best = record;
            }
        }
        return bestScore >= threshold ? best : null;
    }
}
