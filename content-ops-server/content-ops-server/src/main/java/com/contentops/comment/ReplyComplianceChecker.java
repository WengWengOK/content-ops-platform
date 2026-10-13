package com.contentops.comment;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 回复草稿的忠实度/合规校验（**只标记，不自动修正**）。
 *
 * <p>设计原则（与项目约定一致）：
 * <ol>
 *   <li>自动识别错误准确率有限、误判成本不低（把对的标成错会浪费人工复核时间；把错的放过用户可能照错操作），
 *       因此这里只产出带置信度的标记，交由人工复核；</li>
 *   <li>高置信度问题（HIGH）直接进复核清单；中低置信度按抽样复核；</li>
 *   <li>不修改模型输出，避免「自动修正」引入新的不可追溯错误。</li>
 * </ol>
 *
 * <p>校验维度：禁止承诺、未核实价格/促销/时效、需转人工未提示、外链合规、长度超限。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReplyComplianceChecker {

    /** 标记等级：HIGH 进复核清单，MEDIUM/LOW 抽样复核。 */
    public record ReplyFlag(String code, String level, String message, double confidence) {
    }

    /** 校验结果。 */
    public record CheckResult(List<ReplyFlag> flags, double confidence, String reviewState) {
    }

    /** 内置高危承诺词（无论 profile 是否配置都不允许）。 */
    private static final List<String> BUILTIN_FORBIDDEN = List.of(
            "绝对正品", "百分百", "100%", "假一赔十", "全网最低", "最低价", "永久有效",
            "包治", "无副作用", "治愈", "稳赚", "立刻见效", "特效", "保证有效");

    private static final List<String> PRICE_HINTS = List.of("元", "¥", "价格", "多少钱", "折", "券", "优惠价");
    private static final List<String> PROMO_HINTS = List.of("包邮", "返现", "赠品", "买一送一", "免单", "限时");
    /**
     * 「已提示转人工」的判定词。要覆盖真实话术（转接 / 人工 / 客服 / 专人 / 售后…），
     * 否则模型说「我这就帮你转接」也会被误判为漏提示，白白占用人工复核时间。
     */
    private static final List<String> ESCALATION_REPLY_HINTS = List.of(
            "转人工", "转接", "人工", "客服", "专人", "售后", "帮你反馈", "上报");
    private static final List<String> TIME_PROMISE_HINTS = List.of("明天", "后天", "今天发", "当天发", "小时内", "具体日期");

    /**
     * 否定/拒绝语境的前置窗口与标记。
     * 用于降低误判：回复里出现「不能保证涨薪」「无法承诺百分百」这类**拒绝承诺**的表达，
     * 本身是合规的，不应直接进高置信度复核清单。
     */
    private static final int NEGATION_WINDOW = 4;
    private static final List<String> NEGATION_MARKERS = List.of("不", "没", "无", "非", "别", "勿", "拒");

    /**
     * 校验草稿。
     *
     * @param reply          模型产出的回复草稿（原样保留，不修改）
     * @param profile        场景配置（可为空，为空时只用内置规则）
     * @param commentContent 用户评论原文
     * @param extraFacts     额外事实（如商品上下文，可为空）
     */
    public CheckResult check(String reply, ReplyAgentProfile profile, String commentContent, String extraFacts) {
        List<ReplyFlag> flags = new ArrayList<>();
        String text = reply == null ? "" : reply;
        String facts = ((profile == null || profile.getProductFacts() == null ? "" : profile.getProductFacts())
                + " " + (extraFacts == null ? "" : extraFacts)).trim();

        // 1) 禁止承诺
        for (String claim : split(profile == null ? null : profile.getForbiddenClaims())) {
            if (!claim.isBlank() && text.contains(claim)) {
                boolean negated = isNegated(text, claim);
                flags.add(new ReplyFlag("FORBIDDEN_CLAIM", negated ? "LOW" : "HIGH",
                        negated
                                ? "出现禁止承诺词「" + claim + "」，但上下文是否定/拒绝表达，抽样复核即可"
                                : "命中场景禁止承诺：「" + claim + "」，需人工确认是否可承诺",
                        negated ? 0.4 : 0.95));
            }
        }
        for (String claim : BUILTIN_FORBIDDEN) {
            if (text.contains(claim)) {
                boolean negated = isNegated(text, claim);
                flags.add(new ReplyFlag("FORBIDDEN_CLAIM_BUILTIN", negated ? "LOW" : "HIGH",
                        negated
                                ? "出现高危承诺词「" + claim + "」，但上下文是否定/拒绝表达，抽样复核即可"
                                : "命中高危承诺词：「" + claim + "」，广告法风险",
                        negated ? 0.4 : 0.9));
            }
        }

        // 2) 未核实的价格/优惠信息（可能是幻觉）
        if (containsAny(text, PRICE_HINTS) && !containsAny(facts, PRICE_HINTS) && !containsDigit(facts)) {
            flags.add(new ReplyFlag("PRICE_UNVERIFIED", "MEDIUM",
                    "回复涉及价格/优惠，但商品事实里没有价格信息，需人工核对", 0.75));
        }
        if (containsAny(text, PROMO_HINTS) && !containsAny(facts, PROMO_HINTS)) {
            flags.add(new ReplyFlag("PROMO_UNVERIFIED", "MEDIUM",
                    "回复承诺了促销权益（包邮/返现/赠品等），事实里未配置，需人工确认", 0.7));
        }
        if (containsAny(text, TIME_PROMISE_HINTS) && !containsAny(facts, TIME_PROMISE_HINTS)) {
            flags.add(new ReplyFlag("SHIPPING_UNVERIFIED", "MEDIUM",
                    "回复给出了发货/时效承诺，事实里未配置，需人工确认", 0.65));
        }

        // 3) 需转人工却未提示
        List<String> escalateKeywords = split(profile == null ? null : profile.getEscalateKeywords());
        String combined = (commentContent == null ? "" : commentContent) + " " + text;
        for (String keyword : escalateKeywords) {
            if (!keyword.isBlank() && combined.contains(keyword) && !containsAny(text, ESCALATION_REPLY_HINTS)) {
                flags.add(new ReplyFlag("ESCALATION_MISSING", "HIGH",
                        "涉及「" + keyword + "」类问题，回复未提示转人工，建议人工接手", 0.85));
            }
        }

        // 4) 外链合规
        boolean hasLink = text.contains("http://") || text.contains("https://");
        if (hasLink && (profile == null || !profile.isAllowLinks())) {
            flags.add(new ReplyFlag("LINK_NOT_ALLOWED", "MEDIUM",
                    "回复包含外部链接，当前场景未允许发链接，需人工确认", 0.7));
        }

        // 5) 长度超限
        int maxChars = profile == null || profile.getReplyMaxChars() == null ? 120 : profile.getReplyMaxChars();
        if (text.length() > maxChars * 1.5) {
            flags.add(new ReplyFlag("TOO_LONG", "LOW",
                    "回复长度 " + text.length() + " 字，超过场景上限 " + maxChars + " 字较多", 0.5));
        }

        double maxFlagConfidence = flags.stream().mapToDouble(ReplyFlag::confidence).max().orElse(0);
        double confidence = flags.isEmpty() ? 0.95 : Math.max(0.05, 1 - maxFlagConfidence);
        String reviewState = flags.stream().anyMatch(f -> "HIGH".equals(f.level()))
                ? "NEEDS_REVIEW"
                : (flags.isEmpty() ? "NONE" : "SAMPLED");
        return new CheckResult(flags, Math.round(confidence * 100d) / 100d, reviewState);
    }

    private List<String> split(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(raw.split("[,，;；]")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    /**
     * 承诺词是否处于否定/拒绝语境（如「不能保证涨薪」「无法承诺百分百」）。
     * 只有**所有**出现位置都被否定才算否定表达。
     */
    private boolean isNegated(String text, String claim) {
        int idx = text.indexOf(claim);
        if (idx < 0) {
            return false;
        }
        while (idx >= 0) {
            String before = text.substring(Math.max(0, idx - NEGATION_WINDOW), idx);
            if (!containsAny(before, NEGATION_MARKERS)) {
                return false;
            }
            idx = text.indexOf(claim, idx + 1);
        }
        return true;
    }

    private boolean containsAny(String text, List<String> keywords) {
        if (text == null) {
            return false;
        }
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private boolean containsDigit(String text) {
        return text != null && text.matches(".*\\d.*");
    }
}
