package com.contentops.common.knowledge.qa;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 答案-证据一致性校验测试（第 2 类错误：生成幻觉）。
 */
class AnswerFaithfulnessCheckerTest {

    private AnswerFaithfulnessChecker checker;

    @BeforeEach
    void setUp() {
        checker = new AnswerFaithfulnessChecker(new QaProperties());
    }

    @Nested
    @DisplayName("结论拆分")
    class SplitClaims {

        @Test
        @DisplayName("按句切分并剥掉列表序号")
        void splitsSentencesAndListMarkers() {
            List<String> claims = checker.splitClaims("1. 发货时间是 48 小时。2. 支持 7 天无理由退货。", 4);

            assertThat(claims).containsExactly("发货时间是 48 小时", "支持 7 天无理由退货");
        }

        @Test
        @DisplayName("客套/拒答句不参与事实核查")
        void skipsNonClaims() {
            List<String> claims = checker.splitClaims("抱歉，知识库里没有找到相关内容。谢谢！", 4);

            assertThat(claims).isEmpty();
        }
    }

    @Nested
    @DisplayName("判定与风险")
    class Verdicts {

        @Test
        @DisplayName("答案与证据一致：全部支持，风险 NONE")
        void supported() {
            String evidence = "发货时间：下单后 48 小时内发货，支持 7 天无理由退货。";
            AnswerFaithfulnessChecker.FaithfulnessReport report = checker.check(
                    "下单后 48 小时内发货 [1]", List.of(evidence), false);

            assertThat(report.claimCount()).isEqualTo(1);
            assertThat(report.claims().get(0).verdict())
                    .isEqualTo(AnswerFaithfulnessChecker.SUPPORTED);
            assertThat(report.riskLevel()).isEqualTo("NONE");
            assertThat(report.reviewState()).isEqualTo("NONE");
        }

        @Test
        @DisplayName("模型加戏编造步骤：不支持占比 100% → HIGH + 进复核清单")
        void hallucinated() {
            String evidence = "发货时间：下单后 48 小时内发货，支持 7 天无理由退货。";
            AnswerFaithfulnessChecker.FaithfulnessReport report = checker.check(
                    "在订单详情页点击申请退款，然后选择仅退款，系统会在 3 个工作日原路退回，"
                            + "条件是需要提供开箱视频和身份证照片。 [1]",
                    List.of(evidence), false);

            assertThat(report.claims().get(0).verdict())
                    .isEqualTo(AnswerFaithfulnessChecker.UNSUPPORTED);
            assertThat(report.unsupportedRatio()).isEqualTo(1.0);
            assertThat(report.riskLevel()).isEqualTo("HIGH");
            assertThat(report.reviewState()).isEqualTo("NEEDS_REVIEW");
        }

        @Test
        @DisplayName("引用真实章节但数字被改：措辞相近也最多判部分支持")
        void numericMismatch() {
            String evidence = "发货时间：下单后 48 小时内发货。";
            AnswerFaithfulnessChecker.FaithfulnessReport report = checker.check(
                    "下单后 48 小时内发货，超时赔付 500 元。", List.of(evidence), false);

            assertThat(report.claims().get(0).verdict())
                    .isEqualTo(AnswerFaithfulnessChecker.PARTIAL);
            assertThat(report.claims().get(0).reason()).contains("数字");
        }

        @Test
        @DisplayName("拒答样本不做事实核查")
        void refusedSkipped() {
            AnswerFaithfulnessChecker.FaithfulnessReport report =
                    checker.check("知识库中没有找到相关内容。", List.of(), true);

            assertThat(report.riskLevel()).isEqualTo("NONE");
            assertThat(report.summary()).contains("拒答");
        }

        @Test
        @DisplayName("无证据但有事实结论：全部不支持")
        void noEvidence() {
            AnswerFaithfulnessChecker.FaithfulnessReport report =
                    checker.check("发货时间是 48 小时。", List.of(), false);

            assertThat(report.claims().get(0).verdict())
                    .isEqualTo(AnswerFaithfulnessChecker.UNSUPPORTED);
        }

        @Test
        @DisplayName("长证据 + 短结论逐字复述：必须判为支持（防止把正确答案误判成编造）")
        void longEvidenceShortQuoteIsSupported() {
            String evidence = "第 3 章 权限设置：进入「设置 - 成员管理 - 角色权限」，可以为成员分配 "
                    + "ADMIN（管理员）、CREATOR（创作者）、VIEWER（只读）三种角色。"
                    + "第 5 章 退款政策：订阅后 7 天内可申请全额退款，退款在 3 个工作日内原路退回。";

            AnswerFaithfulnessChecker.FaithfulnessReport report = checker.check(
                    "进入「设置 - 成员管理 - 角色权限」，即可为成员分配角色。[1]", List.of(evidence), false);

            assertThat(report.claims().get(0).verdict())
                    .isEqualTo(AnswerFaithfulnessChecker.SUPPORTED);
            assertThat(report.riskLevel()).isEqualTo("NONE");
        }
    }
}
