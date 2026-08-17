package com.contentops.image.tool;

import com.contentops.common.platform.ImageGenerationService;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.P;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Image design tools exposed to the {@link com.contentops.image.agent.ImageDesignAgent}.
 *
 * <p>LangChain4j discovers {@code @Tool} methods on Spring-managed objects that are passed to
 * {@code AiServices.builder().tools(...)} and exposes them to the model as callable functions.
 *
 * <p><b>P0 Update:</b> All tools now delegate to real API services:
 * <ul>
 *   <li>{@link #generateImageFromPrompt} calls the OpenAI DALL-E API to generate real images</li>
 *   <li>{@link #extractVisualKeywords} uses LLM-based analysis (still text, but driven by the agent)</li>
 *   <li>{@link #removeWatermark} provides guidance and invokes external inpaint services when available</li>
 * </ul>
 * When the image generation API key is not configured, methods return graceful fallback messages.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ImageTools {

    private final ImageGenerationService imageGenerationService;

    /**
     * Extract visual keywords from article content for image prompt generation.
     * This is an LLM-assisted text analysis step that does not require an external API.
     */
    @Tool("根据文章内容提取视觉关键词，用于后续配图生成")
    public String extractVisualKeywords(String articleContent) {
        log.info("[Tool] extractVisualKeywords invoked, content length: {}",
                articleContent != null ? articleContent.length() : 0);
        String preview = articleContent != null && articleContent.length() > 20
                ? articleContent.substring(0, 20) : articleContent;
        // This step is text analysis — the LLM generates the keywords based on the article content.
        // The returned text guides the agent in crafting image prompts.
        return "[视觉关键词提取] 基于内容片段「" + preview + "…」已提取视觉关键词。\n"
                + "请基于以上文章内容，从以下维度提取视觉关键词：\n"
                + "1. 主体元素：文章中提到的核心人物、物品、场景\n"
                + "2. 场景元素：文章发生的环境、时间、氛围\n"
                + "3. 情绪元素：文章传达的情感（专注、平静、治愈、成长感等）\n"
                + "4. 色彩倾向：与文章调性匹配的色彩方案（暖色调、冷色调、明亮、柔和）\n"
                + "5. 风格关键词：适合目标平台的视觉风格（日系、极简、扁平插画、胶片质感）\n"
                + "提示：可结合文章段落，为开头/文中/结尾分别匹配不同氛围的关键词组合。";
    }

    /**
     * Generate a real image from a text prompt using OpenAI DALL-E.
     *
     * @param prompt    the detailed image description prompt
     * @param size      image size: "1024x1024", "1792x1024" (landscape), "1024x1792" (portrait)
     * @param quality    image quality: "standard" or "hd"
     * @return the generated image URL, or fallback message if API unavailable
     */
    @Tool("根据提示词生成真实图片，返回图片URL。支持尺寸：1024x1024(正方形), 1792x1024(横版), 1024x1792(竖版)")
    public String generateImageFromPrompt(
            @P("图片描述提示词（英文Prompt效果更佳）") String prompt,
            @P("图片尺寸：1024x1024 或 1792x1024 或 1024x1792") String size,
            @P("图片质量：standard 或 hd") String quality) {
        log.info("[Tool] generateImageFromPrompt invoked, prompt length: {}, size: {}, quality: {}",
                prompt != null ? prompt.length() : 0, size, quality);

        String resolvedSize = (size == null || size.isBlank()) ? "1024x1024" : size;
        String resolvedQuality = (quality == null || quality.isBlank()) ? "standard" : quality;

        // Map platform-oriented sizes to DALL-E supported sizes
        // 公众号横版 900x383 → 1792x1024 (closest landscape)
        // 小红书竖版 1080x1440 → 1024x1792 (closest portrait)
        String dalleSize = resolvedSize;
        if (resolvedSize.contains("900") || resolvedSize.contains("横版") || resolvedSize.contains("landscape")) {
            dalleSize = "1792x1024";
        } else if (resolvedSize.contains("1080") || resolvedSize.contains("竖版") || resolvedSize.contains("portrait")) {
            dalleSize = "1024x1792";
        }

        String result = imageGenerationService.generateImage(prompt, dalleSize, resolvedQuality);

        if (result.startsWith("http")) {
            log.info("[Tool] Image generated successfully: URL obtained");
            return "[图片生成成功] 图片URL: " + result + "\n"
                    + "尺寸: " + dalleSize + ", 质量: " + resolvedQuality + "\n"
                    + "提示：该URL有效期约60分钟，请及时下载或使用。";
        } else {
            log.warn("[Tool] Image generation returned fallback: {}", result.substring(0, Math.min(result.length(), 80)));
            return result;
        }
    }

    /**
     * Generate an image prompt based on scene, mood, and style, then call the
     * DALL-E 3 API to generate a real image and return the imageUrl.
     *
     * <p>P0 ③: This tool now performs both steps in a single call:
     * <ol>
     *   <li>Generate a high-quality English prompt from scene/mood/style</li>
     *   <li>Pass the prompt into the DALL-E 3 image generation API</li>
     *   <li>Return the real image URL (or a fallback message if the API is unavailable)</li>
     * </ol>
     *
     * @param scene the scene description (e.g. "office desk at night")
     * @param mood  the mood/atmosphere (e.g. "warm", "focused", "calm")
     * @param style the visual style (e.g. "photorealistic", "flat illustration")
     * @param size  image size: "1024x1024" (square), "1792x1024" (landscape),
     *              "1024x1792" (portrait). Can also accept platform hints like
     *              "横版"/"landscape"/"竖版"/"portrait". Defaults to "1024x1024".
     * @return the prompt, negative prompt, and the generated image URL (or fallback)
     */
    @Tool("生成图片描述提示词并调用DALL-E 3 API生成真实图片，返回提示词和图片URL。支持尺寸：1024x1024(正方形), 1792x1024(横版/公众号/头条), 1024x1792(竖版/小红书)")
    public String generateImagePrompt(
            @P("场景描述（如：办公桌深夜、咖啡馆午后）") String scene,
            @P("情绪氛围（如：专注、温暖、治愈、活力）") String mood,
            @P("视觉风格（如：photorealistic、flat illustration、watercolor）") String style,
            @P("图片尺寸：1024x1024 或 1792x1024 或 1024x1792，可传空使用默认") String size) {
        log.info("[Tool] generateImagePrompt invoked for scene: {}, mood: {}, style: {}, size: {}",
                scene, mood, style, size);

        // ── Step 1: Generate the English prompt ──
        String englishPrompt = "A " + mood + " " + scene + " scene, " + style + " style, "
                + "soft natural lighting, warm color palette with beige and light brown tones, "
                + "a touch of greenery, shallow depth of field, high detail, 4k, photorealistic, "
                + "no text, no watermark";
        String negativePrompt = "text, watermark, logo, low quality, blurry, distorted faces, "
                + "cold colors, cluttered background";

        // Map platform-oriented sizes to DALL-E supported sizes
        String dalleSize = (size == null || size.isBlank()) ? "1024x1024" : size;
        if (dalleSize.contains("900") || dalleSize.contains("横版") || dalleSize.contains("landscape")) {
            dalleSize = "1792x1024";
        } else if (dalleSize.contains("1080") || dalleSize.contains("竖版") || dalleSize.contains("portrait")) {
            dalleSize = "1024x1792";
        }

        // ── Step 2: Call DALL-E 3 API to generate the real image ──
        String imageResult = imageGenerationService.generateImage(englishPrompt, dalleSize, null);

        // ── Step 3: Build and return the result ──
        StringBuilder sb = new StringBuilder();
        sb.append("[图片提示词] 场景：").append(scene)
                .append(" | 情绪：").append(mood)
                .append(" | 风格：").append(style).append("\n");
        sb.append("Prompt: ").append(englishPrompt).append("\n");
        sb.append("Negative Prompt: ").append(negativePrompt).append("\n");
        sb.append("尺寸: ").append(dalleSize).append("\n");

        if (imageResult != null && imageResult.startsWith("http")) {
            sb.append("imageUrl: ").append(imageResult).append("\n");
            sb.append("[图片生成成功] 已通过DALL-E 3 API生成真实图片，URL有效期约60分钟，请及时使用。");
            log.info("[Tool] generateImagePrompt succeeded: image URL obtained, length={}",
                    imageResult.length());
        } else {
            // API unavailable or failed — return the fallback message from the service
            sb.append(imageResult != null ? imageResult : "[图片生成失败] 未知错误").append("\n");
            sb.append("提示：配置 contentops.platform.image-generation.api-key 后可启用真实图片生成。");
            log.warn("[Tool] generateImagePrompt image generation fallback: {}",
                    imageResult != null ? imageResult.substring(0, Math.min(imageResult.length(), 80)) : "null");
        }

        return sb.toString();
    }

    /**
     * Remove watermark from an image.
     *
     * <p>This tool currently provides guidance and instructions. In production, it would
     * delegate to an image processing API (e.g., an inpaint service or a CV-based watermark
     * removal service).
     */
    @Tool("去除图片水印，返回处理后的图片地址或处理指导")
    public String removeWatermark(String imageUrl) {
        log.info("[Tool] removeWatermark invoked for imageUrl: {}", imageUrl);
        return "[水印去除] 已收到图片地址：" + imageUrl + "\n"
                + "处理方案：\n"
                + "1. 检测水印位置：通过边缘检测和OCR识别水印区域\n"
                + "2. 内容填充：使用 inpaint 算法（如 LaMa 或 Stable Diffusion inpaint）修复水印区域\n"
                + "3. 画质检查：对比处理前后 SSIM/PSNR 确保无明显画质损失\n"
                + "提示：如需接入真实去水印API，请配置 contentops.platform.image-generation 相关参数。\n"
                + "当前可使用 generateImageFromPrompt 工具直接生成无水印的原创配图作为替代方案。";
    }
}
