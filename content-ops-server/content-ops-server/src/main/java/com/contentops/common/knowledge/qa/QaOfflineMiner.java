package com.contentops.common.knowledge.qa;

import com.contentops.common.observability.LlmEvalRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 离线自动挖掘（第 5 层）：聚类找盲区 + 把错误样本并入评测集做回归。
 *
 * <p>线上日志积累后：
 * <ul>
 *   <li>对拒答 query 聚类 → 知识库盲区清单；</li>
 *   <li>对「引用失败 / 忠实度低 / 疑似错误」样本聚类 → 判断是切分问题、检索问题还是 Prompt 问题；</li>
 *   <li>把新发现的错误样本提升进 {@code contentops_llm_eval_case}，每次改检索或 Prompt 都跑回归。</li>
 * </ul>
 *
 * <p>聚类用字二元组 Jaccard + 并查集，无模型依赖、结果可复现（生产环境可替换为向量聚类）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QaOfflineMiner {

    public static final String SCOPE_REFUSED = "REFUSED";
    public static final String SCOPE_HIGH_RISK = "HIGH_RISK";
    public static final String SCOPE_INVALID_CITATION = "INVALID_CITATION";
    public static final String SCOPE_SUSPECTED = "SUSPECTED";
    public static final String SCOPE_ALL = "ALL";

    /** 回归评测集使用的 stage 名 */
    public static final String EVAL_STAGE = "kb-qa";

    private final QaRepository qaRepository;
    private final QaSignalRepository signalRepository;
    private final LlmEvalRepository llmEvalRepository;

    /**
     * 一个聚类簇（= 一类问题 / 一块知识库盲区）。
     *
     * @param clusterId       簇号（按样本数降序重编号）
     * @param size            样本数
     * @param sampleQuestions 代表性问题（最多 5 条）
     * @param qaIds           簇内问答 ID
     * @param signals         簇内命中的质量信号
     */
    public record QaCluster(int clusterId, int size, List<String> sampleQuestions,
                            List<String> qaIds, List<String> signals) {
    }

    /**
     * 聚类。
     *
     * @param ownerId   租户
     * @param scope     REFUSED / HIGH_RISK / INVALID_CITATION / SUSPECTED / ALL
     * @param limit     取样上限
     * @param threshold 相似度阈值（为空用 0.35）
     * @return 按样本数降序的簇列表
     */
    public List<QaCluster> cluster(String ownerId, String scope, int limit, Double threshold) {
        double sim = threshold == null || threshold <= 0 ? 0.3 : threshold;
        List<QaRecord> records = fetchScope(ownerId, scope, limit);
        int n = records.size();
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) {
            parent[i] = i;
        }
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                double score = TextSimilarity.jaccard(records.get(i).getQuestion(), records.get(j).getQuestion());
                if (score >= sim) {
                    union(parent, i, j);
                }
            }
        }
        Map<Integer, List<Integer>> groups = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            groups.computeIfAbsent(find(parent, i), k -> new ArrayList<>()).add(i);
        }
        List<QaCluster> clusters = new ArrayList<>(groups.size());
        for (List<Integer> members : groups.values()) {
            List<String> samples = new ArrayList<>();
            List<String> ids = new ArrayList<>();
            List<String> signals = new ArrayList<>();
            for (Integer index : members) {
                QaRecord record = records.get(index);
                ids.add(record.getQaId());
                if (samples.size() < 5) {
                    samples.add(record.getQuestion());
                }
                String signal = dominantSignal(record);
                if (signal != null && !signals.contains(signal)) {
                    signals.add(signal);
                }
            }
            clusters.add(new QaCluster(0, members.size(), List.copyOf(samples),
                    List.copyOf(ids), List.copyOf(signals)));
        }
        clusters.sort(Comparator.comparingInt(QaCluster::size).reversed());
        List<QaCluster> renumbered = new ArrayList<>(clusters.size());
        for (int i = 0; i < clusters.size(); i++) {
            QaCluster cluster = clusters.get(i);
            renumbered.add(new QaCluster(i + 1, cluster.size(), cluster.sampleQuestions(),
                    cluster.qaIds(), cluster.signals()));
        }
        log.info("[QaMiner] scope={}, samples={}, clusters={}", scope, n, renumbered.size());
        return renumbered;
    }

    /**
     * 把错误样本提升进评测集，作为回归基线。
     *
     * @param ownerId 租户
     * @param qaIds   要提升的问答
     * @return 统计信息
     */
    public Map<String, Object> promote(String ownerId, List<String> qaIds) {
        List<String> ids = qaIds == null ? List.of() : qaIds;
        int promoted = 0;
        for (String qaId : ids) {
            QaRecord record = qaRepository.findById(qaId).orElse(null);
            if (record == null) {
                continue;
            }
            llmEvalRepository.insertCase("kbqa-" + qaId, EVAL_STAGE,
                    truncate(record.getQuestion(), 200),
                    record.getQuestion(),
                    "【知识库盲区/错误样本】待人工补充正确回答后作为回归基线"
                            + (record.getAnswer() == null ? ""
                            : "（原答案：" + truncate(record.getAnswer(), 200) + "）"),
                    Timestamp.valueOf(LocalDateTime.now()));
            promoted++;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("requested", ids.size());
        result.put("promoted", promoted);
        result.put("stage", EVAL_STAGE);
        log.info("[QaMiner] promote to eval set: requested={}, promoted={}", ids.size(), promoted);
        return result;
    }

    /** 盲区总览：各范围样本数 + 簇数 + 信号类型分布。 */
    public Map<String, Object> report(String ownerId, int limit) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("totalCount", fetchScope(ownerId, SCOPE_ALL, limit).size());
        report.put("refusedCount", fetchScope(ownerId, SCOPE_REFUSED, limit).size());
        report.put("suspectedCount", fetchScope(ownerId, SCOPE_SUSPECTED, limit).size());
        report.put("refusedClusters", cluster(ownerId, SCOPE_REFUSED, limit, null).size());
        report.put("highRiskClusters", cluster(ownerId, SCOPE_HIGH_RISK, limit, null).size());
        report.put("signalTypeStats", signalRepository.typeStats(ownerId));
        return report;
    }

    private List<QaRecord> fetchScope(String ownerId, String scope, int limit) {
        String normalized = scope == null ? SCOPE_ALL : scope.trim().toUpperCase();
        return switch (normalized) {
            case SCOPE_REFUSED -> qaRepository.list(ownerId, null, null, true, false, limit);
            case SCOPE_SUSPECTED -> qaRepository.list(ownerId, null, null, false, true, limit);
            case SCOPE_HIGH_RISK -> qaRepository.list(ownerId, null, "HIGH", false, false, limit);
            case SCOPE_INVALID_CITATION -> filterInvalidCitation(
                    qaRepository.list(ownerId, null, null, false, false, limit));
            default -> qaRepository.list(ownerId, null, null, false, false, limit);
        };
    }

    /** 「引用不实」样本：citation_check JSON 里 valid=false 且不是拒答。 */
    private List<QaRecord> filterInvalidCitation(List<QaRecord> records) {
        List<QaRecord> filtered = new ArrayList<>();
        for (QaRecord record : records) {
            if (record.isRefused()) {
                continue;
            }
            String check = record.getCitationCheck();
            if (check != null && check.contains("\"valid\":false")) {
                filtered.add(record);
            }
        }
        return filtered;
    }

    private String dominantSignal(QaRecord record) {
        if (record.isRefused()) {
            return "REFUSED";
        }
        if (record.getCitationCheck() != null && record.getCitationCheck().contains("\"valid\":false")) {
            return "INVALID_CITATION";
        }
        if (record.getFaithfulness() != null && record.getFaithfulness().contains("\"riskLevel\":\"HIGH\"")) {
            return "LOW_FAITHFULNESS";
        }
        if (record.isSuspectedError()) {
            return "SUSPECTED_ERROR";
        }
        return null;
    }

    private static int find(int[] parent, int i) {
        while (parent[i] != i) {
            parent[i] = parent[parent[i]];
            i = parent[i];
        }
        return i;
    }

    private static void union(int[] parent, int a, int b) {
        int rootA = find(parent, a);
        int rootB = find(parent, b);
        if (rootA != rootB) {
            parent[rootB] = rootA;
        }
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }
}
