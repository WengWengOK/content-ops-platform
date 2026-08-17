package com.contentops.common.platform;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 平台规格注册表测试：宽容解析 + 四平台规则完整性 + Prompt 适配指令生成。
 */
class PlatformSpecRegistryTest {

    private final PlatformSpecRegistry registry = new PlatformSpecRegistry();

    @Test
    @DisplayName("四平台规格齐全，且中文名/code/短码均可解析")
    void allFourPlatformsResolvable() {
        assertEquals(4, registry.getSpecs().size());

        assertEquals(ContentPlatform.XIAOHONGSHU, registry.resolve("小红书"));
        assertEquals(ContentPlatform.XIAOHONGSHU, registry.resolve("xiaohongshu"));
        assertEquals(ContentPlatform.XIAOHONGSHU, registry.resolve("xhs"));

        assertEquals(ContentPlatform.WECHAT_OFFICIAL_ACCOUNT, registry.resolve("微信公众号"));
        assertEquals(ContentPlatform.WECHAT_OFFICIAL_ACCOUNT, registry.resolve("wechat"));
        assertEquals(ContentPlatform.WECHAT_OFFICIAL_ACCOUNT, registry.resolve("公众号"));

        assertEquals(ContentPlatform.DOUYIN, registry.resolve("抖音"));
        assertEquals(ContentPlatform.DOUYIN, registry.resolve("douyin"));

        assertEquals(ContentPlatform.BILIBILI, registry.resolve("哔哩哔哩"));
        assertEquals(ContentPlatform.BILIBILI, registry.resolve("bilibili"));
        assertEquals(ContentPlatform.BILIBILI, registry.resolve("B站"));

        assertNull(registry.resolve("知乎"));
        assertNull(registry.resolve(""));
        assertNull(registry.resolve(null));
    }

    @Test
    @DisplayName("resolveAll 去重并忽略无法识别的平台")
    void resolveAllDeduplicates() {
        List<ContentPlatform> platforms = registry.resolveAll(
                List.of("小红书", "xiaohongshu", "微信公众号", "未知平台", "抖音"));
        assertEquals(3, platforms.size());
        assertTrue(platforms.contains(ContentPlatform.XIAOHONGSHU));
        assertTrue(platforms.contains(ContentPlatform.WECHAT_OFFICIAL_ACCOUNT));
        assertTrue(platforms.contains(ContentPlatform.DOUYIN));
    }

    @Test
    @DisplayName("每个平台都有非空适配指令，且内容体现平台差异")
    void guidanceGeneratedForEveryPlatform() {
        for (ContentPlatform platform : ContentPlatform.values()) {
            String guidance = registry.guidance(platform);
            assertNotNull(guidance);
            assertFalse(guidance.isBlank(), platform.getCode() + " 适配指令不能为空");
            assertTrue(guidance.contains(platform.getDisplayName()),
                    platform.getCode() + " 适配指令应包含平台名");
            assertTrue(guidance.contains("标题规则"));
            assertTrue(guidance.contains("正文规则"));
        }

        String xhs = registry.guidance(ContentPlatform.XIAOHONGSHU);
        String wechat = registry.guidance(ContentPlatform.WECHAT_OFFICIAL_ACCOUNT);
        assertTrue(xhs.contains("emoji"), "小红书规则应强调 emoji");
        assertTrue(wechat.contains("克制"), "公众号规则应强调克制使用 emoji");
        assertFalse(xhs.equals(wechat), "不同平台适配指令必须存在差异");
    }

    @Test
    @DisplayName("videoSupported 当前全部为 false（预留短视频扩展位）")
    void videoNotSupportedYet() {
        for (ContentPlatform platform : ContentPlatform.values()) {
            assertFalse(platform.isVideoSupported(), platform.getCode() + " 当前仅支持图文");
        }
    }
}
