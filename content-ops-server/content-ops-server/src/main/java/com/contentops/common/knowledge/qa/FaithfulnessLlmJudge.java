package com.contentops.common.knowledge.qa;

import dev.langchain4j.model.chat.ChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 忠实度模型复核（可选，默认关闭）。
 *
 * <p>规则初筛便宜但粗糙，所以在两种情况下把结论送模型复核：
 * <ol>
 *   <li>结论的规则置信度落在模糊带（低于 {@code llmJudgeMinRuleConfidence}）；</li>
 *   <li>报告已判为高风险，需要二次确认再进人工清单。</li>
 * </ol>
 * 为避免成本失控，单次最多复核 {@code llmJudgeMaxClaims} 条；模型不可用时静默保留规则结论。
 */
@Slf4j
@Component
public class FaithfulnessLlmJudge {

    private final ChatModel chatModel;
    private final QaProperties properties;

    public FaithfulnessLlmJudge(@Qualifier("creativeChatModel") ChatModel chatModel, QaProperties properties) {
        this.chatModel = chatModel;
        this.properties = properties;
    }

    /** 对模糊/高风险结论做模型复核，返回替换后的判定（模型不可用或解析失败时保留原判定）。 */
    public List<AnswerFaithfulnessChecker.ClaimVerdict> reJudge(
            List<AnswerFaithfulnessChecker.ClaimVerdict> verdicts, List<String> evidences) {
        QaProperties.Faithfulness cfg = properties.getFaithfulness();
        if (!cfg.isLlmJudgeEnabled() || verdicts == null || verdicts.isEmpty()) {
            return verdicts;
        }
        List<AnswerFaithfulnessChecker.ClaimVerdict> output = new ArrayList<>(verdicts);
        int judged = 0;
        for (int i = 0; i < output.size() && judged < cfg.getLlmJudgeMaxClaims(); i++) {
            AnswerFaithfulnessChecker.ClaimVerdict verdict = output.get(i);
            if (verdict.ruleConfidence() > cfg.getLlmJudgeMinRuleConfidence()) {
                continue;
            }
            judged++;
            try {
                String reply = chatModel.chat(buildPrompt(verdict, evidences));
                String mapped = mapVerdict(reply);
                if (mapped != null) {
                    output.set(i, new AnswerFaithfulnessChecker.ClaimVerdict(verdict.claim(), mapped,
                            verdict.score(), verdict.evidenceIndex(), 0.8, "模型复核判定：" + mapped));
                }
            } catch (Exception e) {
                log.warn("[QaFaithfulness] LLM judge failed, keep rule verdict: {}", e.getMessage());
            }
        }
        return output;
    }

    private String buildPrompt(AnswerFaithfulnessChecker.ClaimVerdict verdict, List<String> evidences) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是事实核查员。只依据下面给出的证据，判断「待核查结论」是被支持、部分支持还是不支持。\n");
        sb.append("请只输出其中一个词：支持 / 部分支持 / 不支持。\n\n证据：\n");
        int index = 1;
        for (String evidence : evidences) {
            sb.append('[').append(index++).append("] ")
                    .append(evidence.length() > 600 ? evidence.substring(0, 600) : evidence).append('\n');
        }
        sb.append("\n待核查结论：").append(verdict.claim()).append('\n');
        return sb.toString();
    }

    private String mapVerdict(String reply) {
        if (reply == null) {
            return null;
        }
        String text = reply.trim();
        if (text.contains("部分支持")) {
            return AnswerFaithfulnessChecker.PARTIAL;
        }
        if (text.contains("不支持")) {
            return AnswerFaithfulnessChecker.UNSUPPORTED;
        }
        if (text.contains("支持")) {
            return AnswerFaithfulnessChecker.SUPPORTED;
        }
        return null;
    }
}
