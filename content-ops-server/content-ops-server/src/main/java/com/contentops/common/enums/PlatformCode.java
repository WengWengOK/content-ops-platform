package com.contentops.common.enums;

import java.util.List;
import java.util.Map;

/**
 * 平台字节序号（紧凑编码）：作品只保存 1 个字节的平台编号，展示名在前端按序号解码。
 *
 * <p>为什么用序号：平台名是字符串，散落在每条作品记录/索引里既占空间又难做筛选；
 * 统一成 {@code SMALLINT} 后，存储、索引、跨服务传输都只走 1 个字节，展示层再翻译成中文名。
 *
 * <p><b>编号一经发布不可更改</b>（会破坏历史数据与前端映射）；新增平台只能往后追加。
 */
public enum PlatformCode {

    UNKNOWN((byte) 0, "", "未知平台"),
    XIAOHONGSHU((byte) 1, "xiaohongshu", "小红书"),
    WECHAT((byte) 2, "wechat", "微信公众号"),
    DOUYIN((byte) 3, "douyin", "抖音"),
    BILIBILI((byte) 4, "bilibili", "哔哩哔哩"),
    KUAISHOU((byte) 5, "kuaishou", "快手");

    private final byte code;
    private final String key;
    private final String displayName;

    PlatformCode(byte code, String key, String displayName) {
        this.code = code;
        this.key = key;
        this.displayName = displayName;
    }

    public byte code() {
        return code;
    }

    /** 平台标识（与历史 JSON/接口中使用的字符串保持一致） */
    public String key() {
        return key;
    }

    public String displayName() {
        return displayName;
    }

    /** 按平台标识解析（兼容中英文别名），未知返回 {@link #UNKNOWN}。 */
    public static PlatformCode of(String platform) {
        if (platform == null || platform.isBlank()) {
            return UNKNOWN;
        }
        String value = platform.trim().toLowerCase();
        for (PlatformCode candidate : values()) {
            if (candidate.key.equals(value) || candidate.displayName.equals(platform.trim())) {
                return candidate;
            }
        }
        return switch (value) {
            case "xhs", "redbook", "red", "小红书" -> XIAOHONGSHU;
            case "wechat_mp", "weixin", "微信公众号", "公众号", "微信" -> WECHAT;
            case "douyin", "tiktok", "抖音" -> DOUYIN;
            case "b站", "bili", "哔哩哔哩" -> BILIBILI;
            case "ks", "快手" -> KUAISHOU;
            default -> UNKNOWN;
        };
    }

    public static PlatformCode fromCode(int code) {
        for (PlatformCode candidate : values()) {
            if (candidate.code == (byte) code) {
                return candidate;
            }
        }
        return UNKNOWN;
    }

    /** 平台标识 → 字节序号（未知为 0）。 */
    public static int codeOf(String platform) {
        return of(platform).code;
    }

    /**
     * 从工作流输入里推断平台序号：依次看 {@code platform} → {@code platforms[0]}
     * → {@code branches[0].platform}；都取不到返回 0（未知）。
     */
    @SuppressWarnings("unchecked")
    public static int codeOfInputs(Map<String, Object> inputs) {
        if (inputs == null || inputs.isEmpty()) {
            return UNKNOWN.code;
        }
        Object single = inputs.get("platform");
        if (single instanceof String s && !s.isBlank()) {
            return codeOf(s);
        }
        Object multiple = inputs.get("platforms");
        if (multiple instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof String s) {
            return codeOf(s);
        }
        Object selected = inputs.get("selectedPlatforms");
        if (selected instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof String s) {
            return codeOf(s);
        }
        Object branches = inputs.get("branches");
        if (branches instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof Map<?, ?> first) {
            Object platform = ((Map<String, Object>) first).get("platform");
            if (platform instanceof String s && !s.isBlank()) {
                return codeOf(s);
            }
        }
        return UNKNOWN.code;
    }
}