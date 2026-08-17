package com.contentops.common.enums;

/**
 * 发布作品模块的产出模式。
 *
 * <p>用户创建工作流时可选择：
 * <ul>
 *   <li>{@link #TEXT_COVER}：只生成文字内容 + 封面图（最省 Token）</li>
 *   <li>{@link #IMAGE_TEXT}：文字 + 与上下文匹配的正文配图（图文混排）</li>
 *   <li>{@link #FULL_IMAGE}：整篇内容全部生成图片卡片（Token 消耗最大，方便直接下载使用）</li>
 * </ul>
 *
 * <p>模式通过 {@code StartWorkflowRequest.publishMode} 传入，并写入
 * {@code TaskContext.inputs["publishMode"]}，供图片/发布/导出各环节读取。
 */
public enum PublishMode {

    TEXT_COVER("text-cover", "文字+封面", "只生成文字内容和封面图"),
    IMAGE_TEXT("image-text", "图文混排", "文字 + 与上下文匹配的配图"),
    FULL_IMAGE("full-image", "全图卡片", "整篇内容全部生成图片卡片（Token 消耗最大）");

    private final String code;
    private final String nameCn;
    private final String description;

    PublishMode(String code, String nameCn, String description) {
        this.code = code;
        this.nameCn = nameCn;
        this.description = description;
    }

    public String getCode() {
        return code;
    }

    public String getNameCn() {
        return nameCn;
    }

    public String getDescription() {
        return description;
    }

    /**
     * 从 code / 枚举名 / 中文名解析模式，无法识别时回退为 {@link #TEXT_COVER}。
     */
    public static PublishMode fromCode(String raw) {
        if (raw == null || raw.isBlank()) {
            return TEXT_COVER;
        }
        String normalized = raw.trim();
        for (PublishMode mode : values()) {
            if (mode.code.equalsIgnoreCase(normalized)
                    || mode.name().equalsIgnoreCase(normalized)
                    || mode.nameCn.equals(normalized)) {
                return mode;
            }
        }
        return TEXT_COVER;
    }
}
