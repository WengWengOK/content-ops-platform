package com.contentops.common.knowledge.qa;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 引用校验测试（第 3 类错误：引用不实）。
 */
class CitationGuardTest {

    private final CitationGuard guard = new CitationGuard();

    @Test
    @DisplayName("引用落在本次检索结果内：通过")
    void validCitations() {
        CitationGuard.CitationCheck check = guard.check("发货时间 48 小时 [1]，支持 7 天无理由 [2]。", 2);

        assertThat(check.valid()).isTrue();
        assertThat(check.citedIndices()).containsExactly(1, 2);
        assertThat(check.invalidIndices()).isEmpty();
    }

    @Test
    @DisplayName("引用不存在的章节：判为引用不实")
    void invalidCitation() {
        CitationGuard.CitationCheck check = guard.check("在设置里开启双因素认证 [3]。", 2);

        assertThat(check.valid()).isFalse();
        assertThat(check.invalidIndices()).containsExactly(3);
        assertThat(check.reason()).contains("不存在的章节");
    }

    @Test
    @DisplayName("一个引用都没有：判为无法核对出处")
    void noCitation() {
        CitationGuard.CitationCheck check = guard.check("发货时间是 48 小时。", 2);

        assertThat(check.valid()).isFalse();
        assertThat(check.citationCount()).isZero();
        assertThat(check.reason()).contains("没有任何引用");
    }

    @Test
    @DisplayName("区间与多编号引用都能解析")
    void rangeAndMultiCitations() {
        CitationGuard.CitationCheck check = guard.check("综合 [1-2] 与 [3,4] 的结论。", 4);

        assertThat(check.valid()).isTrue();
        assertThat(check.citedIndices()).containsExactly(1, 2, 3, 4);
        assertThat(check.citationCount()).isEqualTo(4);
    }
}
