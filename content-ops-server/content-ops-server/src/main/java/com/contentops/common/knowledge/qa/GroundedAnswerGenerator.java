package com.contentops.common.knowledge.qa;

import com.contentops.common.rag.AdvancedRagService;
import dev.langchain4j.model.chat.ChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 接地答案生成：只用本次检索到的证据作答，并要求标注 {@code [n]} 引用。
 *
 * <p>模型不可用/输出异常时降级为「抽取式答案」（直接引用得分最高的一段证据 + 合法引用编号），
 * 保证链路不中断，且引用天然真实。
 */
@Slf4j
@Component
public class GroundedAnswerGenerator {

    /** 模型主动拒答的标志词（知识库里确实没有答案时，模型应这样说，而不是硬编） */
    private static final List<String> REFUSAL_MARKERS = List.of(
            "没有找到", "未找到", "没有相关", "没有涉及", "无法提供", "无法回答", "不能回答",
            "知识库中没有", "资料中没有", "建议转人工", "建议您转人工", "无法确认");

    private final ChatModel chatModel;
    private final QaProperties properties;

    public GroundedAnswerGenerator(@Qualifier("creativeChatModel") ChatModel chatModel, QaProperties properties) {
        this.chatModel = chatModel;
        this.properties = properties;
    }

    /**
     * 生成结果。
     *
     * @param answer 答案正文
     * @param source llm（模型）/ template（抽取式兜底）/ refuse（拒答）
     */
    public record GeneratedAnswer(String answer, String source) {
    }

    /**
     * 依据证据生成答案。
     *
     * @param question 用户问题
     * @param evidence 本次检索结果（顺序即引用编号 1..n）
     */
    public GeneratedAnswer generate(String question, List<AdvancedRagService.RetrievalResult> evidence) {
        if (evidence == null || evidence.isEmpty()) {
            return new GeneratedAnswer(properties.getRefusalMessage(), "refuse");
        }
        try {
            String answer = chatModel.chat(buildPrompt(question, evidence));
            if (answer != null && !answer.isBlank()) {
                return new GeneratedAnswer(answer.trim(), "llm");
            }
            log.warn("[QaGenerate] 模型返回空内容，使用抽取式兜底");
        } catch (Exception e) {
            log.warn("[QaGenerate] 模型生成失败，使用抽取式兜底: {}", e.getMessage());
        }
        return new GeneratedAnswer(extractive(evidence), "template");
    }

    /** 生成提示词：强约束「只用证据、标引用、不确定就说不确定」。 */
    private String buildPrompt(String question, List<AdvancedRagService.RetrievalResult> evidence) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是知识库问答助手。请只依据下面给出的证据回答用户问题。\n");
        sb.append("规则：\n");
        sb.append("1. 每一条事实性陈述后面都必须标注来源编号，形如 [1]、[2]；\n");
        sb.append("2. 证据里没有的信息（步骤、菜单、参数、价格、时效）绝对不要编造；\n");
        sb.append("3. 证据不足以回答时，直接说明知识库中没有找到相关内容，并提示可以转人工，不要靠常识补全；\n");
        sb.append("4. 用中文回答，分点说明时保持简洁，不要输出与问题无关的内容。\n\n");
        sb.append("证据：\n");
        int index = 1;
        for (AdvancedRagService.RetrievalResult hit : evidence) {
            sb.append('[').append(index++).append("] ");
            String title = hit.metadata() == null ? null : hit.metadata().get("title");
            if (title != null && !title.isBlank()) {
                sb.append('《').append(title).append('》').append(' ');
            }
            sb.append(truncate(hit.content(), properties.getMaxEvidenceChars())).append('\n');
        }
        sb.append("\n用户问题：").append(question).append('\n');
        sb.append("请直接输出回答正文。");
        return sb.toString();
    }

    /** 抽取式兜底：引用得分最高的一段证据原文，引用编号合法。 */
    private String extractive(List<AdvancedRagService.RetrievalResult> evidence) {
        AdvancedRagService.RetrievalResult top = evidence.get(0);
        String title = top.metadata() == null ? null : top.metadata().get("title");
        StringBuilder sb = new StringBuilder();
        sb.append("根据知识库中");
        if (title != null && !title.isBlank()) {
            sb.append('《').append(title).append('》');
        }
        sb.append("的内容：").append(truncate(top.content(), 300)).append(" [1]");
        sb.append("\n以上为自动整理的初稿，如与你的场景不符，请补充细节或转人工核实。");
        return sb.toString();
    }

    /**
     * 答案是否为「模型主动拒答」。
     *
     * <p>这类答案没有引用是正常的（没有可依据的内容），不能按「引用不实」处理；
     * 但它说明检索层认为「找得到」而模型认为「答不了」，属于知识库盲区信号。
     */
    public boolean looksLikeRefusal(String answer) {
        if (answer == null || answer.isBlank()) {
            return true;
        }
        for (String marker : REFUSAL_MARKERS) {
            if (answer.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }
}
