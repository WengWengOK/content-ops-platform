package com.contentops.common.knowledge.qa;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 引用校验（第 3 类：引用不实）。
 *
 * <p>生成答案时要求模型用 {@code [1][2]} 形式标注证据编号，本类校验：
 * <ol>
 *   <li>编号是否落在「本次检索结果」范围内 —— 引用了不存在的章节即视为不实引用；</li>
 *   <li>整段回答是否**一个引用都没有** —— 有事实陈述却零引用，同样是高风险；</li>
 *   <li>输出被引用的证据编号，便于前端做「点开引用看原文」。</li>
 * </ol>
 *
 * <p>注意：本类只做「引用是否真实存在」的判定；「引用真实存在但内容被加戏」由
 * {@link AnswerFaithfulnessChecker} 负责。
 */
@Slf4j
@Component
public class CitationGuard {

    /** 匹配 [1] / ［1］ / [1,2] / [1-3] 等引用写法 */
    private static final Pattern CITE = Pattern.compile("[\\[［]\\s*(\\d+(?:\\s*[-–~,，]\\s*\\d+)*)\\s*[\\]］]");
    private static final Pattern RANGE_SEP = Pattern.compile("[-–~]");

    /**
     * 引用校验结果。
     *
     * @param citationCount  答案中的引用标注个数
     * @param citedIndices   被引用的证据序号（1 起，去重保序）
     * @param invalidIndices 越界/不存在的引用序号
     * @param valid          是否通过校验
     * @param reason         判定原因
     */
    public record CitationCheck(int citationCount, List<Integer> citedIndices, List<Integer> invalidIndices,
                                boolean valid, String reason) {
    }

    /**
     * 校验答案里的引用。
     *
     * @param answer        模型生成的答案
     * @param evidenceCount 本次检索结果条数（合法编号为 1..evidenceCount）
     */
    public CitationCheck check(String answer, int evidenceCount) {
        String text = answer == null ? "" : answer;
        Set<Integer> cited = new LinkedHashSet<>();
        List<Integer> invalid = new ArrayList<>();
        int citationCount = 0;
        Matcher matcher = CITE.matcher(text);
        while (matcher.find()) {
            String group = matcher.group(1);
            for (String part : group.split("[,，]")) {
                String token = part.trim();
                if (token.isEmpty()) {
                    continue;
                }
                if (RANGE_SEP.matcher(token).find()) {
                    // 区间引用：[1-3]
                    String[] bounds = RANGE_SEP.split(token);
                    int from = parse(bounds[0]);
                    int to = bounds.length > 1 ? parse(bounds[1]) : from;
                    if (from <= 0 || to <= 0) {
                        continue;
                    }
                    for (int i = Math.min(from, to); i <= Math.max(from, to); i++) {
                        citationCount++;
                        if (i > evidenceCount) {
                            invalid.add(i);
                        } else {
                            cited.add(i);
                        }
                    }
                } else {
                    int index = parse(token);
                    if (index <= 0) {
                        continue;
                    }
                    citationCount++;
                    if (index > evidenceCount) {
                        invalid.add(index);
                    } else {
                        cited.add(index);
                    }
                }
            }
        }

        boolean valid;
        String reason;
        if (citationCount == 0) {
            valid = false;
            reason = "回答没有任何引用标注，无法核对出处";
        } else if (!invalid.isEmpty()) {
            valid = false;
            reason = "引用了本次检索结果中不存在的章节：" + invalid;
        } else {
            valid = true;
            reason = "引用全部落在本次检索结果内（" + cited.size() + " 段证据）";
        }
        log.debug("[QaCitation] citations={}, cited={}, invalid={}, valid={}", citationCount, cited, invalid, valid);
        return new CitationCheck(citationCount, List.copyOf(cited), List.copyOf(invalid), valid, reason);
    }

    private static int parse(String raw) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
