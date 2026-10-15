package com.contentops.common.knowledge.qa;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 答案-证据一致性校验（第 2 类错误：生成幻觉）—— **只标记，不自动修正**。
 *
 * <p>做法：把答案拆成句子/步骤，对每条结论用「字二元组相似度 + 数字一致性」判定它是否被
 * <b>本次检索到的证据</b>支持，输出 {@code 支持 / 部分支持 / 不支持} 三档；
 * 不支持占比超阈值 → 高风险（{@code NEEDS_REVIEW}），部分支持占比高 → 抽样（{@code SAMPLED}）。
 *
 * <p>先规则初筛（零成本、确定性强），模糊带与高风险样本可选送 {@link FaithfulnessLlmJudge} 复核，
 * 避免在线全量跑 LLM judge 的成本。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AnswerFaithfulnessChecker {

    public static final String SUPPORTED = "SUPPORTED";
    public static final String PARTIAL = "PARTIAL";
    public static final String UNSUPPORTED = "UNSUPPORTED";

    /** 答案切分：句末标点、换行、分号均视为结论边界 */
    private static final String SENTENCE_SPLIT = "[。！？!?；;\\n]+";
    /** 列表/标题前缀（1. / 2) / - / • / #）在核查前剥掉 */
    private static final String LIST_PREFIX =
            "^\\s*(?:[#>*\\-•]|\\d+[.、)）]|[（(]?[一二三四五六七八九十][)）.、])\\s*";
    /** 引用标注（[1] / ［1-3］）不参与事实核查：否则会被当成「数字」误判 */
    private static final java.util.regex.Pattern CITATION =
            java.util.regex.Pattern.compile("[\\[［][^\\]］]*[\\]］]");
    /** 客套/拒答/转人工类句子不是「事实结论」，不参与核查 */
    private static final List<String> NON_CLAIM_MARKERS = List.of(
            "抱歉", "没有找到", "未找到", "没有相关", "无法确认", "无法回答", "暂不确定", "不确定",
            "建议你咨询", "转人工", "谢谢", "请稍等", "希望这些", "还有其它", "还有其他",
            "帮你确认", "帮你查", "帮忙确认", "我再确认", "核实一下");

    private final QaProperties properties;

    /**
     * 单条结论的判定。
     *
     * @param claim          结论原文
     * @param verdict        支持 / 部分支持 / 不支持
     * @param score          与最匹配证据的字二元组相似度
     * @param evidenceIndex  最匹配证据的序号（1 起；无证据为 0）
     * @param ruleConfidence 规则判定置信度（越低越模糊，值得送模型复核）
     * @param reason         判定说明
     */
    public record ClaimVerdict(String claim, String verdict, double score, int evidenceIndex,
                               double ruleConfidence, String reason) {
    }

    /**
     * 忠实度报告。
     *
     * @param claims           各结论判定
     * @param claimCount       参与核查的结论数
     * @param supportedRatio   支持占比
     * @param partialRatio     部分支持占比
     * @param unsupportedRatio 不支持占比
     * @param riskLevel        NONE / LOW / MEDIUM / HIGH
     * @param reviewState      NONE / SAMPLED / NEEDS_REVIEW
     * @param summary          人类可读结论
     */
    public record FaithfulnessReport(List<ClaimVerdict> claims, int claimCount, double supportedRatio,
                                     double partialRatio, double unsupportedRatio, String riskLevel,
                                     String reviewState, String summary) {
    }

    /**
     * 核查答案与证据的一致性。
     *
     * @param answer    模型答案
     * @param evidences 本次检索到的证据正文（顺序与答案引用编号一致）
     * @param refused   答案是否为拒答（拒答不做事实核查）
     */
    public FaithfulnessReport check(String answer, List<String> evidences, boolean refused) {
        if (refused) {
            return new FaithfulnessReport(List.of(), 0, 0, 0, 0, "NONE", "NONE", "拒答样本不做事实一致性核查");
        }
        QaProperties.Faithfulness cfg = properties.getFaithfulness();
        if (!cfg.isEnabled()) {
            return new FaithfulnessReport(List.of(), 0, 0, 0, 0, "NONE", "NONE", "忠实度校验已关闭");
        }
        List<String> claims = splitClaims(answer, cfg.getMinClaimChars());
        if (claims.isEmpty()) {
            return new FaithfulnessReport(List.of(), 0, 0, 0, 0, "NONE", "NONE", "答案中没有可核查的事实结论");
        }
        List<String> evidenceList = evidences == null ? List.of() : evidences;

        List<ClaimVerdict> verdicts = new ArrayList<>(claims.size());
        int supported = 0;
        int partial = 0;
        int unsupported = 0;
        for (String claim : claims) {
            ClaimVerdict verdict = judge(claim, evidenceList, cfg);
            verdicts.add(verdict);
        }
        return summarize(verdicts, false);
    }

    /**
     * 由结论判定汇总出比例与风险等级。
     * 抽出成公开方法，便于模型复核（{@link FaithfulnessLlmJudge}）改写判定后重新汇总。
     */
    public FaithfulnessReport summarize(List<ClaimVerdict> verdicts, boolean refused) {
        if (refused) {
            return new FaithfulnessReport(List.of(), 0, 0, 0, 0, "NONE", "NONE", "拒答样本不做事实一致性核查");
        }
        List<ClaimVerdict> claims = verdicts == null ? List.of() : verdicts;
        if (claims.isEmpty()) {
            return new FaithfulnessReport(List.of(), 0, 0, 0, 0, "NONE", "NONE", "答案中没有可核查的事实结论");
        }
        int supported = 0;
        int partial = 0;
        int unsupported = 0;
        for (ClaimVerdict verdict : claims) {
            if (SUPPORTED.equals(verdict.verdict())) {
                supported++;
            } else if (PARTIAL.equals(verdict.verdict())) {
                partial++;
            } else {
                unsupported++;
            }
        }
        QaProperties.Faithfulness cfg = properties.getFaithfulness();
        int total = claims.size();
        double supportedRatio = TextSimilarity.round3((double) supported / total);
        double partialRatio = TextSimilarity.round3((double) partial / total);
        double unsupportedRatio = TextSimilarity.round3((double) unsupported / total);

        String riskLevel;
        if (unsupportedRatio >= cfg.getUnsupportedRatioThreshold()) {
            riskLevel = "HIGH";
        } else if (unsupportedRatio + partialRatio > cfg.getPartialRatioThreshold()) {
            riskLevel = "MEDIUM";
        } else if (unsupported > 0 || partial > 0) {
            riskLevel = "LOW";
        } else {
            riskLevel = "NONE";
        }
        String reviewState;
        if ("HIGH".equals(riskLevel)) {
            reviewState = "NEEDS_REVIEW";
        } else if ("MEDIUM".equals(riskLevel) || "LOW".equals(riskLevel)) {
            reviewState = "SAMPLED";
        } else {
            reviewState = "NONE";
        }
        String summary = "结论 " + total + " 条：支持 " + supported + " / 部分支持 " + partial
                + " / 不支持 " + unsupported + "（不支持占比 " + unsupportedRatio + "）";
        FaithfulnessReport report = new FaithfulnessReport(List.copyOf(claims), total, supportedRatio,
                partialRatio, unsupportedRatio, riskLevel, reviewState, summary);
        if ("HIGH".equals(riskLevel)) {
            log.warn("[QaFaithfulness] HIGH risk: {}", summary);
        } else {
            log.debug("[QaFaithfulness] {}", summary);
        }
        return report;
    }

    /** 按句/步骤切分答案，过滤非事实结论（客套、拒答、过短碎片）。 */
    public List<String> splitClaims(String answer, int minChars) {
        List<String> claims = new ArrayList<>();
        if (answer == null || answer.isBlank()) {
            return claims;
        }
        for (String raw : answer.split(SENTENCE_SPLIT)) {
            String sentence = raw.replaceAll(LIST_PREFIX, "").trim();
            if (sentence.length() < Math.max(1, minChars)) {
                continue;
            }
            if (isNonClaim(sentence)) {
                continue;
            }
            claims.add(sentence);
        }
        return claims;
    }

    private boolean isNonClaim(String sentence) {
        for (String marker : NON_CLAIM_MARKERS) {
            if (sentence.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 单条结论判定：先找最相似证据，再看数字是否被该证据支持。
     * 数字是幻觉高发区（不存在的菜单序号、错误的时长/价格），出现证据里没有的数字时最多判「部分支持」。
     */
    private ClaimVerdict judge(String claim, List<String> evidences, QaProperties.Faithfulness cfg) {
        // 引用标注不是事实内容，先剥掉再比对，避免 [1] 被当作「证据里没有的数字」
        String scoringClaim = stripCitations(claim);
        if (evidences.isEmpty()) {
            return new ClaimVerdict(claim, UNSUPPORTED, 0, 0, 0.95, "本次检索没有任何证据可对照");
        }
        double best = 0;
        int bestIndex = 0;
        for (int i = 0; i < evidences.size(); i++) {
            double score = TextSimilarity.containment(scoringClaim, evidences.get(i));
            if (score > best) {
                best = score;
                bestIndex = i + 1;
            }
        }
        best = TextSimilarity.round3(best);
        Set<String> claimNumbers = TextSimilarity.numbers(scoringClaim);
        boolean numbersSupported = true;
        if (!claimNumbers.isEmpty() && bestIndex > 0) {
            Set<String> evidenceNumbers = TextSimilarity.numbers(evidences.get(bestIndex - 1));
            numbersSupported = evidenceNumbers.containsAll(claimNumbers);
        }

        if (best >= cfg.getSupportThreshold()) {
            if (numbersSupported) {
                double confidence = Math.min(1, 0.6 + (best - cfg.getSupportThreshold())
                        / Math.max(0.0001, 1 - cfg.getSupportThreshold()) * 0.4);
                return new ClaimVerdict(claim, SUPPORTED, best, bestIndex,
                        TextSimilarity.round3(confidence), "与第 " + bestIndex + " 段证据高度一致");
            }
            return new ClaimVerdict(claim, PARTIAL, best, bestIndex, 0.5,
                    "措辞与第 " + bestIndex + " 段证据相近，但出现证据中没有的数字 " + claimNumbers);
        }
        if (best >= cfg.getPartialThreshold()) {
            String reason = numbersSupported
                    ? "只有部分内容能在第 " + bestIndex + " 段证据里找到，疑似加戏"
                    : "只有部分内容能在第 " + bestIndex + " 段证据里找到，且数字 " + claimNumbers + " 未被证据支持";
            return new ClaimVerdict(claim, PARTIAL, best, bestIndex, 0.45, reason);
        }
        return new ClaimVerdict(claim, UNSUPPORTED, best, bestIndex, 0.9,
                "证据覆盖率过低（最高 " + best + "），疑似模型基于通用知识编造");
    }

    /** 去掉引用标注与其它方括号内容，得到参与相似度/数字核对的纯文本。 */
    private static String stripCitations(String text) {
        return text == null ? "" : CITATION.matcher(text).replaceAll(" ").trim();
    }
}
