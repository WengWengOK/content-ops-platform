package com.contentops.comment;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 回复忠实度校验测试：只标记、不修正；高置信度进复核清单、中低置信度抽样。
 */
class ReplyComplianceCheckerTest {

    private final ReplyComplianceChecker checker = new ReplyComplianceChecker();

    private ReplyAgentProfile profile(String forbidden, String escalate, Integer maxChars, boolean allowLinks) {
        return ReplyAgentProfile.builder()
                .profileId("p1")
                .scenario("ECOMMERCE")
                .name("电商带货客服")
                .forbiddenClaims(forbidden)
                .escalateKeywords(escalate)
                .replyMaxChars(maxChars)
                .allowLinks(allowLinks)
                .enabled(true)
                .build();
    }

    @Nested
    @DisplayName("干净草稿")
    class CleanDraft {

        @Test
        @DisplayName("无任何标记：置信度 0.95，状态 NONE，且原文不被修改")
        void noFlags() {
            String reply = "收到你的问题啦，我帮你确认一下～";
            ReplyComplianceChecker.CheckResult result =
                    checker.check(reply, profile("绝对正品", "退款", 120, false), "这个能发货吗", null);

            assertThat(result.flags()).isEmpty();
            assertThat(result.confidence()).isEqualTo(0.95);
            assertThat(result.reviewState()).isEqualTo("NONE");
        }

        @Test
        @DisplayName("profile 为空时仍执行内置高危词规则")
        void nullProfileStillUsesBuiltinRules() {
            ReplyComplianceChecker.CheckResult result = checker.check("这个绝对正品，放心买", null, "真的吗", null);

            assertThat(result.flags()).anyMatch(f -> "FORBIDDEN_CLAIM_BUILTIN".equals(f.code()));
            assertThat(result.reviewState()).isEqualTo("NEEDS_REVIEW");
        }
    }

    @Nested
    @DisplayName("禁止承诺（HIGH，直接进复核清单）")
    class ForbiddenClaims {

        @Test
        @DisplayName("命中场景配置的禁止承诺：HIGH + NEEDS_REVIEW + 置信度反比")
        void scenarioForbiddenClaim() {
            ReplyComplianceChecker.CheckResult result = checker.check(
                    "放心，我们保证涨薪没问题", profile("保证涨薪,包过", "退款", 120, false), "有用吗", null);

            ReplyComplianceChecker.ReplyFlag flag = result.flags().stream()
                    .filter(f -> "FORBIDDEN_CLAIM".equals(f.code())).findFirst().orElseThrow();
            assertThat(flag.level()).isEqualTo("HIGH");
            assertThat(flag.message()).contains("保证涨薪");
            assertThat(result.reviewState()).isEqualTo("NEEDS_REVIEW");
            assertThat(result.confidence()).isEqualTo(0.05);
        }

        @Test
        @DisplayName("命中内置高危词（广告法风险）：HIGH")
        void builtinForbiddenClaim() {
            ReplyComplianceChecker.CheckResult result =
                    checker.check("效果百分百见效", profile(null, null, 120, false), "有用吗", null);

            assertThat(result.flags()).anyMatch(f -> "FORBIDDEN_CLAIM_BUILTIN".equals(f.code())
                    && "HIGH".equals(f.level()));
        }

        @Test
        @DisplayName("逗号/分号分隔的多个禁止承诺都能命中")
        void multipleForbiddenClaimsSplitOnSeparators() {
            ReplyComplianceChecker.CheckResult result = checker.check(
                    "这是全网最低价，绝对正品", profile("全网最低价；绝对正品", null, 120, false), "多少钱", null);

            assertThat(result.flags().stream().filter(f -> "FORBIDDEN_CLAIM".equals(f.code())).count())
                    .isGreaterThanOrEqualTo(2);
        }

        @Test
        @DisplayName("拒绝承诺的表达降级为抽样：不能保证涨薪 ≠ 承诺涨薪（避免误判占用人工）")
        void negatedClaimDowngraded() {
            ReplyComplianceChecker.CheckResult result = checker.check(
                    "不能保证涨薪，我们不承诺收益，效果因人而异",
                    profile("保证涨薪,收益", null, 120, false), "学完能涨薪吗", null);

            assertThat(result.flags()).isNotEmpty();
            assertThat(result.flags()).allMatch(f -> "LOW".equals(f.level()));
            assertThat(result.reviewState()).isEqualTo("SAMPLED");
        }

        @Test
        @DisplayName("同一承诺词只要有一处是肯定表达，仍按 HIGH 进复核清单")
        void partiallyNegatedStillHigh() {
            ReplyComplianceChecker.CheckResult result = checker.check(
                    "不会夸大，我们保证涨薪",
                    profile("保证涨薪", null, 120, false), "学完能涨薪吗", null);

            assertThat(result.flags()).anyMatch(f -> "FORBIDDEN_CLAIM".equals(f.code()) && "HIGH".equals(f.level()));
            assertThat(result.reviewState()).isEqualTo("NEEDS_REVIEW");
        }
    }

    @Nested
    @DisplayName("事实未核实（MEDIUM/LOW，抽样复核）")
    class UnverifiedFacts {

        @Test
        @DisplayName("回复谈价格但事实里没有价格：PRICE_UNVERIFIED + SAMPLED")
        void priceUnverified() {
            ReplyComplianceChecker.CheckResult result = checker.check(
                    "这件是 299 元哦", profile(null, null, 120, false), "多少钱", null);

            assertThat(result.flags()).anyMatch(f -> "PRICE_UNVERIFIED".equals(f.code()) && "MEDIUM".equals(f.level()));
            assertThat(result.reviewState()).isEqualTo("SAMPLED");
        }

        @Test
        @DisplayName("事实里已配置价格：不误报（误判会浪费人工复核时间）")
        void priceVerifiedNoFlag() {
            ReplyAgentProfile p = profile(null, null, 120, false);
            p.setProductFacts("售价 299 元，48 小时内发货");
            ReplyComplianceChecker.CheckResult result = checker.check("这件是 299 元哦", p, "多少钱", null);

            assertThat(result.flags()).noneMatch(f -> "PRICE_UNVERIFIED".equals(f.code()));
        }

        @Test
        @DisplayName("促销权益（包邮/返现）事实里没有：PROMO_UNVERIFIED")
        void promoUnverified() {
            ReplyComplianceChecker.CheckResult result =
                    checker.check("可以给你包邮哦", profile(null, null, 120, false), "能便宜吗", null);

            assertThat(result.flags()).anyMatch(f -> "PROMO_UNVERIFIED".equals(f.code()));
        }

        @Test
        @DisplayName("发货时效承诺事实里没有：SHIPPING_UNVERIFIED")
        void shippingUnverified() {
            ReplyComplianceChecker.CheckResult result =
                    checker.check("今天发的，明天就到", profile(null, null, 120, false), "多久发货", null);

            assertThat(result.flags()).anyMatch(f -> "SHIPPING_UNVERIFIED".equals(f.code()));
        }
    }

    @Nested
    @DisplayName("转人工规则")
    class Escalation {

        @Test
        @DisplayName("评论命中转人工词但回复没提示转人工：HIGH + NEEDS_REVIEW")
        void escalationMissing() {
            ReplyComplianceChecker.CheckResult result = checker.check(
                    "好的，我帮你看看", profile(null, "退款,投诉", 120, false), "我要退款", null);

            ReplyComplianceChecker.ReplyFlag flag = result.flags().stream()
                    .filter(f -> "ESCALATION_MISSING".equals(f.code())).findFirst().orElseThrow();
            assertThat(flag.level()).isEqualTo("HIGH");
            assertThat(result.reviewState()).isEqualTo("NEEDS_REVIEW");
        }

        @Test
        @DisplayName("回复已提示转人工：不误报")
        void escalationPresentNoFlag() {
            ReplyComplianceChecker.CheckResult result = checker.check(
                    "退款的问题我转人工帮你处理，客服同事马上联系你",
                    profile(null, "退款,投诉", 120, false), "我要退款", null);

            assertThat(result.flags()).noneMatch(f -> "ESCALATION_MISSING".equals(f.code()));
        }

        @Test
        @DisplayName("「我这就帮你转接」等真实话术也算已提示人工：不误报")
        void escalationPresentRealPhrasing() {
            ReplyComplianceChecker.CheckResult result = checker.check(
                    "退款问题需要人工帮你核实处理，我这就帮你转接，请稍等～",
                    profile(null, "退款,投诉", 120, false), "我要退款", null);

            assertThat(result.flags()).noneMatch(f -> "ESCALATION_MISSING".equals(f.code()));
            assertThat(result.reviewState()).isEqualTo("NONE");
        }
    }

    @Nested
    @DisplayName("外链与长度")
    class LinkAndLength {

        @Test
        @DisplayName("场景不允许发链接：LINK_NOT_ALLOWED")
        void linkNotAllowed() {
            ReplyComplianceChecker.CheckResult result =
                    checker.check("详情看 https://example.com/a", profile(null, null, 120, false), "有链接吗", null);

            assertThat(result.flags()).anyMatch(f -> "LINK_NOT_ALLOWED".equals(f.code()));
        }

        @Test
        @DisplayName("场景允许发链接：不标记")
        void linkAllowed() {
            ReplyComplianceChecker.CheckResult result =
                    checker.check("详情看 https://example.com/a", profile(null, null, 120, true), "有链接吗", null);

            assertThat(result.flags()).noneMatch(f -> "LINK_NOT_ALLOWED".equals(f.code()));
        }

        @Test
        @DisplayName("超出长度上限过多：TOO_LONG（LOW）")
        void tooLong() {
            ReplyComplianceChecker.CheckResult result = checker.check(
                    "很".repeat(30), profile(null, null, 10, false), "在吗", null);

            assertThat(result.flags()).anyMatch(f -> "TOO_LONG".equals(f.code()) && "LOW".equals(f.level()));
            assertThat(result.reviewState()).isEqualTo("SAMPLED");
        }
    }
}
