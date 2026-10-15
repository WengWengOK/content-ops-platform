package com.contentops.common.knowledge.qa;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 轻量相似度工具测试（忠实度 / 重复提问 / 聚类共用）。
 */
class TextSimilarityTest {

    @Test
    @DisplayName("归一化去掉空白与标点")
    void normalize() {
        assertThat(TextSimilarity.normalize("发货 时间：48 小时！")).isEqualTo("发货时间48小时");
    }

    @Test
    @DisplayName("完全相同为 1，完全不同接近 0")
    void jaccardExtremes() {
        assertThat(TextSimilarity.jaccard("支持7天无理由退货", "支持7天无理由退货")).isEqualTo(1.0);
        assertThat(TextSimilarity.jaccard("支持7天无理由退货", "今天天气不错")).isLessThan(0.1);
    }

    @Test
    @DisplayName("同义改写仍有明显重合（字二元组对中文改写的下限，聚类阈值按此设定）")
    void jaccardParaphrase() {
        double score = TextSimilarity.jaccard("发货时间是多久", "发货大概要多久呢");

        assertThat(score).isGreaterThan(0.15);
        // 改写幅度更小时可稳定越过重复提问阈值
        assertThat(TextSimilarity.jaccard("退款怎么申请", "退款要怎么申请")).isGreaterThan(0.45);
    }

    @Test
    @DisplayName("抽取数字用于核对证据")
    void numbers() {
        assertThat(TextSimilarity.numbers("48 小时内发货，超时赔付 500 元")).containsExactly("48", "500");
        assertThat(TextSimilarity.numbers("没有数字")).isEmpty();
    }

    @Test
    @DisplayName("覆盖率：短结论 vs 长证据要判为被支持（Jaccard 会误判）")
    void containmentVsJaccard() {
        String evidence = "《内容运营平台 产品手册》第 3 章 权限设置：进入「设置 - 成员管理 - 角色权限」，"
                + "可以为成员分配 ADMIN（管理员）、CREATOR（创作者）、VIEWER（只读）三种角色。"
                + "第 4 章 发布设置：公众号发布需要先配置 AppID 与 AppSecret。";
        String claim = "进入「设置 - 成员管理 - 角色权限」，即可为成员分配角色";

        assertThat(TextSimilarity.containment(claim, evidence)).isGreaterThan(0.6);
        assertThat(TextSimilarity.jaccard(claim, evidence)).isLessThan(0.3);
    }

    @Test
    @DisplayName("覆盖率对编造内容仍然很低")
    void containmentLowForHallucination() {
        String evidence = "第 3 章 权限设置：可以为成员分配 ADMIN、CREATOR、VIEWER 三种角色。";

        assertThat(TextSimilarity.containment("在设置页开启双因素认证，然后等待 99 天生效", evidence))
                .isLessThan(0.3);
    }
}
