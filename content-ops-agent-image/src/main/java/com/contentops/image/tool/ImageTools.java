package com.contentops.image.tool;

import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Image design tools exposed to the {@link com.contentops.image.agent.ImageDesignAgent}.
 *
 * <p>LangChain4j discovers {@code @Tool} methods on Spring-managed objects that are passed to
 * {@code AiServices.builder().tools(...)} and exposes them to the model as callable functions.
 * The implementations here return simulated data; in production they would delegate to real
 * image-generation / watermark-removal APIs.
 */
@Slf4j
@Component
public class ImageTools {

    @Tool("根据文章内容提取视觉关键词")
    public String extractVisualKeywords(String articleContent) {
        log.info("[Tool] extractVisualKeywords invoked, content length: {}",
                articleContent != null ? articleContent.length() : 0);
        String preview = articleContent != null && articleContent.length() > 20
                ? articleContent.substring(0, 20) : articleContent;
        return "[模拟数据] 基于内容片段「" + preview + "…」提取的视觉关键词：\n"
                + "1. 主体元素：人物、书桌、笔记本、咖啡杯\n"
                + "2. 场景元素：温馨室内、自然光、木质纹理\n"
                + "3. 情绪元素：专注、平静、治愈、成长感\n"
                + "4. 色彩倾向：暖色调（米黄、浅棕、暖白），点缀绿色植物\n"
                + "5. 风格关键词：日系生活感、极简、扁平插画、胶片质感\n"
                + "提示：可结合文章段落，为开头/文中/结尾分别匹配不同氛围的关键词组合。";
    }

    @Tool("生成图片描述提示词")
    public String generateImagePrompt(String scene, String mood, String style) {
        log.info("[Tool] generateImagePrompt invoked for scene: {}, mood: {}, style: {}",
                scene, mood, style);
        return "[模拟提示词] 场景：" + scene + " | 情绪：" + mood + " | 风格：" + style + "\n"
                + "Prompt: A warm and cozy " + scene + " scene, " + mood + " atmosphere, "
                + style + " style, soft natural lighting, warm color palette with beige and "
                + "light brown tones, a touch of greenery, shallow depth of field, "
                + "high detail, 4k, photorealistic, no text, no watermark.\n"
                + "Negative Prompt: text, watermark, logo, low quality, blurry, distorted faces, "
                + "cold colors, cluttered background.\n"
                + "建议尺寸：横版 16:9 用于公众号/头条，竖版 3:4 用于小红书。";
    }

    @Tool("去除图片水印")
    public String removeWatermark(String imageUrl) {
        log.info("[Tool] removeWatermark invoked for imageUrl: {}", imageUrl);
        return "[模拟结果] 已对图片「" + imageUrl + "」执行水印去除处理：\n"
                + "- 检测到右下角文字水印，已通过 inpaint 算法修复\n"
                + "- 输出无水印图片地址：https://images.contentops.example.com/clean/"
                + imageUrl.hashCode() + ".jpg\n"
                + "- 处理耗时：约1.2秒，画质无明显损失\n"
                + "提示：如需批量去水印，可传入多张图片URL以逗号分隔。";
    }
}
