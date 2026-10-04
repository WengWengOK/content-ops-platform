package com.contentops.common.enums;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 平台字节序号映射测试：编号稳定性 + 输入推断。
 */
class PlatformCodeTest {

    @Test
    @DisplayName("编号固定：1=小红书 2=公众号 3=抖音 4=B站 5=快手（改动会破坏历史数据）")
    void codesAreStable() {
        assertThat(PlatformCode.XIAOHONGSHU.code()).isEqualTo((byte) 1);
        assertThat(PlatformCode.WECHAT.code()).isEqualTo((byte) 2);
        assertThat(PlatformCode.DOUYIN.code()).isEqualTo((byte) 3);
        assertThat(PlatformCode.BILIBILI.code()).isEqualTo((byte) 4);
        assertThat(PlatformCode.KUAISHOU.code()).isEqualTo((byte) 5);
        assertThat(PlatformCode.fromCode(2).displayName()).isEqualTo("微信公众号");
        assertThat(PlatformCode.fromCode(99)).isEqualTo(PlatformCode.UNKNOWN);
    }

    @Test
    @DisplayName("按字符串/别名解析平台")
    void resolvesAliases() {
        assertThat(PlatformCode.codeOf("xiaohongshu")).isEqualTo(1);
        assertThat(PlatformCode.codeOf("小红书")).isEqualTo(1);
        assertThat(PlatformCode.codeOf("XHS")).isEqualTo(1);
        assertThat(PlatformCode.codeOf("公众号")).isEqualTo(2);
        assertThat(PlatformCode.codeOf("B站")).isEqualTo(4);
        assertThat(PlatformCode.codeOf("douyin")).isEqualTo(3);
        assertThat(PlatformCode.codeOf("unknown-platform")).isZero();
        assertThat(PlatformCode.codeOf(null)).isZero();
    }

    @Test
    @DisplayName("从工作流 inputs 推断平台序号：platform → platforms[0] → branches[0].platform")
    void infersFromInputs() {
        assertThat(PlatformCode.codeOfInputs(Map.of("platform", "douyin"))).isEqualTo(3);
        assertThat(PlatformCode.codeOfInputs(Map.of("platforms", List.of("bilibili", "wechat")))).isEqualTo(4);
        assertThat(PlatformCode.codeOfInputs(Map.of("branches",
                List.of(Map.of("platform", "kuaishou"))))).isEqualTo(5);
        assertThat(PlatformCode.codeOfInputs(Map.of())).isZero();
        assertThat(PlatformCode.codeOfInputs(null)).isZero();
    }
}