package com.contentops.comment;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 场景客服回复 Agent：按 {@link ReplyAgentProfile} 生成回复草稿，并交给
 * {@link ReplyComplianceChecker} 做「只标记不修正」的忠实度校验。
 *
 * <p>内置场景种子见 {@link ReplyAgentProfileService}：电商带货 / 课程客服 / 通用客服。
 * 定制新场景只需要新增一条 profile（商品事实、禁止承诺、转人工规则、语气都走配置），无需改代码。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScenarioReplyAgent {

    private final @Qualifier("formattingChatModel") ChatModel chatModel;
    private final ReplyComplianceChecker complianceChecker;
    private final ObjectMapper objectMapper;

    /** 草稿结果：回复 + 来源配置 + 置信度 + 自动标记 + 复核状态。 */
    public record DraftResult(String reply, String profileId, String scenario, double confidence,
                              List<ReplyComplianceChecker.ReplyFlag> flags, String reviewState, String source) {
    }

    /**
     * 生成回复草稿。
     *
     * @param profile        场景配置（必需）
     * @param commentContent 用户评论原文
     * @param history        对话历史（role/content）
     * @param userMessage    本次要回应的话（通常等于评论内容或运营输入）
     * @param productContext 临时补充事实（如具体商品信息，可为空）
     */
    public DraftResult draft(ReplyAgentProfile profile, String commentContent,
                             List<Map<String, String>> history, String userMessage,
                             String productContext) {
        String reply;
        String source;
        try {
            reply = chatModel.chat(buildPrompt(profile, commentContent, history, userMessage, productContext)).trim();
            source = "llm";
            if (reply.isBlank()) {
                reply = template(profile, commentContent);
                source = "template";
            }
        } catch (Exception e) {
            log.warn("[ReplyAgent] 模型生成失败，使用模板兜底: scenario={}, err={}",
                    profile == null ? "-" : profile.getScenario(), e.getMessage());
            reply = template(profile, commentContent);
            source = "template";
        }

        ReplyComplianceChecker.CheckResult check =
                complianceChecker.check(reply, profile, commentContent, productContext);
        log.info("[ReplyAgent] 草稿完成: scenario={}, source={}, flags={}, reviewState={}",
                profile == null ? "-" : profile.getScenario(), source, check.flags().size(), check.reviewState());
        return new DraftResult(reply, profile == null ? null : profile.getProfileId(),
                profile == null ? null : profile.getScenario(), check.confidence(), check.flags(),
                check.reviewState(), source);
    }

    /** 校验已有草稿（用于对历史草稿补做标记或人工修改后复检）。 */
    public ReplyComplianceChecker.CheckResult recheck(String reply, ReplyAgentProfile profile,
                                                      String commentContent, String productContext) {
        return complianceChecker.check(reply, profile, commentContent, productContext);
    }

    private String buildPrompt(ReplyAgentProfile profile, String commentContent,
                               List<Map<String, String>> history, String userMessage, String productContext) {
        String scenarioPrompt = profile != null && profile.getSystemPrompt() != null && !profile.getSystemPrompt().isBlank()
                ? profile.getSystemPrompt()
                : defaultPrompt(profile == null ? null : profile.getScenario());
        StringBuilder facts = new StringBuilder();
        if (profile != null && profile.getProductFacts() != null && !profile.getProductFacts().isBlank()) {
            facts.append(profile.getProductFacts().trim());
        }
        if (productContext != null && !productContext.isBlank()) {
            facts.append(facts.isEmpty() ? "" : "\n").append(productContext.trim());
        }

        StringBuilder sb = new StringBuilder();
        sb.append(scenarioPrompt).append('\n');
        sb.append("回复要求：\n");
        sb.append("- 语气：").append(profile != null && profile.getTone() != null
                ? profile.getTone() : "自然、真诚、口语化").append('\n');
        sb.append("- 长度：不超过 ").append(profile == null || profile.getReplyMaxChars() == null
                ? 120 : profile.getReplyMaxChars()).append(" 字\n");
        sb.append("- 只能依据下面的事实回答，事实里没有的信息（价格/优惠/时效/功效）不要编造，"
                + "可以说明「我帮你确认一下」\n");
        if (profile != null && profile.getForbiddenClaims() != null && !profile.getForbiddenClaims().isBlank()) {
            sb.append("- 禁止出现这些承诺：").append(profile.getForbiddenClaims()).append('\n');
        }
        if (profile != null && profile.getEscalateKeywords() != null && !profile.getEscalateKeywords().isBlank()) {
            sb.append("- 遇到这些情况要提示转人工：").append(profile.getEscalateKeywords()).append('\n');
        }
        sb.append("- 不要暴露你是 AI\n");
        sb.append("\n可用事实：\n").append(facts.isEmpty() ? "（暂无可引用事实，涉及具体信息时请引导用户咨询人工）"
                : facts).append('\n');
        sb.append("\n用户评论原文：").append(commentContent == null ? "" : commentContent).append('\n');
        if (history != null && !history.isEmpty()) {
            sb.append("\n对话历史：\n");
            for (Map<String, String> turn : history) {
                sb.append(turn.getOrDefault("role", "?")).append(": ")
                        .append(turn.getOrDefault("content", "")).append('\n');
            }
        }
        sb.append("\n需要回应的最新消息：").append(userMessage == null ? "" : userMessage).append('\n');
        sb.append("请直接输出回复内容本身，不要任何前缀或引号。");
        return sb.toString();
    }

    private String defaultPrompt(String scenario) {
        if ("COURSE".equalsIgnoreCase(scenario)) {
            return "你是一位课程顾问客服，负责解答课程内容、适合人群、学习方式与购买流程的问题。";
        }
        if ("ECOMMERCE".equalsIgnoreCase(scenario)) {
            return "你是一位电商带货客服，负责解答商品规格、发货、优惠与售后政策的问题，并适度引导下单。";
        }
        return "你是一位专业客服，负责解答用户疑问并给出可执行建议。";
    }

    /** 模板兜底：模型不可用时也要给出可用的草稿（同样会被标记复核）。 */
    private String template(ReplyAgentProfile profile, String commentContent) {
        String scenario = profile == null ? "GENERIC" : String.valueOf(profile.getScenario());
        if ("ECOMMERCE".equalsIgnoreCase(scenario)) {
            return "收到你的问题啦～具体信息我帮你和客服同事确认一下，稍后给你准确答复，"
                    + "急的话可以直接戳客服小姐姐哦！";
        }
        if ("COURSE".equalsIgnoreCase(scenario)) {
            return "谢谢关注～课程的具体安排我帮你核实一下再回复你，也可以先说说你的基础和目标，"
                    + "我看看哪个更适合你。";
        }
        return "收到你的评论～我确认一下具体情况再回复你，感谢耐心等待！";
    }
}