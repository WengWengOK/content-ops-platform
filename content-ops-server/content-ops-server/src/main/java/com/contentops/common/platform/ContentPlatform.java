package com.contentops.common.platform;

import java.util.Arrays;
import java.util.Locale;

/**
 * 内容发布平台枚举（P 平台化工作流改造）。
 *
 * <p>当前仅支持图文内容形态；{@link #isVideoSupported()} 预留短视频能力扩展位，
 * 抖音/哔哩哔哩后续可切换为短视频形态而不影响编排结构。
 */
public enum ContentPlatform {

    XIAOHONGSHU("xiaohongshu", "小红书", "xhs", false),
    WECHAT_OFFICIAL_ACCOUNT("wechat", "微信公众号", "gh", false),
    DOUYIN("douyin", "抖音", "dy", false),
    BILIBILI("bilibili", "哔哩哔哩", "bl", false);

    private final String code;
    private final String displayName;
    private final String shortCode;
    private final boolean videoSupported;

    ContentPlatform(String code, String displayName, String shortCode, boolean videoSupported) {
        this.code = code;
        this.displayName = displayName;
        this.shortCode = shortCode;
        this.videoSupported = videoSupported;
    }

    public String getCode() {
        return code;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getShortCode() {
        return shortCode;
    }

    /** 是否支持短视频内容形态（当前统一为图文，false）。 */
    public boolean isVideoSupported() {
        return videoSupported;
    }

    /**
     * 宽容匹配：支持 code（xiaohongshu）、中文名（小红书）、短码（xhs）以及
     * 前端常见的"小红书、公众号、微信、B站、bilibili"等别名。
     */
    public static ContentPlatform from(String nameOrCode) {
        if (nameOrCode == null || nameOrCode.isBlank()) {
            return null;
        }
        String raw = nameOrCode.trim();
        String normalized = raw.toLowerCase(Locale.ROOT);
        for (ContentPlatform platform : values()) {
            if (platform.code.equalsIgnoreCase(normalized)
                    || platform.shortCode.equalsIgnoreCase(normalized)
                    || platform.displayName.equals(raw)) {
                return platform;
            }
        }
        // 常见别名兜底
        if (normalized.contains("wechat") || normalized.contains("weixin")
                || raw.contains("公众号") || raw.equals("微信")) {
            return WECHAT_OFFICIAL_ACCOUNT;
        }
        if (normalized.equals("b站") || normalized.equals("bilibili")
                || normalized.equals("哔哩") || normalized.equals("bili")) {
            return BILIBILI;
        }
        return Arrays.stream(values())
                .filter(p -> normalized.contains(p.code) || raw.contains(p.displayName))
                .findFirst()
                .orElse(null);
    }
}
